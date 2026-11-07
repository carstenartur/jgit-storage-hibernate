/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitHistoryQuery;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitSearchCursor;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitSearchHit;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import org.hibernate.SessionEventListener;
import org.hibernate.stat.Statistics;

/** Measures the public cursor and offset APIs over the same deterministic history. */
public final class HistoryScrollExportBenchmark {
  private static final String REPOSITORY = "scroll-export";
  private static final String EXACT_PATH = "src/Shared.java";
  private static final String SUBJECT = "Change shared source";
  private static final String AUTHOR = "Alice";
  private static final String EMAIL = "alice@example.com";
  private static final Instant BASE_TIME = Instant.parse("2026-08-09T00:00:00Z");
  private static final int TIMESTAMP_GROUP = 4;
  private static final int SEED_BATCH = 500;
  private static final int DEFAULT_GUARD = GitHistorySearchService.DEFAULT_MAX_OFFSET;

  private HistoryScrollExportBenchmark() {}

  static void run(
      Properties database,
      Path index,
      Path output,
      int documents,
      List<Integer> chunkSizes,
      int repetitions)
      throws IOException {
    if (documents < 1
        || documents > 1_000_000
        || chunkSizes.isEmpty()
        || chunkSizes.size() != chunkSizes.stream().distinct().count()
        || chunkSizes.stream()
            .anyMatch(size -> size < 1 || size > GitHistorySearchService.MAX_SCROLL_CHUNK_SIZE)
        || repetitions < 1
        || repetitions > 10) {
      throw new IllegalArgumentException("Invalid scroll benchmark matrix");
    }

    Properties properties = new Properties();
    properties.putAll(database);
    properties.setProperty("hibernate.generate_statistics", "true");
    properties.setProperty("hibernate.session.events.log", "false");
    properties.setProperty("hibernate.session.events.auto", JdbcEvents.class.getName());
    properties.setProperty("hibernate.search.backend.type", "lucene");
    properties.setProperty("hibernate.search.backend.directory.type", "local-filesystem");
    properties.setProperty("hibernate.search.backend.directory.root", index.toAbsolutePath().toString());
    properties.setProperty("hibernate.search.indexing.plan.synchronization.strategy", "sync");
    properties.remove(GitHistorySearchService.MAX_OFFSET_PROPERTY);

    try (var provider = provider(properties)) {
      seed(provider, documents);
      var service = new GitHistorySearchService(provider.getSessionFactory());
      assertEquals(DEFAULT_GUARD, service.maxOffset());
      for (QueryPath path : QueryPath.values()) {
        IllegalArgumentException rejection =
            assertThrows(
                IllegalArgumentException.class,
                () -> service.findChangeSummaries(path.query().offset(DEFAULT_GUARD + 1).limit(1).build()));
        assertTrue(rejection.getMessage().contains("scrollChangeSummaries"));
      }
    }

    // Reopen the same persistent Lucene index. Only the disposable comparison factory lifts the guard.
    properties.setProperty("hibernate.hbm2ddl.auto", "validate");
    properties.setProperty(GitHistorySearchService.MAX_OFFSET_PROPERTY, Integer.toString(documents));
    List<String> exports = new ArrayList<>();
    exports.add(
        "engine,documents,chunk_size,repeat,rows,chunks,first_chunk_ns,api_total_ns,wall_total_ns,"
            + "rows_per_second,gc_count,gc_ms,heap_before_bytes,heap_observed_max_bytes,heap_after_bytes,"
            + "sessions_before,sessions_during,sessions_after,jdbc_before,jdbc_during,jdbc_after,"
            + "transactions_completed");
    List<String> offsets = new ArrayList<>();
    offsets.add(
        "engine,documents,chunk_size,repeat,offset,rows,scroll_chunk_ns,scroll_prefix_ns,"
            + "offset_page_ns,first_object_id,last_object_id");

    Properties metadata;
    try (var provider = provider(properties)) {
      var factory = provider.getSessionFactory();
      var service = new GitHistorySearchService(factory);
      assertEquals(documents, service.maxOffset());
      metadata = environment(provider);
      metadata.setProperty("guard.default", Integer.toString(DEFAULT_GUARD));
      metadata.setProperty("guard.benchmark", Integer.toString(service.maxOffset()));
      verifyExactPathFilter(service);

      for (QueryPath path : QueryPath.values()) {
        for (int chunk : chunkSizes) {
          verifyEarlyCloseAndInterrupt(service, factory.getStatistics(), path, documents, chunk);
          List<Integer> sampleOffsets = sampleOffsets(documents, chunk);
          // Warm each page shape once; time the same nonempty windows after the sequential scan.
          for (int offset : sampleOffsets) {
            verifyHits(page(service, path, offset, chunk), documents, offset, chunk);
          }
          for (int repeat = 1; repeat <= repetitions; repeat++) {
            measure(
                service, factory.getStatistics(), path, documents, chunk, repeat, sampleOffsets,
                exports, offsets);
          }
        }
      }
    } finally {
      JdbcEvents.IN_USE.remove();
    }

    Files.createDirectories(output);
    Files.write(output.resolve("exports.csv"), exports);
    Files.write(output.resolve("offsets.csv"), offsets);
    try (var writer = Files.newBufferedWriter(output.resolve("environment.properties"))) {
      metadata.store(writer, "History scroll/export benchmark");
    }
  }

