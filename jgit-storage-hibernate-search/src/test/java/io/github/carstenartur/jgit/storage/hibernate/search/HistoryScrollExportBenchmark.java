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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringJoiner;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import org.hibernate.SessionEventListener;
import org.hibernate.stat.Statistics;

/** Measures the public cursor and offset APIs over the same deterministic history. */
public final class HistoryScrollExportBenchmark {
  private static final String REPOSITORY_NAME = "scroll-export";
  private static final String EXACT_PATH = "src/Shared.java";
  private static final String SELECTED_PATH = "src/Selected.java";
  private static final String RARE_PATH = "src/Rare.java";
  private static final String OTHER_AUTHOR = "Bob";
  private static final String OTHER_EMAIL = "bob@example.com";
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
    properties.setProperty(
        "hibernate.search.backend.directory.root", index.toAbsolutePath().toString());
    properties.setProperty("hibernate.search.indexing.plan.synchronization.strategy", "sync");
    properties.remove(GitHistorySearchService.MAX_OFFSET_PROPERTY);

    try (var provider = provider(properties)) {
      seed(provider, documents);
      var service = new GitHistorySearchService(provider.getSessionFactory());
      assertEquals(DEFAULT_GUARD, service.maxOffset());
      for (Scenario scenario : List.of(Scenario.REPOSITORY, Scenario.INDEXED_EXPORT)) {
        IllegalArgumentException rejection =
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    service.findChangeSummaries(
                        scenario.query(documents).offset(DEFAULT_GUARD + 1).limit(1).build()));
        assertTrue(rejection.getMessage().contains("scrollChangeSummaries"));
      }
    }

    // Reopen the same persistent Lucene index. Only the disposable comparison factory lifts the
    // guard.
    properties.setProperty("hibernate.hbm2ddl.auto", "validate");
    properties.setProperty(
        GitHistorySearchService.MAX_OFFSET_PROPERTY, Integer.toString(documents));
    List<String> exports = new ArrayList<>();
    exports.add(
        "engine,scenario,cache,documents,chunk_size,repeat,rows,chunks,first_chunk_ns,api_total_ns,"
            + "wall_total_ns,rows_per_second,chunk_p50_upper_ns,chunk_p95_upper_ns,chunk_p99_upper_ns,"
            + "gc_count,gc_ms,heap_before_gc_bytes,heap_observed_max_bytes,heap_after_bytes,"
            + "heap_after_gc_bytes,max_chunk_rows,sessions_before,sessions_during,sessions_after,"
            + "jdbc_before,jdbc_during,jdbc_after,transactions_before,transactions_during,"
            + "transactions_after,transactions_completed");
    List<String> offsets = new ArrayList<>();
    offsets.add(
        "engine,scenario,cache,documents,chunk_size,repeat,offset,rows,scroll_chunk_ns,"
            + "scroll_prefix_ns,offset_page_ns,first_object_id,last_object_id");

    int reopenChunk =
        chunkSizes.contains(GitHistorySearchService.DEFAULT_SCROLL_CHUNK_SIZE)
            ? GitHistorySearchService.DEFAULT_SCROLL_CHUNK_SIZE
            : chunkSizes.getFirst();
    Properties metadata;
    try {
      // The first query on each new factory is measured before warming any query shape.
      for (Scenario scenario : List.of(Scenario.REPOSITORY, Scenario.INDEXED_EXPORT)) {
        try (var provider = provider(properties)) {
          var factory = provider.getSessionFactory();
          var service = new GitHistorySearchService(factory);
          measure(
              service,
              factory.getStatistics(),
              scenario,
              documents,
              documents,
              reopenChunk,
              1,
              "reopened",
              List.of(),
              exports,
              offsets);
        }
      }

      try (var provider = provider(properties)) {
        var factory = provider.getSessionFactory();
        var service = new GitHistorySearchService(factory);
        assertEquals(documents, service.maxOffset());
        metadata = environment(provider);
        metadata.setProperty("guard.default", Integer.toString(DEFAULT_GUARD));
        metadata.setProperty("guard.benchmark", Integer.toString(service.maxOffset()));

        for (Scenario scenario : Scenario.values()) {
          int expectedRows = scenario.count(documents);
          for (int chunk : chunkSizes) {
            verifyCloseAndInterrupt(
                service, factory.getStatistics(), scenario, documents, expectedRows, chunk);
            List<Integer> sampleOffsets = sampleOffsets(expectedRows, chunk);
            for (int offset : sampleOffsets) {
              verifyPage(
                  page(service, scenario, documents, offset, chunk),
                  scenario,
                  documents,
                  expectedRows,
                  offset,
                  chunk);
            }
            for (int repeat = 1; repeat <= repetitions; repeat++) {
              measure(
                  service,
                  factory.getStatistics(),
                  scenario,
                  documents,
                  expectedRows,
                  chunk,
                  repeat,
                  "warm",
                  sampleOffsets,
                  exports,
                  offsets);
            }
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
      Scenario scenario,
      int documents,
      int expectedRows,
      int chunk,
      int repeat,
      String cache,
      List<Integer> offsets,
      List<String> exports,
      List<String> pages) {
    Map<Integer, Window> samples = new HashMap<>();
    var expected = new ExpectedRows(scenario, documents);
    var chunkLatencies = new ChunkLatencies();
    long heapBefore = heapAfterGc();
    Resources before = Resources.read(statistics);
    assertEquals(0, before.sessions(), "Benchmark starts with no open Hibernate sessions");
    assertEquals(0, before.connections(), "Benchmark starts with no borrowed JDBC connections");
    Resources during;
    Resources after;
    Gc gcBefore = Gc.read();
    long heapMax = heapBefore;
    long firstChunk = 0;
    int rows = 0;
    int chunks = 0;
    int maxChunkRows = 0;
    long start = System.nanoTime();
    long apiTime;
    try (CommitSearchCursor cursor =
        service.scrollChangeSummaries(scenario.query(documents).unbounded().build(), chunk)) {
      apiTime = System.nanoTime() - start;
      during = Resources.read(statistics);
      while (true) {
        long nextStart = System.nanoTime();
        List<CommitSearchHit> hits = cursor.nextChunk();
        long chunkTime = System.nanoTime() - nextStart;
        apiTime += chunkTime;
        if (hits.isEmpty()) {
          assertTrue(cursor.isClosed(), "Exhaustion must close the cursor");
          after = Resources.read(statistics);
          verifyCursorReleased(scenario, before, after);
          if (rows == 0 && offsets.contains(0)) {
            samples.put(0, new Window(chunkTime, apiTime));
          }
          break;
        }
        chunkLatencies.add(chunkTime);
        if (chunks == 0) {
          firstChunk = apiTime;
        }
        if (offsets.contains(rows)) {
          samples.put(rows, new Window(chunkTime, apiTime));
        }
        verifyHits(hits, expected, expectedRows, rows, chunk);
        rows += hits.size();
        chunks++;
        maxChunkRows = Math.max(maxChunkRows, hits.size());
        if (chunks == 1 || chunks % 64 == 0 || rows == expectedRows) {
          heapMax = Math.max(heapMax, heapUsed());
        }
      }
    }
    long wallTime = System.nanoTime() - start;
    long heapAfter = heapUsed();
    Gc gcAfter = Gc.read();
    long heapRetained = heapAfterGc();
    assertEquals(expectedRows, rows, "Export must cover the entire result set");
    assertEquals(-1, expected.next(), "Export omitted a fixture match");
    assertEquals(1, during.sessions() - before.sessions(), "Exactly one cursor session");
    assertEquals(
        scenario.indexed ? 0 : 1,
        during.connections() - before.connections(),
        "Only an ORM cursor should hold a JDBC connection");
    assertEquals(
        before.transactions(),
        during.transactions(),
        "A cursor transaction stays open until close");
    exports.add(
        csv(
            scenario.engine(),
            scenario.label,
            cache,
            documents,
            chunk,
            repeat,
            rows,
            chunks,
            firstChunk,
            apiTime,
            wallTime,
            rows * 1_000_000_000.0 / wallTime,
            chunkLatencies.percentile(50),
            chunkLatencies.percentile(95),
            chunkLatencies.percentile(99),
            gcAfter.count() - gcBefore.count(),
            gcAfter.millis() - gcBefore.millis(),
            heapBefore,
            Math.max(heapMax, heapAfter),
            heapAfter,
            heapRetained,
            maxChunkRows,
            before.sessions(),
            during.sessions(),
            after.sessions(),
            before.connections(),
            during.connections(),
            after.connections(),
            before.transactions(),
            during.transactions(),
            after.transactions(),
            after.transactions() - before.transactions()));

    for (int offset : offsets) {
      Resources pageBefore = Resources.read(statistics);
      long pageStart = System.nanoTime();
      List<CommitSearchHit> hits = page(service, scenario, documents, offset, chunk);
      long pageTime = System.nanoTime() - pageStart;
      Resources pageAfter = Resources.read(statistics);
      pageBefore.verifyReleased(pageAfter);
      verifyQueryPath(scenario, pageBefore, pageAfter);
      verifyPage(hits, scenario, documents, expectedRows, offset, chunk);
      Window sample = samples.get(offset);
      pages.add(
          csv(
              scenario.engine(),
              scenario.label,
              cache,
              documents,
              chunk,
              repeat,
              offset,
              hits.size(),
              sample.chunkTime(),
              sample.prefixTime(),
              pageTime,
              hits.isEmpty() ? "" : hits.getFirst().objectId(),
              hits.isEmpty() ? "" : hits.getLast().objectId()));
    }
  }

  private static void verifyCloseAndInterrupt(
      GitHistorySearchService service,
      Statistics statistics,
      Scenario scenario,
      int documents,
      int expectedRows,
      int chunk) {
    CommitHistoryQuery query = scenario.query(documents).unbounded().build();
    int chunks = (expectedRows + chunk - 1) / chunk;
    for (int stop : List.of(1, Math.max(2, Math.min(10, chunks / 2)))) {
      if (stop >= chunks) {
        continue;
      }
      Resources before = Resources.read(statistics);
      var expected = new ExpectedRows(scenario, documents);
      try (CommitSearchCursor cursor = service.scrollChangeSummaries(query, chunk)) {
        for (int i = 0; i < stop; i++) {
          verifyHits(cursor.nextChunk(), expected, expectedRows, i * chunk, chunk);
        }
      }
      verifyCursorReleased(scenario, before, Resources.read(statistics));
    }

    Resources before = Resources.read(statistics);
    try (CommitSearchCursor cursor = service.scrollChangeSummaries(query, chunk)) {
      if (expectedRows > 0) {
        verifyHits(
            cursor.nextChunk(), new ExpectedRows(scenario, documents), expectedRows, 0, chunk);
      }
      Thread.currentThread().interrupt();
      try {
        assertThrows(CancellationException.class, cursor::nextChunk);
        assertTrue(Thread.currentThread().isInterrupted(), "Cursor must preserve the interrupt");
        assertTrue(cursor.isClosed(), "Interruption must close the cursor");
      } finally {
        Thread.interrupted();
      }
      verifyCursorReleased(scenario, before, Resources.read(statistics));
    }
  }

  private static void verifyCursorReleased(Scenario scenario, Resources before, Resources after) {
    before.verifyReleased(after);
    verifyQueryPath(scenario, before, after);
    assertEquals(
        scenario.indexed ? 0 : 1,
        after.transactions() - before.transactions(),
        "Only an ORM cursor should finish a Hibernate transaction on close");
  }

  private static void verifyQueryPath(Scenario scenario, Resources before, Resources after) {
    if (scenario.indexed) {
      assertEquals(before.statements(), after.statements(), "Indexed summaries must not run SQL");
    }
  }

  private static List<CommitSearchHit> page(
      GitHistorySearchService service, Scenario scenario, int documents, int offset, int chunk) {
    return service.findChangeSummaries(
        scenario.query(documents).offset(offset).limit(chunk).build());
  }

  private static List<Integer> sampleOffsets(int rows, int chunk) {
    if (rows == 0) {
      return List.of(0);
    }
    int guard = DEFAULT_GUARD / chunk * chunk;
    return new TreeSet<>(
            List.of(
                0,
                chunk,
                rows / 10 / chunk * chunk,
                rows / 2 / chunk * chunk,
                (rows - 1) / chunk * chunk,
                Math.max(0, guard - chunk),
                guard,
                guard + chunk))
        .stream().filter(offset -> offset < rows).toList();
  }

  private static void verifyPage(
      List<CommitSearchHit> hits,
      Scenario scenario,
      int documents,
      int rows,
      int offset,
      int chunk) {
    var expected = new ExpectedRows(scenario, documents);
    for (int i = 0; i < offset; i++) {
      expected.next();
    }
    verifyHits(hits, expected, rows, offset, chunk);
  }

  private static void verifyHits(
      List<CommitSearchHit> hits, ExpectedRows expected, int rows, int offset, int chunk) {
    assertEquals(Math.min(chunk, rows - offset), hits.size(), "Rows at offset " + offset);
    for (int i = 0; i < hits.size(); i++) {
      CommitSearchHit expectedHit = projection(expected.next());
      CommitSearchHit hit = hits.get(i);
      if (!expectedHit.equals(hit)) {
        assertEquals(expectedHit, hit, "Unexpected projected row at ordinal " + (offset + i));
      }
    }
  }

  private static CommitSearchHit projection(int value) {
    String suffix = Integer.toHexString(value);
    String objectId = "0".repeat(40 - suffix.length()) + suffix;
    Instant time = BASE_TIME.plusSeconds(value / TIMESTAMP_GROUP);
    return new CommitSearchHit(
        objectId,
        SUBJECT,
        isOtherAuthor(value) ? OTHER_AUTHOR : AUTHOR,
        isOtherAuthor(value) ? OTHER_EMAIL : EMAIL,
        AUTHOR,
        EMAIL,
        time,
        time);
  }

  private static boolean isOtherAuthor(int value) {
    return value % 3 == 0;
  }

  private static boolean isSelectedPath(int value) {
    int withinGroup = value % TIMESTAMP_GROUP;
    return withinGroup == 1 || withinGroup == 2;
  }

  private static boolean isRarePath(int value) {
    return value % 997 == 1;
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
          row.setRepositoryName(value == documents ? "other-repository" : REPOSITORY_NAME);
          row.setObjectId(fields.objectId());
          row.setShortMessage(fields.shortMessage());
          row.setFullMessage(fields.shortMessage() + "\n\nSynthetic history scroll benchmark");
          row.setAuthorName(fields.authorName());
          row.setAuthorEmail(fields.authorEmail());
          row.setAuthorTime(fields.authorTime());
          row.setCommitterName(fields.committerName());
          row.setCommitterEmail(fields.committerEmail());
          row.setCommitterTime(fields.committerTime());
          row.setChangedPaths(
              EXACT_PATH
                  + (isSelectedPath(value) ? "\n" + SELECTED_PATH : "")
                  + (isRarePath(value) ? "\n" + RARE_PATH : ""));
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
    metadata.setProperty("schema.version", "2");
    metadata.setProperty("fixture", "history-scroll-projection-v2");
    metadata.setProperty("cache.condition", "first-query-after-factory-reopen-and-warmed-query");
    metadata.setProperty("heap.retention", "whole-process-used-after-explicit-gc");
    metadata.setProperty("commit", System.getProperty("benchmark.commit", "local"));
    metadata.setProperty("runner", System.getenv().getOrDefault("RUNNER_NAME", "local"));
    metadata.setProperty("runner.image", System.getenv().getOrDefault("ImageVersion", "local"));
    metadata.setProperty("java.version", System.getProperty("java.runtime.version"));
    metadata.setProperty("java.vm", System.getProperty("java.vm.name"));
    metadata.setProperty("os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
    metadata.setProperty(
        "processors", Integer.toString(Runtime.getRuntime().availableProcessors()));
    var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    metadata.setProperty("heap.init.bytes", Long.toString(heap.getInit()));
    metadata.setProperty("heap.max.bytes", Long.toString(heap.getMax()));
    try (var session = provider.getSessionFactory().openSession()) {
      session.doWork(
          connection -> {
            var db = connection.getMetaData();
            metadata.setProperty("database.product", db.getDatabaseProductName());
            metadata.setProperty("database.version", db.getDatabaseProductVersion());
            metadata.setProperty("jdbc.driver", db.getDriverName() + " " + db.getDriverVersion());
          });
    }
    return metadata;
  }

  private static long heapAfterGc() {
    System.gc();
    return heapUsed();
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

  private enum Scenario {
    REPOSITORY("repository", false),
    INDEXED_EXPORT("indexed-export", true),
    PATH("path", true),
    AUTHOR("author", false),
    TIME("time", false),
    COMBINED("combined", true),
    RARE("rare", true),
    EMPTY("empty", true);

    private final String label;
    private final boolean indexed;

    Scenario(String label, boolean indexed) {
      this.label = label;
      this.indexed = indexed;
    }

    String engine() {
      return indexed ? "search" : "orm";
    }

    CommitHistoryQuery.Builder query(int documents) {
      var builder = CommitHistoryQuery.forRepository(REPOSITORY_NAME);
      var recent = BASE_TIME.plusSeconds(recentGroup(documents));
      var latest = BASE_TIME.plusSeconds((documents - 1) / TIMESTAMP_GROUP);
      return switch (this) {
        case REPOSITORY -> builder;
        case INDEXED_EXPORT -> builder.touchingExactPath(EXACT_PATH);
        case PATH -> builder.touchingExactPath(SELECTED_PATH);
        case AUTHOR -> builder.authoredBy(OTHER_EMAIL);
        case TIME -> builder.committedBetween(recent, latest);
        case COMBINED ->
            builder
                .touchingExactPath(SELECTED_PATH)
                .authoredBy(OTHER_EMAIL)
                .committedBetween(recent, latest);
        case RARE -> builder.touchingExactPath(RARE_PATH);
        // A basename is a substring of the fixture path but never a complete changed path.
        case EMPTY -> builder.touchingExactPath("Shared.java");
      };
    }

    boolean matches(int value, int documents) {
      return switch (this) {
        case REPOSITORY, INDEXED_EXPORT -> true;
        case PATH -> isSelectedPath(value);
        case AUTHOR -> isOtherAuthor(value);
        case TIME -> value / TIMESTAMP_GROUP >= recentGroup(documents);
        case COMBINED ->
            isSelectedPath(value)
                && isOtherAuthor(value)
                && value / TIMESTAMP_GROUP >= recentGroup(documents);
        case RARE -> isRarePath(value);
        case EMPTY -> false;
      };
    }

    int count(int documents) {
      int matches = 0;
      for (int value = 0; value < documents; value++) {
        if (matches(value, documents)) {
          matches++;
        }
      }
      return matches;
    }

    private static int recentGroup(int documents) {
      return ((documents - 1) / TIMESTAMP_GROUP) * 3 / 4;
    }
  }

  /** Computes expected results in order without retaining a list proportional to the fixture. */
  private static final class ExpectedRows {
    private final Scenario scenario;
    private final int documents;
    private int group;
    private int value;

    ExpectedRows(Scenario scenario, int documents) {
      this.scenario = scenario;
      this.documents = documents;
      group = (documents - 1) / TIMESTAMP_GROUP * TIMESTAMP_GROUP;
      value = group;
    }

    int next() {
      while (group >= 0) {
        int candidate = value++;
        if (value == Math.min(documents, group + TIMESTAMP_GROUP)) {
          group -= TIMESTAMP_GROUP;
          value = group;
        }
        if (scenario.matches(candidate, documents)) {
          return candidate;
        }
      }
      return -1;
    }
  }

  /** Fixed memory. Each reported percentile is the upper bound of a 5% latency bucket. */
  private static final class ChunkLatencies {
    private static final long[] UPPER = upperBounds();
    private final int[] counts = new int[UPPER.length];
    private int size;

    void add(long nanos) {
      int bucket = Arrays.binarySearch(UPPER, nanos);
      counts[bucket >= 0 ? bucket : -bucket - 1]++;
      size++;
    }

    long percentile(int percent) {
      if (size == 0) {
        return 0;
      }
      int rank = (size * percent + 99) / 100;
      for (int i = 0; i < counts.length; i++) {
        rank -= counts[i];
        if (rank <= 0) {
          return UPPER[i];
        }
      }
      throw new IllegalStateException("Missing chunk latency");
    }

    private static long[] upperBounds() {
      long[] bounds = new long[1024];
      for (int i = 1; i < bounds.length - 1; i++) {
        long step = Math.max(1, bounds[i - 1] / 20);
        if (Long.MAX_VALUE - bounds[i - 1] < step) {
          Arrays.fill(bounds, i, bounds.length, Long.MAX_VALUE);
          break;
        }
        bounds[i] = bounds[i - 1] + step;
      }
      bounds[bounds.length - 1] = Long.MAX_VALUE;
      return bounds;
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
