/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import java.io.IOException;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.MultiTerms;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.search.mapper.orm.Search;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Opt-in physical Lucene shard comparison using the actual commit projection mapping. */
@EnabledIfSystemProperty(named = "search.routing.run", matches = "true")
class SearchRoutingPerformanceIT {

  private static final int BATCH = 250;
  private static final int SMALL_BURST = 50;
  private static final int LARGE_BURST = 500;
  private static final String COMMON_PATH = "src/shared/Workflow.java";
  private static final String HEADER =
      "layout,repositories,initial_documents,mode,shards,occupied_shards,documents_per_shard,"
          + "files,segments,index_bytes,h2_bytes,peak_sampled_jvm_heap_bytes,"
          + "indexing_ms,indexed_docs_per_second,burst_50_ms,burst_500_ms,"
          + "text_routed_p50_us,text_routed_p95_us,text_fanout_p50_us,text_fanout_p95_us,"
          + "path_routed_p50_us,path_routed_p95_us,path_fanout_p50_us,path_fanout_p95_us,"
          + "admin_text_p50_us,admin_text_p95_us,restart_to_first_hit_ms,"
          + "purged_docs,routed_purge_and_refresh_ms,mass_rebuild_ms";

  @TempDir Path temporaryDirectory;

  @Test
  void comparePhysicalShards() throws Exception {
    int documents = Integer.getInteger("search.routing.documents", 10_000);
    int manyRepositories = Integer.getInteger("search.routing.repositories", 100);
    int warmup = Integer.getInteger("search.routing.warmup", 80);
    int samples = Integer.getInteger("search.routing.samples", 200);
    String[] modes = System.getProperty("search.routing.shards", "baseline,1,2,4,8").split(",");
    String[] layouts =
        System.getProperty("search.routing.layouts", "single,uniform,skewed").split(",");
    if (documents < 1 || manyRepositories < 2 || warmup < 1 || samples < 2) {
      throw new IllegalArgumentException(
          "documents, warmup and samples must be positive; use at least two repositories and"
              + " samples");
    }
    Path results = Path.of(System.getProperty("search.routing.output", "target/search-routing"));
    Files.createDirectories(results);
    writeEnvironment(results.resolve("environment.properties"), warmup, samples);
    try (Writer csv = Files.newBufferedWriter(results.resolve("shards.csv"))) {
      csv.write(HEADER + "\n");
      for (String value : layouts) {
        Layout layout = Layout.valueOf(value.toUpperCase(Locale.ROOT));
        int repositories = layout == Layout.SINGLE ? 1 : manyRepositories;
        if (documents < repositories || (layout == Layout.SKEWED && documents / 5 < repositories)) {
          throw new IllegalArgumentException(
              "fixture must have enough documents for every repository");
        }
        for (String mode : modes) {
          boolean routed = !"baseline".equals(mode);
          int shards = routed ? Integer.parseInt(mode) : 1;
          if (shards != 1 && shards != 2 && shards != 4 && shards != 8) {
            throw new IllegalArgumentException("unsupported experimental shard count: " + shards);
          }
          Path trial = Files.createTempDirectory(temporaryDirectory, "search-routing-");
          try {
            String observation =
                measure(trial, layout, repositories, documents, routed, shards, warmup, samples);
            csv.write(observation + "\n");
            csv.flush();
            System.out.println("Search routing: " + observation);
          } finally {
            try (var paths = Files.walk(trial)) {
              for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
              }
            }
          }
        }
      }
    }
  }

  private static String measure(
      Path trial,
      Layout layout,
      int repositoryCount,
      int documents,
      boolean routed,
      int shards,
      int warmup,
      int samples)
      throws Exception {
    List<String> repositories =
        IntStream.range(0, repositoryCount).mapToObj(i -> "project/repository-" + i).toList();
    Path indexRoot = trial.resolve("lucene");
    String indexName = routed ? RepositoryRoutingMappingConfigurer.INDEX_NAME : "GitCommitIndex";
    int total = documents + SMALL_BURST + LARGE_BURST;
    long indexing;
    long smallBurst;
    long largeBurst;
    long peakHeap;
    QueryTimings queries;
    try (HeapSampler heap = new HeapSampler();
        var provider = provider(trial, "create", routed, shards)) {
      var factory = provider.getSessionFactory();
      long start = System.nanoTime();
      append(factory, layout, repositories, 0, documents);
      indexing = System.nanoTime() - start;
      start = System.nanoTime();
      append(factory, layout, repositories, documents, SMALL_BURST);
      smallBurst = System.nanoTime() - start;
      assertEquals(documents + SMALL_BURST, count(factory, null));
      start = System.nanoTime();
      append(factory, layout, repositories, documents + SMALL_BURST, LARGE_BURST);
      largeBurst = System.nanoTime() - start;
      assertEquals(total, count(factory, null));
      queries = measureQueries(factory, layout, repositories, routed, warmup, samples);
      peakHeap = heap.peak();
    }

    var footprint = footprint(indexRoot.resolve(indexName), shards);
    assertEquals(total, footprint.documentsPerShard().stream().mapToInt(Integer::intValue).sum());
    assertEquals(repositoryCount, footprint.repositories());
    long h2Bytes = Files.size(trial.resolve("projection.mv.db"));
    String target = repositories.getFirst();
    long restart;
    long purged = 0;
    long purge = 0;
    long rebuild;
    long start = System.nanoTime();
    try (var provider = provider(trial, "validate", routed, shards)) {
      var factory = provider.getSessionFactory();
      var search = new GitHistorySearchService(factory);
      assertFalse(search.searchCommitTextSummaries(target, "workflow", 10).isEmpty());
      restart = System.nanoTime() - start;
      assertEquals(total, count(factory, null));
      if (routed) {
        purged = count(factory, target);
        assertTrue(purged > 0);
        try (Session session = factory.openSession()) {
          var workspace = Search.session(session).workspace(GitCommitIndex.class);
          start = System.nanoTime();
          workspace.purge(Set.of(target));
          workspace.refresh();
          purge = System.nanoTime() - start;
        }
        assertEquals(0, count(factory, target));
        assertEquals(total - purged, count(factory, null));
      }
      start = System.nanoTime();
      Search.mapping(factory)
          .scope(GitCommitIndex.class)
          .massIndexer()
          .threadsToLoadObjects(2)
          .batchSizeToLoadObjects(BATCH)
          .idFetchSize(500)
          .failFast(true)
          .startAndWait();
      rebuild = System.nanoTime() - start;
      assertEquals(total, count(factory, null));
      if (routed) {
        assertEquals(purged, count(factory, target));
      }
    }

    StringJoiner result = new StringJoiner(",");
    result
        .add(layout.name().toLowerCase(Locale.ROOT))
        .add(Integer.toString(repositoryCount))
        .add(Integer.toString(documents))
        .add(routed ? "routed" : "baseline")
        .add(Integer.toString(shards))
        .add(Long.toString(footprint.occupiedShards()))
        .add(String.join(";", footprint.documentsPerShard().stream().map(String::valueOf).toList()))
        .add(Long.toString(footprint.files()))
        .add(Long.toString(footprint.segments()))
        .add(Long.toString(footprint.bytes()))
        .add(Long.toString(h2Bytes))
        .add(Long.toString(peakHeap))
        .add(milliseconds(indexing))
        .add(decimal(documents * 1_000_000_000.0 / indexing))
        .add(milliseconds(smallBurst))
        .add(milliseconds(largeBurst));
    queries.append(result, routed);
    result
        .add(milliseconds(restart))
        .add(routed ? Long.toString(purged) : "")
        .add(routed ? milliseconds(purge) : "")
        .add(milliseconds(rebuild));
    return result.toString();
  }

  private static QueryTimings measureQueries(
      SessionFactory factory,
      Layout layout,
      List<String> repositories,
      boolean routed,
      int warmup,
      int samples) {
    var timings =
        new QueryTimings(
            new long[samples],
            new long[samples],
            new long[samples],
            new long[samples],
            new long[samples]);
    try (Session session = factory.openSession()) {
      for (int i = -warmup; i < samples; i++) {
        String repository =
            layout == Layout.SKEWED && i % 2 == 0
                ? repositories.getFirst()
                : repositories.get(Math.floorMod(i, repositories.size()));
        for (boolean path : new boolean[] {false, true}) {
          if (!routed) {
            var hit = timeQuery(session, repository, path, false);
            if (i >= 0) {
              timings.set(i, path, 0L, hit.nanoseconds());
            }
          } else {
            boolean routedFirst = (i % 2 == 0) ^ path;
            var first = timeQuery(session, repository, path, routedFirst);
            var second = timeQuery(session, repository, path, !routedFirst);
            assertEquals(
                first.hits(), second.hits(), "routing changed the result set for " + repository);
            if (i >= 0) {
              timings.set(
                  i,
                  path,
                  routedFirst ? first.nanoseconds() : second.nanoseconds(),
                  routedFirst ? second.nanoseconds() : first.nanoseconds());
            }
          }
        }
        var admin = timeQuery(session, null, false, false);
        if (i >= 0) {
          timings.adminText()[i] = admin.nanoseconds();
        }
      }
    }
    return timings;
  }

  private static TimedHits timeQuery(
      Session session, String repository, boolean path, boolean routed) {
    long start = System.nanoTime();
    var query =
        Search.session(session)
            .search(GitCommitIndex.class)
            .select(f -> f.field("objectId", String.class))
            .where(
                f -> {
                  var predicate = f.bool();
                  if (repository != null) {
                    predicate.filter(f.match().field("repositoryName").matching(repository));
                  }
                  if (path) {
                    predicate.filter(
                        f.match()
                            .field(GitCommitIndex.CHANGED_PATH_EXACT_FIELD)
                            .matching(COMMON_PATH));
                  } else {
                    predicate.must(
                        f.match().fields("shortMessage", "changedText").matching("workflow"));
                  }
                  return predicate;
                })
            .sort(f -> f.field("committerTime").desc().then().field("objectId"));
    if (routed) {
      query.routing(repository);
    }
    List<String> hits = query.fetchHits(20);
    long elapsed = System.nanoTime() - start;
    assertFalse(hits.isEmpty());
    return new TimedHits(hits, elapsed);
  }

  private static long count(SessionFactory factory, String repository) {
    try (Session session = factory.openSession()) {
      var query = Search.session(session).search(GitCommitIndex.class).where(f -> f.matchAll());
      if (repository != null) {
        query.routing(repository);
      }
      return query.fetchTotalHitCount();
    }
  }

  private static void append(
      SessionFactory factory, Layout layout, List<String> repositories, int first, int count) {
    for (int offset = 0; offset < count; offset += BATCH) {
      try (Session session = factory.openSession()) {
        session.setJdbcBatchSize(BATCH);
        session.beginTransaction();
        for (int i = first + offset; i < first + Math.min(count, offset + BATCH); i++) {
          int repository =
              switch (layout) {
                case SINGLE -> 0;
                case UNIFORM -> i % repositories.size();
                case SKEWED -> i % 5 == 0 ? 1 + (i / 5) % (repositories.size() - 1) : 0;
              };
          var row = new GitCommitIndex();
          String key = Integer.toHexString(i);
          row.setProjectionKey(key);
          row.setObjectId("0".repeat(40 - key.length()) + key);
          row.setRepositoryName(repositories.get(repository));
          row.setShortMessage("Update workflow module " + (i % 64));
          row.setFullMessage("Update workflow module " + (i % 64) + " after review and validation");
          row.setAuthorName("Synthetic author " + (i % 32));
          row.setAuthorEmail("author-" + (i % 32) + "@example.invalid");
          row.setCommitterName("Synthetic committer " + (i % 8));
          row.setCommitterEmail("committer-" + (i % 8) + "@example.invalid");
          row.setAuthorTime(Instant.ofEpochSecond(i));
          row.setCommitterTime(Instant.ofEpochSecond(i));
          row.setChangedPaths(COMMON_PATH + "\nmodules/module-" + (i % 64) + "/WorkflowTest.java");
          row.setChangedText(
              "class Workflow { private int revision = "
                  + i
                  + "; } "
                  + "the workflow handles validation for synthetic module "
                  + (i % 64));
          session.persist(row);
        }
        session.getTransaction().commit();
      }
    }
  }

  private static Footprint footprint(Path index, int shards) throws IOException {
    List<Path> directories;
    if (shards == 1) {
      directories = List.of(index);
    } else {
      try (var paths = Files.list(index)) {
        directories = paths.filter(Files::isDirectory).sorted().toList();
      }
    }
    assertEquals(shards, directories.size());
    long bytes = 0;
    long files = 0;
    long segments = 0;
    var documents = new ArrayList<Integer>(shards);
    Set<String> repositories = new HashSet<>();
    for (Path directory : directories) {
      try (var paths = Files.list(directory)) {
        for (Path file : paths.filter(Files::isRegularFile).toList()) {
          files++;
          bytes += Files.size(file);
        }
      }
      try (var fs = FSDirectory.open(directory)) {
        if (!DirectoryReader.indexExists(fs)) {
          documents.add(0);
          continue;
        }
        try (var reader = DirectoryReader.open(fs)) {
          documents.add(reader.numDocs());
          segments += reader.leaves().size();
          var terms = MultiTerms.getTerms(reader, "repositoryName");
          if (terms != null) {
            var iterator = terms.iterator();
            for (var term = iterator.next(); term != null; term = iterator.next()) {
              assertTrue(
                  repositories.add(term.utf8ToString()), "repository was split across shards");
            }
          }
        }
      }
    }
    return new Footprint(bytes, files, segments, List.copyOf(documents), repositories.size());
  }

  private static HibernateSessionFactoryProvider provider(
      Path trial, String schema, boolean routed, int shards) {
    Properties settings = new Properties();
    settings.setProperty(
        "hibernate.connection.url",
        "jdbc:h2:file:" + trial.resolve("projection") + ";CACHE_SIZE=32768");
    settings.setProperty("hibernate.connection.driver_class", "org.h2.Driver");
    settings.setProperty("hibernate.hbm2ddl.auto", schema);
    settings.setProperty("hibernate.search.backend.type", "lucene");
    settings.setProperty("hibernate.search.backend.directory.type", "local-filesystem");
    settings.setProperty(
        "hibernate.search.backend.directory.root", trial.resolve("lucene").toString());
    settings.setProperty("hibernate.search.indexing.plan.synchronization.strategy", "sync");
    if (routed) {
      settings.setProperty(
          "hibernate.search.mapping.configurer",
          "class:" + RepositoryRoutingMappingConfigurer.class.getName());
      if (shards > 1) {
        String prefix =
            "hibernate.search.backend.indexes."
                + RepositoryRoutingMappingConfigurer.INDEX_NAME
                + ".sharding.";
        settings.setProperty(prefix + "strategy", "hash");
        settings.setProperty(prefix + "number_of_shards", Integer.toString(shards));
      }
    }
    return new HibernateSessionFactoryProvider(settings, SearchEntities.annotatedClasses());
  }

  private static void writeEnvironment(Path path, int warmup, int samples) throws IOException {
    var properties = new Properties();
    properties.setProperty("java", System.getProperty("java.runtime.version"));
    properties.setProperty(
        "os", System.getProperty("os.name") + " " + System.getProperty("os.arch"));
    properties.setProperty("lucene", Version.LATEST.toString());
    properties.setProperty("jvm.heap.max.bytes", Long.toString(Runtime.getRuntime().maxMemory()));
    properties.setProperty(
        "jvm.available.processors", Integer.toString(Runtime.getRuntime().availableProcessors()));
    properties.setProperty("database", "H2 local file");
    properties.setProperty("source", System.getProperty("search.routing.source", "local checkout"));
    properties.setProperty("synchronization", "sync");
    properties.setProperty("query.warmup", Integer.toString(warmup));
    properties.setProperty("query.samples", Integer.toString(samples));
    properties.setProperty("index.batch", Integer.toString(BATCH));
    try (var writer = Files.newBufferedWriter(path)) {
      properties.store(writer, "Search routing and physical shard measurements");
    }
  }

  private static String decimal(double value) {
    return String.format(Locale.ROOT, "%.3f", value);
  }

  private static String milliseconds(long nanoseconds) {
    return decimal(nanoseconds / 1_000_000.0);
  }

  private enum Layout {
    SINGLE,
    UNIFORM,
    SKEWED
  }

  private record Footprint(
      long bytes, long files, long segments, List<Integer> documentsPerShard, int repositories) {
    long occupiedShards() {
      return documentsPerShard.stream().filter(documents -> documents > 0).count();
    }
  }

  private record TimedHits(List<String> hits, long nanoseconds) {}

  private record QueryTimings(
      long[] textRouted,
      long[] textFanout,
      long[] pathRouted,
      long[] pathFanout,
      long[] adminText) {
    void set(int sample, boolean path, long routed, long fanout) {
      (path ? pathRouted : textRouted)[sample] = routed;
      (path ? pathFanout : textFanout)[sample] = fanout;
    }

    void append(StringJoiner result, boolean routed) {
      percentiles(result, textRouted, routed);
      percentiles(result, textFanout, true);
      percentiles(result, pathRouted, routed);
      percentiles(result, pathFanout, true);
      percentiles(result, adminText, true);
    }

    private static void percentiles(StringJoiner result, long[] samples, boolean applicable) {
      if (!applicable) {
        result.add("").add("");
        return;
      }
      Arrays.sort(samples);
      result.add(decimal(samples[(int) Math.ceil(samples.length * .5) - 1] / 1_000.0));
      result.add(decimal(samples[(int) Math.ceil(samples.length * .95) - 1] / 1_000.0));
    }
  }

  private static final class HeapSampler implements AutoCloseable {
    private final AtomicLong peak = new AtomicLong();
    private final ScheduledExecutorService sampler =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("search-routing-heap-sampler").daemon().factory());

    HeapSampler() {
      sampler.scheduleAtFixedRate(this::peak, 0, 20, TimeUnit.MILLISECONDS);
    }

    long peak() {
      long used = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
      return peak.accumulateAndGet(used, Math::max);
    }

    @Override
    public void close() {
      sampler.shutdownNow();
    }
  }
}