  private static void measure(
      GitHistorySearchService service,
      Statistics statistics,
      QueryPath path,
      int documents,
      int chunk,
      int repeat,
      List<Integer> offsets,
      List<String> exports,
      List<String> pages) {
    Map<Integer, Window> samples = new HashMap<>();
    Resources before = Resources.read(statistics);
    assertEquals(0, before.sessions(), "Benchmark starts with no open Hibernate sessions");
    assertEquals(0, before.connections(), "Benchmark starts with no borrowed JDBC connections");
    Resources during = null;
    Resources after;
    Gc gcBefore = Gc.read();
    long heapBefore = heapUsed();
    long heapMax = heapBefore;
    long firstChunk = 0;
    int rows = 0;
    int chunks = 0;
    long start = System.nanoTime();
    long apiTime;
    try (CommitSearchCursor cursor =
        service.scrollChangeSummaries(path.query().unbounded().build(), chunk)) {
      apiTime = System.nanoTime() - start;
      while (true) {
        long nextStart = System.nanoTime();
        List<CommitSearchHit> hits = cursor.nextChunk();
        long chunkTime = System.nanoTime() - nextStart;
        apiTime += chunkTime;
        if (hits.isEmpty()) {
          assertTrue(cursor.isClosed(), "Exhaustion must close the cursor");
          after = Resources.read(statistics);
          verifyCursorReleased(path, before, after);
          break;
        }
        if (chunks == 0) {
          firstChunk = apiTime;
          during = Resources.read(statistics);
        }
        if (offsets.contains(rows)) {
          samples.put(rows, new Window(chunkTime, apiTime));
        }
        verifyHits(hits, documents, rows, chunk);
        rows += hits.size();
        chunks++;
        if (chunks == 1 || chunks % 64 == 0 || rows == documents) {
          heapMax = Math.max(heapMax, heapUsed());
        }
      }
    }
    long wallTime = System.nanoTime() - start;
    long heapAfter = heapUsed();
    Gc gcAfter = Gc.read();
    assertEquals(documents, rows, "Export must cover the entire result set");
    assertEquals(1, during.sessions() - before.sessions(), "Exactly one cursor session");
    assertEquals(
        path == QueryPath.ORM ? 1 : 0,
        during.connections() - before.connections(),
        "Only an ORM cursor should hold a JDBC connection");
    exports.add(
        csv(path.label(), documents, chunk, repeat, rows, chunks, firstChunk, apiTime, wallTime,
            rows * 1_000_000_000.0 / wallTime, gcAfter.count() - gcBefore.count(),
            gcAfter.millis() - gcBefore.millis(), heapBefore, Math.max(heapMax, heapAfter), heapAfter,
            before.sessions(), during.sessions(), after.sessions(),
            before.connections(), during.connections(), after.connections(),
            after.transactions() - before.transactions()));

    for (int offset : offsets) {
      Resources pageBefore = Resources.read(statistics);
      long pageStart = System.nanoTime();
      List<CommitSearchHit> hits = page(service, path, offset, chunk);
      long pageTime = System.nanoTime() - pageStart;
      Resources pageAfter = Resources.read(statistics);
      pageBefore.verifyReleased(pageAfter);
      verifyQueryPath(path, pageBefore, pageAfter);
      verifyHits(hits, documents, offset, chunk);
      Window sample = samples.get(offset);
      pages.add(csv(path.label(), documents, chunk, repeat, offset, hits.size(), sample.chunkTime(),
          sample.prefixTime(), pageTime, hits.getFirst().objectId(), hits.getLast().objectId()));
    }
  }

