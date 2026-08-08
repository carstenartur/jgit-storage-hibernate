/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.search.SearchEntities;
import io.github.carstenartur.jgit.storage.hibernate.search.profile.SearchIndexingProfile;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitIndexer;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitSearchHit;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.TreeFormatter;
import org.hibernate.stat.Statistics;
import org.openjdk.jmh.annotations.AuxCounters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;

/**
 * Measures Hibernate Search runtime knobs independently from semantic indexing profiles.
 *
 * <p>The benchmark always uses {@code content-v1}. Each scenario changes one controlled family of
 * settings: synchronization/refresh, Lucene writer RAM/backend threads, or projection transaction
 * size. This keeps results attributable instead of creating an opaque five-dimensional Cartesian
 * product.
 *
 * <p>Three workloads are retained:
 *
 * <ul>
 *   <li>burst submission latency, with eventual visibility validated outside measured time;
 *   <li>burst ready latency, including the time until all new documents are searchable;
 *   <li>query latency distribution while the burst is being indexed;
 *   <li>empty-to-complete rebuild/import latency until the new index is searchable.
 * </ul>
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 5)
@Fork(1)
@Threads(1)
@State(Scope.Thread)
public class HibernateSearchRuntimeTuningBenchmark {

  private static final AtomicInteger INVOCATION = new AtomicInteger();
  private static final Duration VISIBILITY_TIMEOUT = Duration.ofSeconds(15);
  private static final String STEADY_NEEDLE = "steadyneedle";
  private static final String BURST_NEEDLE = "burstneedle";
  private static final String PATH = "services/payments/fraud/runtime.txt";
  private static final int CONTENT_BYTES = 4 * 1024;

  @Param({"100"})
  public int commitCount;

  @Param({"50"})
  public int burstCount;

  @Param({"reference"})
  public String runtimeScenario;

  private SearchRuntimeScenario scenario;
  private HibernateSessionFactoryProvider provider;
  private HibernateRepository repository;
  private GitHistorySearchService search;
  private Statistics statistics;
  private Path indexRoot;
  private String repositoryName;
  private List<ObjectId> commits;
  private ObjectId head;
  private String operation;
  private boolean awaitBurstInTearDown;

  @Setup(Level.Invocation)
  public void setupInvocation(BenchmarkParams params) throws Exception {
    if (burstCount <= 0 || burstCount >= commitCount) {
      throw new IllegalArgumentException("burstCount must be between 1 and commitCount - 1");
    }
    RuntimeBenchmarkFailureHandler.reset();
    scenario = SearchRuntimeScenario.fromId(runtimeScenario);
    operation = params.getBenchmark().substring(params.getBenchmark().lastIndexOf('.') + 1);
    repositoryName =
        "runtime-"
            + operation
            + "-"
            + scenario.id().replace('-', '_')
            + "-"
            + INVOCATION.incrementAndGet();
    indexRoot = Files.createTempDirectory("jgit-storage-search-runtime-");
    provider = new HibernateSessionFactoryProvider(properties(), SearchEntities.annotatedClasses());
    repository = HibernateRepository.create(provider.getSessionFactory(), repositoryName);
    repository.create(true);
    commits = createHistory();
    head = commits.getLast();
    search = new GitHistorySearchService(provider.getSessionFactory());

    if (!"projectionRebuildReady".equals(operation)) {
      int existingCount = commitCount - burstCount;
      ObjectId existingHead = commits.get(existingCount - 1);
      int indexed =
          new CommitIndexer(provider.getSessionFactory(), repositoryName)
              .indexCommitsFrom(repository, existingHead, -1);
      if (indexed != existingCount) {
        throw new IllegalStateException(
            "Expected " + existingCount + " preindexed commits but indexed " + indexed);
      }
      awaitVisible(STEADY_NEEDLE, existingCount, null);
    }
    statistics = provider.getSessionFactory().getStatistics();
    statistics.clear();
    awaitBurstInTearDown = false;
  }

  @TearDown(Level.Invocation)
  public void tearDownInvocation() throws Exception {
    try {
      if (awaitBurstInTearDown && search != null) {
        awaitVisible(BURST_NEEDLE, burstCount, null);
      }
      RuntimeBenchmarkFailureHandler.assertNoFailure();
    } finally {
      if (repository != null) {
        repository.close();
        repository = null;
      }
      if (provider != null) {
        provider.close();
        provider = null;
      }
      deleteRecursively(indexRoot);
      indexRoot = null;
      search = null;
      statistics = null;
      commits = null;
      head = null;
    }
  }

  /** Application-thread cost of submitting a burst; visibility is verified in unmeasured teardown. */
  @Benchmark
  public int incrementalBurstSubmission(RuntimeCounters counters) throws Exception {
    int indexed = indexBurst();
    awaitBurstInTearDown = true;
    counters.captureDatabase(statistics, indexed);
    return indexed;
  }

  /** End-to-end burst latency through the point at which every new commit is searchable. */
  @Benchmark
  public int incrementalBurstReady(RuntimeCounters counters) throws Exception {
    int indexed = indexBurst();
    Visibility visibility = awaitVisible(BURST_NEEDLE, burstCount, counters);
    counters.captureDatabase(statistics, indexed);
    counters.visibilityPolls = visibility.polls();
    counters.visibilityWaitMicros = visibility.waitMicros();
    RuntimeBenchmarkFailureHandler.assertNoFailure();
    return indexed;
  }

  /** Query latency distribution while the second half of the history is being indexed. */
  @Benchmark
  public int steadyQueriesDuringBurst(RuntimeCounters counters) throws Exception {
    List<Long> queryMicros = new ArrayList<>();
    try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
      Future<Integer> writer = executor.submit(this::indexBurstUnchecked);
      int checksum = 0;
      while (!writer.isDone() || queryMicros.size() < 5) {
        long started = System.nanoTime();
        List<CommitSearchHit> hits = search.searchCommitTextSummaries(repositoryName, STEADY_NEEDLE, 10);
        long elapsedMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started);
        queryMicros.add(Math.max(1L, elapsedMicros));
        if (hits.isEmpty()) {
          throw new IllegalStateException("Stable preindexed history disappeared during indexing");
        }
        checksum = 31 * checksum + hits.getFirst().objectId().hashCode();
      }
      int indexed = get(writer);
      if (indexed != burstCount) {
        throw new IllegalStateException(
            "Expected burst of " + burstCount + " commits but indexed " + indexed);
      }
      counters.captureDatabase(statistics, indexed);
      counters.captureQueryDistribution(queryMicros);
      Visibility visibility = awaitVisible(BURST_NEEDLE, burstCount, counters);
      counters.visibilityPolls = visibility.polls();
      counters.visibilityWaitMicros = visibility.waitMicros();
      RuntimeBenchmarkFailureHandler.assertNoFailure();
      return checksum ^ indexed;
    }
  }

  /** Empty-to-complete import/rebuild latency until the rebuilt projection is searchable. */
  @Benchmark
  public int projectionRebuildReady(RuntimeCounters counters) throws Exception {
    var result =
        new CommitProjectionRebuilder(provider.getSessionFactory())
            .rebuild(repository, new RepositoryName(repositoryName));
    if (result.indexedCommits() != commitCount) {
      throw new IllegalStateException(
          "Expected rebuild of " + commitCount + " commits but indexed " + result.indexedCommits());
    }
    Visibility visibility = awaitVisible(BURST_NEEDLE, burstCount, counters);
    counters.captureDatabase(statistics, result.indexedCommits());
    counters.visibilityPolls = visibility.polls();
    counters.visibilityWaitMicros = visibility.waitMicros();
    RuntimeBenchmarkFailureHandler.assertNoFailure();
    return result.indexedCommits();
  }

  private int indexBurst() throws IOException {
    int indexed =
        new CommitIndexer(provider.getSessionFactory(), repositoryName)
            .indexCommitsFrom(repository, head, burstCount);
    if (indexed != burstCount) {
      throw new IllegalStateException(
          "Expected burst of " + burstCount + " commits but indexed " + indexed);
    }
    return indexed;
  }

  private int indexBurstUnchecked() {
    try {
      return indexBurst();
    } catch (IOException exception) {
      throw new IllegalStateException("Could not index Search benchmark burst", exception);
    }
  }

  private Visibility awaitVisible(String query, int expected, RuntimeCounters counters)
      throws InterruptedException {
    long started = System.nanoTime();
    long deadline = started + VISIBILITY_TIMEOUT.toNanos();
    int polls = 0;
    while (true) {
      RuntimeBenchmarkFailureHandler.assertNoFailure();
      int visible = search.searchCommitTextSummaries(repositoryName, query, commitCount).size();
      polls++;
      if (visible == expected) {
        long waitMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started);
        if (counters != null) {
          counters.resultCount = visible;
        }
        return new Visibility(polls, Math.max(0L, waitMicros));
      }
      if (System.nanoTime() >= deadline) {
        throw new IllegalStateException(
            "Timed out waiting for "
                + expected
                + " visible Search hits for '"
                + query
                + "'; last count="
                + visible
                + ", scenario="
                + scenario.id());
      }
      Thread.sleep(5L);
    }
  }

  private List<ObjectId> createHistory() throws Exception {
    List<ObjectId> result = new ArrayList<>(commitCount);
    ObjectId parent = null;
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      for (int index = 0; index < commitCount; index++) {
        String marker = index < commitCount - burstCount ? STEADY_NEEDLE : BURST_NEEDLE;
        byte[] content = deterministicContent(index, marker);
        ObjectId blob = inserter.insert(Constants.OBJ_BLOB, content);
        TreeFormatter tree = new TreeFormatter();
        tree.append(PATH, FileMode.REGULAR_FILE, blob);

        CommitBuilder commit = new CommitBuilder();
        commit.setTreeId(inserter.insert(tree));
        if (parent != null) {
          commit.setParentId(parent);
        }
        PersonIdent identity =
            new PersonIdent(
                "Runtime benchmark",
                "runtime-benchmark@example.invalid",
                Instant.ofEpochSecond(1_710_000_000L + index),
                ZoneOffset.UTC);
        commit.setAuthor(identity);
        commit.setCommitter(identity);
        commit.setMessage(marker + " runtime commit " + index);
        parent = inserter.insert(commit);
        result.add(parent);
      }
      inserter.flush();
    }

    RefUpdate update = repository.updateRef("refs/heads/main");
    update.setExpectedOldObjectId(ObjectId.zeroId());
    update.setNewObjectId(parent);
    update.disableRefLog();
    if (update.update() != RefUpdate.Result.NEW) {
      throw new IllegalStateException("Could not publish Search runtime benchmark history");
    }
    return List.copyOf(result);
  }

  private static byte[] deterministicContent(int index, String marker) {
    StringBuilder content = new StringBuilder(CONTENT_BYTES + 128);
    content.append(marker).append(" revision ").append(index).append('\n');
    int value = index ^ 0x52554E54;
    while (content.length() < CONTENT_BYTES) {
      value = value * 1664525 + 1013904223;
      content.append((char) ('a' + Math.floorMod(value, 26)));
      if ((content.length() & 63) == 0) {
        content.append('\n');
      }
    }
    content.setLength(CONTENT_BYTES);
    return content.toString().getBytes(StandardCharsets.UTF_8);
  }

  private Properties properties() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        requiredSystemProperty(HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY));
    properties.put(
        "hibernate.connection.username",
        requiredSystemProperty(HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY));
    properties.put(
        "hibernate.connection.password",
        requiredSystemProperty(HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY));
    properties.put("hibernate.connection.driver_class", "org.postgresql.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.format_sql", "false");
    properties.put("hibernate.generate_statistics", "true");
    properties.put("hibernate.order_inserts", "true");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-filesystem");
    properties.put("hibernate.search.backend.directory.root", indexRoot.toString());
    properties.put(SearchIndexingProfile.PROFILE_PROPERTY, SearchIndexingProfile.CONTENT.id());
    properties.put(
        "hibernate.search.background_failure_handler",
        "class:" + RuntimeBenchmarkFailureHandler.class.getName());
    scenario.apply(properties);
    return properties;
  }

  private static int get(Future<Integer> future)
      throws InterruptedException, ExecutionException, TimeoutException {
    return future.get(15, TimeUnit.SECONDS);
  }

  private static String requiredSystemProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing Search runtime benchmark system property " + name);
    }
    return value;
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  private static long percentile(List<Long> values, double percentile) {
    if (values.isEmpty()) {
      return 0L;
    }
    List<Long> sorted = values.stream().sorted().toList();
    int rank = (int) Math.ceil(percentile * sorted.size()) - 1;
    return sorted.get(Math.max(0, Math.min(rank, sorted.size() - 1)));
  }

  private record Visibility(int polls, long waitMicros) {}

  /** Per-invocation ORM, visibility and concurrent-query evidence. */
  @AuxCounters(AuxCounters.Type.EVENTS)
  @State(Scope.Thread)
  public static class RuntimeCounters {
    public long resultCount;
    public long indexedCommits;
    public long preparedStatements;
    public long transactions;
    public long queryExecutions;
    public long visibilityPolls;
    public long visibilityWaitMicros;
    public long concurrentQueries;
    public long queryP50Micros;
    public long queryP95Micros;
    public long queryP99Micros;

    @Setup(Level.Invocation)
    public void reset() {
      resultCount = 0L;
      indexedCommits = 0L;
      preparedStatements = 0L;
      transactions = 0L;
      queryExecutions = 0L;
      visibilityPolls = 0L;
      visibilityWaitMicros = 0L;
      concurrentQueries = 0L;
      queryP50Micros = 0L;
      queryP95Micros = 0L;
      queryP99Micros = 0L;
    }

    private void captureDatabase(Statistics statistics, long indexedCommits) {
      this.indexedCommits = indexedCommits;
      preparedStatements = statistics.getPrepareStatementCount();
      transactions = statistics.getTransactionCount();
      queryExecutions = statistics.getQueryExecutionCount();
    }

    private void captureQueryDistribution(List<Long> queryMicros) {
      concurrentQueries = queryMicros.size();
      queryP50Micros = percentile(queryMicros, 0.50);
      queryP95Micros = percentile(queryMicros, 0.95);
      queryP99Micros = percentile(queryMicros, 0.99);
    }
  }
}
