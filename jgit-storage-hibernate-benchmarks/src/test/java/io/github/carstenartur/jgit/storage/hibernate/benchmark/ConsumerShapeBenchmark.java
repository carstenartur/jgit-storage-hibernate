/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateStorageSettings;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaAnalysisConfiguration;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaJdtAnalyzer;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaProjectAnalyzer;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaProjectSnapshot;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.JavaSourceSnapshot;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.search.SearchEntities;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitHistoryQuery;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitIndexer;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitSearchHit;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageByteMetrics;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.hibernate.SessionFactory;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;

@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
public class ConsumerShapeBenchmark {
  private static final int SEED = 17;
  private static final int QUERY_LIMIT = 20;
  private static final JavaAnalysisConfiguration BINDINGS =
      JavaAnalysisConfiguration.java21BindingAware();

  private static final JavaAnalysisConfiguration PROJECT_BINDINGS =
      new JavaAnalysisConfiguration(
          BINDINGS.sourceLevel(),
          BINDINGS.bindingMode(),
          BINDINGS.classpathEntries(),
          List.of("src/main/java"),
          BINDINGS.encodings(),
          BINDINGS.modulepathEntries(),
          BINDINGS.includeRunningVmBootClasspath(),
          BINDINGS.analyzerVersion());

  @Benchmark
  public String audioPublish(Audio fixture, Counters counters) throws IOException {
    var publication =
        ConsumerBenchmarkFixtures.publishAudio(
            fixture.repository, SEED, ++fixture.publication, fixture.coordinate);
    counters.payloadBytes = fixture.coordinate;
    counters.captureAudio(fixture);
    return publication.metadataCommit().name();
  }

  @Benchmark
  public String audioReadRecent(Audio fixture, Counters counters) throws IOException {
    var repository = fixture.repository;
    if (!fixture.recent.metadataCommit().equals(repository.resolve("refs/heads/main"))
        || !fixture.recent.artifactCommit().equals(repository.resolve("refs/heads/artifacts"))) {
      throw new IllegalStateException("Recent audio refs did not resolve to the published commits");
    }
    try (var input = repository.open(fixture.recent.payload()).openStream();
        var formatter = new ObjectInserter.Formatter()) {
      ObjectId actual = formatter.idFor(Constants.OBJ_BLOB, input.getSize(), input);
      if (!actual.equals(fixture.recent.payload()) || input.getSize() != fixture.coordinate) {
        throw new IllegalStateException("Recent audio payload differs from its Git identity");
      }
      counters.payloadBytes = input.getSize();
      counters.captureAudio(fixture);
      return actual.name();
    }
  }

  @Benchmark
  public int taxonomyExactPathSummary(Taxonomy fixture, Counters counters) throws IOException {
    int hits = 0;
    for (Repository repository : fixture.repositories) {
      var query = taxonomyPathQuery(repository, fixture.coordinate, true, QUERY_LIMIT);
      hits +=
          verify(
              repository,
              fixture.search.findChangeSummaries(query).stream()
                  .map(CommitSearchHit::objectId)
                  .toList());
    }
    counters.resultCount = hits;
    counters.capture(fixture.provider);
    return hits;
  }

  @Benchmark
  public int taxonomySqlPathSummary(Taxonomy fixture, Counters counters) throws IOException {
    int hits = 0;
    for (Repository repository : fixture.repositories) {
      hits += verify(
          repository,
          fixture.search.findChangeSummaries(
                  taxonomyPathQuery(repository, fixture.coordinate, false, QUERY_LIMIT)).stream()
              .map(CommitSearchHit::objectId).toList());
    }
    counters.resultCount = hits;
    counters.capture(fixture.provider);
    return hits;
  }