  private static void verifyEarlyCloseAndInterrupt(
      GitHistorySearchService service, Statistics statistics, QueryPath path, int documents, int chunk) {
    CommitHistoryQuery query = path.query().unbounded().build();
    for (int chunks : List.of(1, 2)) {
      Resources before = Resources.read(statistics);
      try (CommitSearchCursor cursor = service.scrollChangeSummaries(query, chunk)) {
        for (int i = 0; i < chunks && i * chunk < documents; i++) {
          verifyHits(cursor.nextChunk(), documents, i * chunk, chunk);
        }
      }
      verifyCursorReleased(path, before, Resources.read(statistics));
    }

    Resources before = Resources.read(statistics);
    try (CommitSearchCursor cursor = service.scrollChangeSummaries(query, chunk)) {
      verifyHits(cursor.nextChunk(), documents, 0, chunk);
      Thread.currentThread().interrupt();
      try {
        assertThrows(CancellationException.class, cursor::nextChunk);
        assertTrue(Thread.currentThread().isInterrupted(), "Cursor must preserve the interrupt");
        assertTrue(cursor.isClosed(), "Interruption must close the cursor");
      } finally {
        Thread.interrupted();
      }
      verifyCursorReleased(path, before, Resources.read(statistics));
    }
  }

  private static void verifyCursorReleased(QueryPath path, Resources before, Resources after) {
    before.verifyReleased(after);
    verifyQueryPath(path, before, after);
    assertEquals(
        path == QueryPath.ORM ? 1 : 0,
        after.transactions() - before.transactions(),
        "Only an ORM cursor should finish a Hibernate transaction on close");
  }

  private static void verifyQueryPath(QueryPath path, Resources before, Resources after) {
    if (path == QueryPath.SEARCH) {
      assertEquals(before.statements(), after.statements(), "Indexed summaries must not run SQL");
    }
  }

  private static void verifyExactPathFilter(GitHistorySearchService service) {
    // The basename is present as a substring but is not a complete changed path.
    var query = CommitHistoryQuery.forRepository(REPOSITORY)
        .touchingExactPath("Shared.java")
        .limit(1)
        .build();
    assertTrue(service.findChangeSummaries(query).isEmpty(), "Offset query must apply exact path");
    try (CommitSearchCursor cursor = service.scrollChangeSummaries(query, 1)) {
      assertTrue(cursor.nextChunk().isEmpty(), "Indexed cursor must apply exact path");
      assertTrue(cursor.isClosed(), "An empty indexed result must close the cursor");
    }
  }

  private static List<CommitSearchHit> page(
      GitHistorySearchService service, QueryPath path, int offset, int chunk) {
    return service.findChangeSummaries(path.query().offset(offset).limit(chunk).build());
  }

  private static List<Integer> sampleOffsets(int documents, int chunk) {
    int guard = DEFAULT_GUARD / chunk * chunk;
    return new TreeSet<>(List.of(0, chunk, documents / 2 / chunk * chunk,
            (documents - 1) / chunk * chunk, guard, guard + chunk))
        .stream()
        .filter(offset -> offset < documents)
        .toList();
  }

  private static void verifyHits(
      List<CommitSearchHit> hits, int documents, int offset, int chunk) {
    assertEquals(Math.min(chunk, documents - offset), hits.size(), "Rows at offset " + offset);
    for (int i = 0; i < hits.size(); i++) {
      CommitSearchHit hit = hits.get(i);
      int ordinal = offset + i;
      // Walk timestamp groups newest first, but walk object IDs within each group ascending.
      int newest = (documents - 1) / TIMESTAMP_GROUP * TIMESTAMP_GROUP;
      int newestSize = documents - newest;
      int olderOrdinal = ordinal - newestSize;
      int value = olderOrdinal < 0
          ? newest + ordinal
          : newest - (1 + olderOrdinal / TIMESTAMP_GROUP) * TIMESTAMP_GROUP
              + olderOrdinal % TIMESTAMP_GROUP;
      CommitSearchHit expected = projection(value);
      if (!expected.equals(hit)) {
        assertEquals(expected, hit, "Unexpected projected row at ordinal " + ordinal);
      }
    }
  }

  private static CommitSearchHit projection(int value) {
    String suffix = Integer.toHexString(value);
    String objectId = "0".repeat(40 - suffix.length()) + suffix;
    Instant time = BASE_TIME.plusSeconds(value / TIMESTAMP_GROUP);
    return new CommitSearchHit(objectId, SUBJECT, AUTHOR, EMAIL, AUTHOR, EMAIL, time, time);
  }

  private static void seed(HibernateSessionFactoryProvider provider, int documents) {
    // Add a matching document for another repository to detect accidental cross-repository hits.
    for (int start = 0; start <= documents; start += SEED_BATCH) {
      try (var session = provider.getSessionFactory().openSession()) {
        var transaction = session.beginTransaction();
        for (int value = start; value < Math.min(start + SEED_BATCH, documents + 1); value++) {
          CommitSearchHit fields = projection(value);
          GitCommitIndex row = new GitCommitIndex();
          row.setProjectionKey("history-scroll-" + Integer.toHexString(value));
          row.setRepositoryName(value == documents ? "other-repository" : REPOSITORY);
          row.setObjectId(fields.objectId());
          row.setShortMessage(fields.shortMessage());
          row.setFullMessage(fields.shortMessage() + "\n\nSynthetic history scroll benchmark");
          row.setAuthorName(fields.authorName());
          row.setAuthorEmail(fields.authorEmail());
          row.setAuthorTime(fields.authorTime());
          row.setCommitterName(fields.committerName());
          row.setCommitterEmail(fields.committerEmail());
          row.setCommitterTime(fields.committerTime());
          row.setChangedPaths(EXACT_PATH);
          row.setChangedText("A shared source change");
          session.persist(row);
        }
        transaction.commit();
      }
    }
  }

  private static HibernateSessionFactoryProvider provider(Properties properties) {
    return new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses());
  }

  private static Properties environment(HibernateSessionFactoryProvider provider) {
    Properties metadata = new Properties();
    metadata.setProperty("schema.version", "1");
    metadata.setProperty("fixture", "history-scroll-projection-v1");
    metadata.setProperty("cache.condition", "reopened-factory-with-query-warmup");
    metadata.setProperty("commit", System.getProperty("benchmark.commit", "local"));
    metadata.setProperty("runner", System.getenv().getOrDefault("RUNNER_NAME", "local"));
    metadata.setProperty("runner.image", System.getenv().getOrDefault("ImageVersion", "local"));
    metadata.setProperty("java.version", System.getProperty("java.runtime.version"));
    metadata.setProperty("java.vm", System.getProperty("java.vm.name"));
    metadata.setProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
    metadata.setProperty("processors", Integer.toString(Runtime.getRuntime().availableProcessors()));
    var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    metadata.setProperty("heap.init.bytes", Long.toString(heap.getInit()));
    metadata.setProperty("heap.max.bytes", Long.toString(heap.getMax()));
    try (var session = provider.getSessionFactory().openSession()) {
      session.doWork(connection -> {
        var db = connection.getMetaData();
        metadata.setProperty("database.product", db.getDatabaseProductName());
        metadata.setProperty("database.version", db.getDatabaseProductVersion());
        metadata.setProperty("jdbc.driver", db.getDriverName() + " " + db.getDriverVersion());
      });
    }
    return metadata;
  }

  private static long heapUsed() {
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
  }

  private static String csv(Object... fields) {
    StringJoiner row = new StringJoiner(",");
    for (Object field : fields) {
      row.add(field.toString());
    }
    return row.toString();
  }

  private enum QueryPath {
    ORM,
    SEARCH;

    CommitHistoryQuery.Builder query() {
      var builder = CommitHistoryQuery.forRepository(REPOSITORY);
      return this == SEARCH ? builder.touchingExactPath(EXACT_PATH) : builder;
    }

    String label() {
      return name().toLowerCase(Locale.ROOT);
    }
  }

  private record Window(long chunkTime, long prefixTime) {}

  private record Gc(long count, long millis) {
    static Gc read() {
      long count = 0;
      long millis = 0;
      for (var collector : ManagementFactory.getGarbageCollectorMXBeans()) {
        count += Math.max(0, collector.getCollectionCount());
        millis += Math.max(0, collector.getCollectionTime());
      }
      return new Gc(count, millis);
    }
  }

  private record Resources(long sessions, int connections, long transactions, long statements) {
    static Resources read(Statistics statistics) {
      return new Resources(
          statistics.getSessionOpenCount() - statistics.getSessionCloseCount(),
          JdbcEvents.IN_USE.get(),
          statistics.getTransactionCount(),
          statistics.getPrepareStatementCount());
    }

    void verifyReleased(Resources after) {
      assertEquals(sessions, after.sessions, "Operation leaked a Hibernate session");
      assertEquals(connections, after.connections, "Operation leaked a JDBC connection");
    }
  }

  /** Counts connection borrows and returns for the benchmark thread. */
  public static final class JdbcEvents implements SessionEventListener {
    private static final ThreadLocal<Integer> IN_USE = ThreadLocal.withInitial(() -> 0);

    @Override
    public void jdbcConnectionAcquisitionEnd() {
      IN_USE.set(IN_USE.get() + 1);
    }

    @Override
    public void jdbcConnectionReleaseEnd() {
      IN_USE.set(IN_USE.get() - 1);
    }
  }
}