  private static CommitHistoryQuery taxonomyPathQuery(
      Repository repository, int coordinate, boolean indexed, int limit) {
    var builder = CommitHistoryQuery.forRepository(repository.getIdentifier())
        .authoredBy("workspace-author-0@example.invalid")
        .authoredBetween(
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z").plusSeconds(coordinate))
        .limit(limit);
    return (indexed
        ? builder.touchingExactPath("requirements/accessibility.json")
        : builder.touchingPath("requirements/accessibility.json")).build();
  }

  @Benchmark
  public int taxonomyTextSummary(Taxonomy fixture, Counters counters) throws IOException {
    int hits = 0;
    for (Repository repository : fixture.repositories) {
      hits +=
          verify(
              repository,
              fixture
                  .search
                  .searchCommitTextSummaries(repository.getIdentifier(), "terminology", QUERY_LIMIT)
                  .stream()
                  .map(CommitSearchHit::objectId)
                  .toList());
    }
    counters.resultCount = hits;
    counters.capture(fixture.provider);
    return hits;
  }

  @Benchmark
  public int taxonomyTextEntity(Taxonomy fixture, Counters counters) throws IOException {
    int hits = 0;
    for (Repository repository : fixture.repositories) {
      hits +=
          verify(
              repository,
              fixture
                  .search
                  .searchCommitText(repository.getIdentifier(), "terminology", QUERY_LIMIT)
                  .stream()
                  .map(hit -> hit.getObjectId())
                  .toList());
    }
    counters.resultCount = hits;
    counters.capture(fixture.provider);
    return hits;
  }

  @Benchmark
  public int taxonomyProjectionRebuild(Taxonomy fixture, Counters counters) throws IOException {
    if (fixture.indexRoot == null) {
      throw new IllegalStateException("Rebuild is enabled only for the PostgreSQL profile");
    }
    var rebuilder = new CommitProjectionRebuilder(fixture.provider.getSessionFactory());
    int rebuilt = 0;
    for (Repository repository : fixture.repositories) {
      String name = repository.getIdentifier();
      var result = rebuilder.rebuild(repository, new RepositoryName(name));
      int expected = fixture.coordinate / fixture.repositories.size();
      if (result.state() != CommitProjectionRebuilder.RebuildState.COMPLETED
          || result.removedProjections() != expected
          || result.indexedCommits() != expected) {
        throw new IllegalStateException("Taxonomy rebuild did not replace every projection");
      }
      verify(
          repository,
          fixture.search.searchCommitTextSummaries(name, "terminology", QUERY_LIMIT).stream()
              .map(CommitSearchHit::objectId)
              .toList());
      rebuilt += result.indexedCommits();
    }
    counters.resultCount = rebuilt;
    counters.capture(fixture.provider);
    return rebuilt;
  }

  @Benchmark
  public int sandboxAnalyzeBodyEdit(Sandbox fixture, Counters counters) {
    var result = new JavaJdtAnalyzer().analyze(fixture.changed, BINDINGS);
    if (result.analysisRun().getErrorCount() != 0 || result.symbols().isEmpty()) {
      throw new IllegalStateException("Edited synthetic Java source did not resolve");
    }
    counters.javaFilesAnalyzed = 1;
    counters.resultCount = result.symbols().size();
    return result.symbols().size();
  }

  @Benchmark
  public int sandboxRebuildSnapshot(Sandbox fixture, Counters counters) {
    var result =
        new JavaProjectAnalyzer()
            .analyze(
                new JavaProjectSnapshot("sandbox", fixture.head.name(), fixture.sources),
                PROJECT_BINDINGS);
    if (result.errorCount() != 0 || result.symbols().isEmpty()) {
      throw new IllegalStateException("Synthetic Java snapshot did not resolve");
    }
    counters.javaFilesAnalyzed = fixture.sources.size();
    counters.resultCount = result.symbols().size();
    return result.symbols().size();
  }

  @State(Scope.Thread)
  public static class Audio {
    @Param({"262144", "2097152"})
    public int coordinate;

    @Param({"auto"})
    public String writer;

    @Param({"idle"})
    public String pressure;

    @Param({"16"})
    public int chunkBatch;

    private HibernateSessionFactoryProvider provider;
    private HibernateRepository repository;
    private ConsumerBenchmarkFixtures.AudioPublication recent;
    private AudioPressure background;
    private StorageByteMetrics before;
    private int publication;

    @Setup(Level.Trial)
    public void setup() throws IOException, InterruptedException {
      var properties = settings("audio", false);
      properties.setProperty(HibernateStorageSettings.PACK_CHUNK_WRITER, writer);
      properties.setProperty(
          HibernateStorageSettings.PACK_CHUNK_BATCH_SIZE, Integer.toString(chunkBatch));
      properties.setProperty(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
      properties.setProperty(
          "hibernate.session.events.auto", JdbcBatchMetricsSessionEventListener.class.getName());
      provider = new HibernateSessionFactoryProvider(properties);
      try {
        repository = create(provider, "audio");
        recent = ConsumerBenchmarkFixtures.publishAudio(repository, SEED, publication, coordinate);
        if ("bounded".equals(pressure)) {
          background = new AudioPressure();
        } else if (!"idle".equals(pressure)) {
          throw new IllegalArgumentException("Expected idle or bounded audio pressure");
        }
      } catch (IOException | InterruptedException | RuntimeException failure) {
        try {
          close();
        } catch (InterruptedException | RuntimeException cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }

    @Setup(Level.Iteration)
    public void clear() {
      provider.getSessionFactory().getStatistics().clear();
      JdbcBatchMetricsSessionEventListener.resetCurrentThread();
      before = repository.getStorageByteMetrics();
    }

    @TearDown(Level.Trial)
    public void close() throws InterruptedException {
      try (var factory = provider;
          var repo = repository;
          var analysis = background) {
        // Stop the background workload before closing the repository.
      }
    }
  }

  /** Fixed working set touched repeatedly by one CPU worker. */
  private static final class AudioPressure implements AutoCloseable {
    private static final int BYTES = 32 * 1024 * 1024;
    private final Thread worker;

    AudioPressure() throws InterruptedException {
      var active = new CountDownLatch(1);
      long[] workingSet = new long[BYTES / Long.BYTES];
      worker =
          Thread.ofPlatform()
              .daemon()
              .name("synthetic-audio-analysis")
              .start(
                  () -> {
                    long state = SEED;
                    while (!Thread.currentThread().isInterrupted()) {
                      for (int i = 0; i < workingSet.length; i += 8) {
                        state = Long.rotateLeft(state + workingSet[i], 13) ^ i;
                        workingSet[i] = state;
                      }
                      active.countDown();
                    }
                  });
      try {
        if (!active.await(5, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Synthetic analysis worker did not start");
        }
      } catch (InterruptedException | RuntimeException failure) {
        worker.interrupt();
        throw failure;
      }
    }

    @Override
    public void close() throws InterruptedException {
      worker.interrupt();
      worker.join(5000);
      if (worker.isAlive()) {
        throw new IllegalStateException("Synthetic analysis worker did not stop");
      }
    }
  }

  @State(Scope.Thread)
  public static class Taxonomy {
    @Param({"100"})
    public int coordinate;

    @Param({"embedded"})
    public String deployment = "embedded";

    @Param({"warm"})
    public String cacheState = "warm";

    @Param({"50"})
    public int indexBatch = 50;

    @Param({"250"})
    public int purgeBatch = 250;

    private HibernateSessionFactoryProvider provider;
    private GitHistorySearchService search;
    private Path indexRoot;
    private Properties persistentSettings;
    private String schema;
    private String measurementUrl;
    private boolean postgres;
    private boolean closed;
    private int providerRestarts;
    private DatabaseTelemetryCollector telemetry;
    private DatabaseTelemetrySnapshot beforeTelemetry;
    private int measurement;
    boolean persistentIndex;
    private final List<Repository> repositories = new ArrayList<>();

    @Setup(Level.Trial)
    public void setup() throws IOException {
      Properties properties = settings("taxonomy", true);
      properties.setProperty(CommitIndexer.INDEX_BATCH_SIZE_PROPERTY, Integer.toString(indexBatch));
      properties.setProperty(CommitProjectionRebuilder.PURGE_BATCH_SIZE_PROPERTY, Integer.toString(purgeBatch));
      properties.setProperty("hibernate.session.events.auto", JdbcBatchMetricsSessionEventListener.class.getName());
      postgres =
          System.getProperty(BenchmarkConnectionProperties.CONNECTION_PROPERTIES_FILE_PROPERTY)
              != null;
      if (postgres || persistentIndex) {
        BenchmarkConnectionProperties.suppressConnectionMetadataLogging();
        if (postgres) {
          properties.setProperty(
              "hibernate.connection.url",
              BenchmarkConnectionProperties.requiredSystemProperty(
                  HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY));
          properties.setProperty(
              "hibernate.connection.username",
              BenchmarkConnectionProperties.requiredSystemProperty(
                  HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY));
          properties.setProperty(
              "hibernate.connection.password",
              BenchmarkConnectionProperties.requiredSystemProperty(
                  HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY));
          properties.setProperty("hibernate.connection.driver_class", "org.postgresql.Driver");
          Properties connections = new Properties();
          try (var input = Files.newInputStream(Path.of(System.getProperty(
              BenchmarkConnectionProperties.CONNECTION_PROPERTIES_FILE_PROPERTY)))) {
            connections.load(input);
          }
          measurementUrl = connections.getProperty(
              "jgit.storage.benchmark.postgresql.measurement-url",
              properties.getProperty("hibernate.connection.url"));
          schema = "consumer_taxonomy_" + UUID.randomUUID().toString().replace("-", "");
          properties.setProperty("hibernate.default_schema", schema);
        } else {
          properties.setProperty(
              "hibernate.connection.url", "jdbc:hsqldb:mem:consumer-taxonomy-" + UUID.randomUUID());
        }
        indexRoot = Files.createTempDirectory("consumer-taxonomy-lucene-");
        properties.setProperty("hibernate.hbm2ddl.auto", "create-only");
        properties.setProperty("hibernate.search.backend.directory.type", "local-filesystem");
        properties.setProperty("hibernate.search.backend.directory.root", indexRoot.toString());
        persistentSettings = properties;
      }
      try {
        if (postgres) {
          databaseCommand("CREATE SCHEMA " + schema);
        }
        provider = new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses());
        search = new GitHistorySearchService(provider.getSessionFactory());
        int workspaces = coordinate < 1000 ? 2 : 10;
        for (int workspace = 0; workspace < workspaces; workspace++) {
          String name = "taxonomy-" + workspace;
          Repository repository = create(provider, name);
          repositories.add(repository);
          int commits = coordinate / workspaces;
          ObjectId head =
              ConsumerBenchmarkFixtures.createTaxonomyHistory(repository, workspace, commits);
          if (new CommitIndexer(provider.getSessionFactory(), name)
                  .indexCommitsFrom(repository, head, -1)
              != commits) {
            throw new IllegalStateException("Taxonomy projection omitted reachable commits");
          }
          var summaries = search.searchCommitTextSummaries(name, "terminology", QUERY_LIMIT);
          var entities = search.searchCommitText(name, "terminology", QUERY_LIMIT);
          if (!summaries.stream()
              .map(CommitSearchHit::objectId)
              .toList()
              .equals(entities.stream().map(entity -> entity.getObjectId()).toList())) {
            throw new IllegalStateException("Taxonomy query projections returned different results");
          }
          var sqlPaths = search.findChangeSummaries(taxonomyPathQuery(repository, coordinate, false, coordinate))
              .stream().map(CommitSearchHit::objectId).sorted().toList();
          var indexedPaths = search.findChangeSummaries(taxonomyPathQuery(repository, coordinate, true, coordinate))
              .stream().map(CommitSearchHit::objectId).sorted().toList();
          if (!sqlPaths.equals(indexedPaths)) {
            throw new IllegalStateException("SQL and Lucene path predicates differ on the synthetic fixture");
          }
        }
        if (postgres) {
          restart();
          DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
          if ("warm".equals(cacheState)) {
            for (Repository repository : repositories) {
              try (RevWalk walk = new RevWalk(repository)) {
                walk.markStart(walk.parseCommit(repository.resolve(Constants.HEAD)));
                for (var ignored : walk) {
                  // Warm the already constructed authoritative history outside measurement.
                }
              }
              verify(repository, textResultIds(repository.getIdentifier()));
            }
          } else if (!"reopened".equals(cacheState)) {
            throw new IllegalArgumentException("Expected warm or reopened Taxonomy cache state");
          }
          telemetry = DatabaseTelemetryCollectors.create(
              "postgresql", Boolean.getBoolean(DatabaseTelemetryCollectors.ENABLED_PROPERTY),
              properties.getProperty("hibernate.connection.url"),
              properties.getProperty("hibernate.connection.username"),
              properties.getProperty("hibernate.connection.password"));
        }
      } catch (IOException | RuntimeException failure) {
        try {
          close();
        } catch (IOException | RuntimeException cleanup) {
          failure.addSuppressed(cleanup);
        }
        throw failure;
      }
    }

    SessionFactory sessionFactory() {
      return provider.getSessionFactory();
    }

    Repository repository(int workspace) {
      return repositories.get(workspace);
    }

    Path indexDirectory() {
      return indexRoot;
    }

    void restart() throws IOException {
      if (persistentSettings == null) {
        throw new IllegalStateException("Restart requires a persistent Taxonomy fixture");
      }
      var names = repositories.stream().map(Repository::getIdentifier).toList();
      closeProvider();
      Properties reopened = new Properties();
      reopened.putAll(persistentSettings);
      reopened.setProperty("hibernate.hbm2ddl.auto", "validate");
      if (measurementUrl != null) {
        reopened.setProperty("hibernate.connection.url", measurementUrl);
      }
      provider = new HibernateSessionFactoryProvider(reopened, SearchEntities.annotatedClasses());
      search = new GitHistorySearchService(provider.getSessionFactory());
      for (String name : names) {
        repositories.add(
            HibernateRepository.openExisting(
                provider.getSessionFactory(), name, request -> {}, () -> {}));
      }
      providerRestarts++;
    }

    private void databaseCommand(String sql) throws IOException {
      try (var connection =
              DriverManager.getConnection(
                  persistentSettings.getProperty("hibernate.connection.url"),
                  persistentSettings.getProperty("hibernate.connection.username", "SA"),
                  persistentSettings.getProperty("hibernate.connection.password", ""));
          var statement = connection.createStatement()) {
        statement.execute(sql);
      } catch (SQLException failure) {
        throw new IOException("Cannot manage the isolated Taxonomy fixture database", failure);
      }
    }

    TaxonomyRecovery verifyInterruptedRebuild() throws IOException {
      if (indexRoot == null) {
        throw new IllegalStateException("Recovery requires the PostgreSQL fixture");
      }
      Repository repository = repositories.getFirst();
      String name = repository.getIdentifier();
      String other = repositories.get(1).getIdentifier();
      long expected = search.countIndexedCommits(name);
      long otherBefore = search.countIndexedCommits(other);
      var head = repository.resolve(Constants.HEAD);
      List<String> before = textResultIds(name);
      var rebuilder = new CommitProjectionRebuilder(provider.getSessionFactory());
      var progress = new AtomicReference<CommitProjectionRebuilder.RebuildState>();
      try {
        rebuilder.rebuild(
            repository,
            new RepositoryName(name),
            update -> {
              progress.set(update.state());
              if (update.state() == CommitProjectionRebuilder.RebuildState.INDEXING
                  && update.indexedCommits() == 1) {
                Thread.currentThread().interrupt();
              }
            });
        throw new IllegalStateException("Expected a rebuild interruption after the first batch");
      } catch (InterruptedIOException expectedInterrupt) {
        if (!Thread.currentThread().isInterrupted()
            || progress.get() != CommitProjectionRebuilder.RebuildState.INTERRUPTED) {
          throw new IllegalStateException(
              "Rebuild did not report an interruption", expectedInterrupt);
        }
      } finally {
        Thread.interrupted();
      }

      long partial = search.countIndexedCommits(name);
      if (partial <= 0 || partial >= expected) {
        throw new IllegalStateException("Expected a partially committed PostgreSQL projection");
      }
      restart();
      long partialAfterRestart = search.countIndexedCommits(name);
      if (partialAfterRestart != partial || search.countIndexedCommits(other) != otherBefore) {
        throw new IllegalStateException("Reopening changed the committed partial projection");
      }
      repository = repositories.getFirst();
      rebuilder = new CommitProjectionRebuilder(provider.getSessionFactory());
      var restored = rebuilder.rebuild(repository, new RepositoryName(name));
      restart();
      repository = repositories.getFirst();
      var hits = textResultIds(name);
      if (restored.state() != CommitProjectionRebuilder.RebuildState.COMPLETED
          || restored.removedProjections() != partial
          || restored.indexedCommits() != expected
          || search.countIndexedCommits(name) != expected
          || search.countIndexedCommits(other) != otherBefore
          || !head.equals(repository.resolve(Constants.HEAD))
          || !before.equals(hits)) {
        throw new IllegalStateException(
            "Recovery did not restore expected state: partial="
                + partial
                + ", removed="
                + restored.removedProjections()
                + ", restored="
                + restored.indexedCommits()
                + ", originalHits="
                + before.size()
                + ", recoveredHits="
                + hits.size());
      }
      return new TaxonomyRecovery(
          expected, partial, partialAfterRestart, restored.indexedCommits(), verify(repository, hits));
    }

    private List<String> textResultIds(String name) {
      // Compare the complete result membership, independently of Lucene score ties after purge.
      return search.searchCommitTextSummaries(name, "terminology", coordinate).stream()
          .map(CommitSearchHit::objectId)
          .sorted()
          .toList();
    }

    public void clear() {
      provider.getSessionFactory().getStatistics().clear();
      JdbcBatchMetricsSessionEventListener.resetCurrentThread();
    }

    @Setup(Level.Iteration)
    public void beginIteration(IterationParams iteration) {
      beforeTelemetry = telemetry != null && telemetry.enabled()
          && iteration.getType() == IterationType.MEASUREMENT ? telemetry.capture() : null;
      clear();
    }

    @TearDown(Level.Iteration)
    public void retainNativeEvidence(BenchmarkParams benchmark) throws IOException {
      if (beforeTelemetry == null) {
        return;
      }
      var delta = beforeTelemetry.deltaTo(telemetry.capture());
      beforeTelemetry = null;
      var gauges = new LinkedHashMap<>(delta.gauges());
      long indexBytes = 0;
      try (var files = Files.walk(indexRoot)) {
        for (Path file : files.filter(Files::isRegularFile).toList()) {
          indexBytes += Files.size(file);
        }
      }
      gauges.put("consumer.lucene.index_bytes", indexBytes);
      var observation = new DatabaseTelemetryObservation(
          Map.of("consumerShape", "Taxonomy", "coordinate", Integer.toString(coordinate),
              "operation", benchmark.getBenchmark().replaceFirst(".*\\.", ""),
              "deployment", deployment, "cacheState", cacheState,
              "indexBatch", Integer.toString(indexBatch), "purgeBatch", Integer.toString(purgeBatch),
              "measurement", Integer.toString(++measurement)),
          new DatabaseTelemetryDelta(delta.backend(), delta.enabled(), delta.startedAt(),
              delta.completedAt(), delta.serverVersion(), delta.counters(), gauges,
              delta.metadata(), delta.unsupported()));
      DatabaseTelemetryJson.appendNdjson(
          Path.of(System.getProperty(DatabaseTelemetryCollectors.OUTPUT_PROPERTY)), observation);
    }

    @TearDown(Level.Trial)
    public void close() throws IOException {
      if (closed) {
        return;
      }
      closed = true;
      try {
        if (telemetry != null) {
          telemetry.close();
        }
        closeProvider();
      } finally {
        try {
          if (persistentSettings != null) {
            databaseCommand(postgres ? "DROP SCHEMA IF EXISTS " + schema + " CASCADE" : "SHUTDOWN");
          }
        } finally {
          if (indexRoot != null && Files.exists(indexRoot)) {
            try (var paths = Files.walk(indexRoot)) {
              for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
              }
            }
          }
        }
      }
    }

    private void closeProvider() {
      try (var factory = provider) {
        repositories.forEach(Repository::close);
      } finally {
        repositories.clear();
        provider = null;
      }
    }
  }

  record TaxonomyRecovery(
      long original, long partial, long partialAfterRestart, int recovered, int verifiedHits) {}

  @State(Scope.Thread)
  public static class Sandbox {
    @Param({"10"})
    public int coordinate;

    private ObjectId head;
    private Map<String, JavaSourceSnapshot> sources;
    private JavaSourceSnapshot changed;

    @Setup(Level.Trial)
    public void setup() throws IOException {
      Path root = Files.createTempDirectory("consumer-sandbox-");
      try (Repository repository =
          new FileRepositoryBuilder().setGitDir(root.toFile()).setBare().build()) {
        repository.create(true);
        head = ConsumerBenchmarkFixtures.createSandboxHistory(repository, SEED, coordinate, 5);
        try (RevWalk walk = new RevWalk(repository)) {
          var tip = walk.parseCommit(head);
          var before = javaBlobs(repository, walk.parseCommit(tip.getParent(0)).getTree());
          var after = javaBlobs(repository, tip.getTree());
          var changes =
              after.entrySet().stream()
                  .filter(entry -> !entry.getValue().equals(before.get(entry.getKey())))
                  .toList();
          if (!before.keySet().equals(after.keySet()) || changes.size() != 1) {
            throw new IllegalStateException("Expected a single method-body edit");
          }
          sources = new LinkedHashMap<>();
          for (var entry : after.entrySet()) {
            sources.put(
                entry.getKey(),
                new JavaSourceSnapshot(
                    "sandbox",
                    head.name(),
                    entry.getValue().name(),
                    entry.getKey(),
                    new String(
                        repository.open(entry.getValue()).getCachedBytes(),
                        StandardCharsets.UTF_8)));
          }
          var edit = changes.getFirst();
          changed = sources.get(edit.getKey());
        }
      } finally {
        try (var paths = Files.walk(root)) {
          for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
            Files.delete(path);
          }
        }
      }
    }
  }

  @AuxCounters(AuxCounters.Type.EVENTS)
  @State(Scope.Thread)
  public static class Counters {
    public long payloadBytes;
    public long preparedStatements;
    public long transactions;
    public long javaFilesAnalyzed;
    public long resultCount;
    public long databaseBytesWritten;
    public long databaseBytesRead;
    public long jdbcBatches;
    public long retainedAnalysisBytes;

    private void captureAudio(Audio fixture) {
      capture(fixture.provider);
      var storage = fixture.repository.getStorageByteMetrics().minus(fixture.before);
      databaseBytesWritten = storage.databasePayloadBytesWritten();
      databaseBytesRead = storage.databasePayloadBytesRead();
      jdbcBatches = JdbcBatchMetricsSessionEventListener.snapshotCurrentThread().batchExecutions();
      retainedAnalysisBytes = fixture.background == null ? 0 : AudioPressure.BYTES;
    }

    private void capture(HibernateSessionFactoryProvider provider) {
      var statistics = provider.getSessionFactory().getStatistics();
      preparedStatements = statistics.getPrepareStatementCount();
      transactions = statistics.getTransactionCount();
      jdbcBatches = JdbcBatchMetricsSessionEventListener.snapshotCurrentThread().batchExecutions();
    }
  }

  private static int verify(Repository repository, List<String> hits) throws IOException {
    if (hits.isEmpty()) {
      throw new IllegalStateException("Expected a Taxonomy history hit");
    }
    try (var walk = new RevWalk(repository)) {
      for (String hit : hits) {
        walk.parseCommit(ObjectId.fromString(hit));
      }
    }
    return hits.size();
  }

  private static Map<String, ObjectId> javaBlobs(Repository repository, ObjectId tree)
      throws IOException {
    Map<String, ObjectId> result = new LinkedHashMap<>();
    try (var walk = new TreeWalk(repository)) {
      walk.addTree(tree);
      walk.setRecursive(true);
      while (walk.next()) {
        if (walk.getPathString().endsWith(".java")) {
          result.put(walk.getPathString(), walk.getObjectId(0));
        }
      }
    }
    return result;
  }

  private static HibernateRepository create(HibernateSessionFactoryProvider provider, String name)
      throws IOException {
    HibernateRepository repository = HibernateRepository.create(provider.getSessionFactory(), name);
    repository.create(true);
    return repository;
  }

  private static Properties settings(String shape, boolean search) {
    Properties properties = new Properties();
    properties.setProperty("hibernate.connection.url", "jdbc:hsqldb:mem:consumer-" + shape);
    properties.setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver");
    properties.setProperty("hibernate.hbm2ddl.auto", "create-drop");
    properties.setProperty("hibernate.generate_statistics", "true");
    properties.setProperty("hibernate.search.enabled", Boolean.toString(search));
    if (search) {
      properties.setProperty("hibernate.search.backend.type", "lucene");
      properties.setProperty("hibernate.search.backend.directory.type", "local-heap");
      properties.setProperty("hibernate.search.indexing.plan.synchronization.strategy", "sync");
    }
    return properties;
  }
}
