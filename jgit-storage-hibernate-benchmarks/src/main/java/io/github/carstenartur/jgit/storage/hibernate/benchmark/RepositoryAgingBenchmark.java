/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import io.github.carstenartur.jgit.storage.hibernate.PackRepackOptions;
import io.github.carstenartur.jgit.storage.hibernate.PackRepackResult;
import io.github.carstenartur.jgit.storage.hibernate.PackStorageMaintenance;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageByteMetrics;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.hibernate.Session;
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
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;

/**
 * Reproducible repository-aging fixture for lookup, clone/fetch-style traversal, refs and reopen.
 *
 * <p>Each incremental push uses a fresh inserter and ref publication, intentionally producing the
 * small-pack history whose crossover point is under investigation. The fixture closes and reopens
 * before and after optional maintenance. JMH sample-time output provides p50/p95/p99 while secondary
 * counters retain the structural condition needed to derive a policy instead of hard-coding a pack
 * count. The full scheduled profile covers 1, 10, 32, 100, 300 and 1,000 pushes with explicit cold
 * and warm JGit-cache states. A separate evidence profile can close the complete Hibernate provider,
 * reopen the existing schema with validation and then measure cold or deliberately warmed reads.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@Threads(1)
@State(Scope.Thread)
public class RepositoryAgingBenchmark {

  static final String NONE = "none";
  static final String COMPACT_ONLY = "compact-only";
  static final String READ_OPTIMIZED = "read-optimized";
  static final String COLD = "cold";
  static final String WARM = "warm";
  static final String SAME_PROVIDER = "same-provider";
  static final String RESTARTED_PROVIDER = "restarted-provider";
  static final String LOCAL_TESTCONTAINERS = "local-testcontainers";
  private static final int PAYLOAD_BYTES = 8 * 1024 + 257;
  private static final int SMALL_PACK_LIMIT_BYTES = 1024 * 1024;
  private static final int STREAM_BUFFER_BYTES = 16 * 1024;
  private static final ObjectId MISSING_OBJECT =
      ObjectId.fromString("ffffffffffffffffffffffffffffffffffffffff");

  @Param({HibernateRepositoryBenchmark.HSQLDB})
  public String backend;

  @Param({"1", "10"})
  public int pushes;

  @Param({NONE, COMPACT_ONLY, READ_OPTIMIZED})
  public String maintenanceMode;

  @Param({COLD})
  public String cacheState;

  @Param({LOCAL_TESTCONTAINERS})
  public String deployment;

  @Param({SAME_PROVIDER})
  public String providerLifecycle;

  @Param({"1"})
  public int evidenceRepeat;

  private HibernateSessionFactoryProvider provider;
  private HibernateRepository repository;
  private Statistics statistics;
  private String repositoryName;
  private ObjectId oldestBlob;
  private ObjectId newestBlob;
  private ObjectId headCommit;
  private ObjectId incrementalBoundary;
  private final List<ObjectId> commits = new ArrayList<>();
  private StorageByteMetrics bytesBeforeInvocation;
  private Inventory inventory;
  private PackRepackResult repackResult;
  private long unreachableLogicalBytes;
  private int providerRestartCount;
  private final AtomicInteger measurementIteration = new AtomicInteger();
  private DatabaseTelemetryCollector telemetryCollector;
  private DatabaseTelemetrySnapshot iterationTelemetryBefore;
  private String benchmarkMethod;

  @Setup(Level.Trial)
  public void setupTrial(BenchmarkParams benchmarkParams) throws Exception {
    setupTrial(benchmarkMethod(benchmarkParams));
  }

  public void setupTrial() throws Exception {
    setupTrial("unit-test");
  }

  private void setupTrial(String benchmarkMethod) throws Exception {
    suppressConnectionMetadataLogging();
    validateParameters();
    this.benchmarkMethod = benchmarkMethod;
    providerRestartCount = 0;
    repositoryName =
        "jmh-aging-"
            + backend
            + "-"
            + pushes
            + "-"
            + maintenanceMode
            + "-"
            + cacheState
            + "-"
            + providerLifecycle
            + "-repeat-"
            + evidenceRepeat
            + "-"
            + Long.toHexString(System.nanoTime());
    provider =
        new HibernateSessionFactoryProvider(
            properties(RESTARTED_PROVIDER.equals(providerLifecycle) ? "create" : "create-drop"));
    statistics = provider.getSessionFactory().getStatistics();
    repository = HibernateRepository.create(provider.getSessionFactory(), repositoryName);
    repository.create(true);
    telemetryCollector = databaseTelemetryCollector();
    captureSetupPhase("fixture-build", this::buildDeterministicHistory);
    closeAndReopen();
    verifyPersistedPackOrdering();
    captureSetupPhase("maintenance", this::applyMaintenance);
    closeAndReopen();
    verifyPersistedPackOrdering();
    if (RESTARTED_PROVIDER.equals(providerLifecycle)) {
      captureSetupPhase("provider-restart", this::restartProvider);
      verifyPersistedPackOrdering();
    }
    verifyReachableFixture();
    inventory = inventory();
    if (WARM.equals(cacheState)) {
      warmRepositoryCache();
    } else if (!COLD.equals(cacheState)) {
      throw new IllegalArgumentException("Unsupported cache state " + cacheState);
    }
  }

  @Setup(Level.Iteration)
  public void setupIteration(IterationParams iterationParams) {
    iterationTelemetryBefore = null;
    if (telemetryCollector.enabled()
        && iterationParams.getType() == IterationType.MEASUREMENT) {
      iterationTelemetryBefore = telemetryCollector.capture();
    }
  }

  @TearDown(Level.Iteration)
  public void tearDownIteration(IterationParams iterationParams) throws IOException {
    try {
      if (iterationTelemetryBefore != null
          && iterationParams.getType() == IterationType.MEASUREMENT) {
        DatabaseTelemetrySnapshot after = telemetryCollector.capture();
        appendTelemetry(
            "measurement",
            measurementIteration.incrementAndGet(),
            iterationTelemetryBefore.deltaTo(after));
      }
    } finally {
      iterationTelemetryBefore = null;
    }
  }

  @Setup(Level.Invocation)
  public void setupInvocation() {
    if (COLD.equals(cacheState)) {
      DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    }
    statistics.clear();
    bytesBeforeInvocation = repository.getStorageByteMetrics();
  }

  @TearDown(Level.Trial)
  public void tearDownTrial() {
    iterationTelemetryBefore = null;
    if (telemetryCollector != null) {
      telemetryCollector.close();
      telemetryCollector = null;
    }
    if (repository != null) {
      repository.close();
      repository = null;
    }
    if (provider != null) {
      provider.close();
      provider = null;
    }
    commits.clear();
  }

  /** Object lookup whose object originated in the first incremental pack. */
  @Benchmark
  public long lookupOldestObject(AgingCounters counters) throws Exception {
    long size = objectSize(oldestBlob);
    counters.capture(this);
    return size;
  }

  /** Object lookup whose object originated in the newest incremental pack. */
  @Benchmark
  public long lookupNewestObject(AgingCounters counters) throws Exception {
    long size = objectSize(newestBlob);
    counters.capture(this);
    return size;
  }

  /** Negative lookup across the aged pack catalog. */
  @Benchmark
  public boolean lookupMissingObject(AgingCounters counters) throws Exception {
    boolean present;
    try (ObjectReader reader = repository.newObjectReader()) {
      present = reader.has(MISSING_OBJECT);
    }
    if (present) {
      throw new IllegalStateException("Deterministic missing object unexpectedly exists");
    }
    counters.capture(this);
    return false;
  }

  /** Traverse every reachable commit and stream the payload selected by each tree. */
  @Benchmark
  public long cloneStyleTraversal(AgingCounters counters) throws Exception {
    long bytes = 0;
    try (RevWalk walk = new RevWalk(repository)) {
      walk.markStart(walk.parseCommit(headCommit));
      for (RevCommit commit : walk) {
        bytes += streamPayload(commit);
      }
    }
    counters.capture(this);
    return bytes;
  }

  /** Traverse only the tail after a deterministic already-known commit. */
  @Benchmark
  public int incrementalFetchTraversal(AgingCounters counters) throws Exception {
    int count = 0;
    try (RevWalk walk = new RevWalk(repository)) {
      walk.markStart(walk.parseCommit(headCommit));
      if (incrementalBoundary != null) {
        walk.markUninteresting(walk.parseCommit(incrementalBoundary));
      }
      for (RevCommit commit : walk) {
        streamPayload(commit);
        count++;
      }
    }
    counters.capture(this);
    return count;
  }

  /** Walk commit history without loading tree payloads. */
  @Benchmark
  public int revisionWalk(AgingCounters counters) throws Exception {
    int count = 0;
    try (RevWalk walk = new RevWalk(repository)) {
      walk.markStart(walk.parseCommit(headCommit));
      for (RevCommit ignored : walk) {
        count++;
      }
    }
    if (count != pushes) {
      throw new IllegalStateException(
          "Expected " + pushes + " commits in deterministic revision walk, found " + count);
    }
    counters.capture(this);
    return count;
  }

  /** Read the complete ref/Reftable view of the aged repository. */
  @Benchmark
  public int readAllRefs(AgingCounters counters) throws Exception {
    List<Ref> refs = repository.getRefDatabase().getRefsByPrefix("refs/");
    counters.capture(this);
    return refs.size();
  }

  /** Reconstruct the repository handle and verify the oldest object after catalog reload. */
  @Benchmark
  public long reopenAndLookupOldest(AgingCounters counters) throws Exception {
    closeAndReopen();
    long size = objectSize(oldestBlob);
    counters.capture(this);
    return size;
  }

  private void captureSetupPhase(String phase, CheckedAction action)
      throws Exception {
    if (!telemetryCollector.enabled()) {
      action.run();
      return;
    }
    DatabaseTelemetrySnapshot before = telemetryCollector.capture();
    action.run();
    DatabaseTelemetrySnapshot after = telemetryCollector.capture();
    appendTelemetry(phase, 0, before.deltaTo(after));
  }

  private void appendTelemetry(
      String phase, int iteration, DatabaseTelemetryDelta telemetry)
      throws IOException {
    DatabaseTelemetryJson.appendNdjson(
        requiredTelemetryOutput(),
        new DatabaseTelemetryObservation(
            telemetryCoordinate(phase, iteration), telemetry));
  }

  private DatabaseTelemetryCollector databaseTelemetryCollector() {
    boolean enabled = Boolean.getBoolean(DatabaseTelemetryCollectors.ENABLED_PROPERTY);
    String databaseBackend = databaseBackend();
    if (!enabled) {
      return DatabaseTelemetryCollectors.disabled(
          databaseBackend, "disabled-by-configuration");
    }
    return switch (databaseBackend) {
      case HibernateRepositoryBenchmark.HSQLDB ->
          DatabaseTelemetryCollectors.disabled(
              HibernateRepositoryBenchmark.HSQLDB, "unsupported-backend");
      case HibernateRepositoryBenchmark.POSTGRESQL ->
          DatabaseTelemetryCollectors.create(
              HibernateRepositoryBenchmark.POSTGRESQL,
              true,
              requiredProperty(HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY),
              requiredProperty(HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY),
              requiredProperty(
                  HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY));
      case PackStorageLayoutBenchmark.SQL_SERVER ->
          DatabaseTelemetryCollectors.create(
              PackStorageLayoutBenchmark.SQL_SERVER,
              true,
              requiredProperty(PackStorageLayoutBenchmark.SQL_SERVER_URL_PROPERTY),
              requiredProperty(PackStorageLayoutBenchmark.SQL_SERVER_USER_PROPERTY),
              requiredProperty(PackStorageLayoutBenchmark.SQL_SERVER_PASSWORD_PROPERTY));
      default ->
          throw new IllegalArgumentException(
              "Unsupported aging telemetry backend " + databaseBackend);
    };
  }

  private String databaseBackend() {
    return switch (backend) {
      case HibernateRepositoryBenchmark.HSQLDB -> HibernateRepositoryBenchmark.HSQLDB;
      case HibernateRepositoryBenchmark.POSTGRESQL,
          HibernateRepositoryBenchmark.POSTGRESQL_HIKARI ->
          HibernateRepositoryBenchmark.POSTGRESQL;
      case PackStorageLayoutBenchmark.SQL_SERVER -> PackStorageLayoutBenchmark.SQL_SERVER;
      default -> throw new IllegalArgumentException("Unsupported aging backend " + backend);
    };
  }

  private Path requiredTelemetryOutput() {
    String value = System.getProperty(DatabaseTelemetryCollectors.OUTPUT_PROPERTY);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "Missing benchmark system property "
              + DatabaseTelemetryCollectors.OUTPUT_PROPERTY);
    }
    return Path.of(value);
  }

  private Map<String, String> telemetryCoordinate(String phase, int iteration) {
    return Map.ofEntries(
        Map.entry("backend", backend),
        Map.entry("benchmarkMethod", benchmarkMethod),
        Map.entry("cacheState", cacheState),
        Map.entry("databaseBackend", databaseBackend()),
        Map.entry("deployment", deployment),
        Map.entry("evidenceRepeat", Integer.toString(evidenceRepeat)),
        Map.entry("maintenanceMode", maintenanceMode),
        Map.entry("measurementIteration", Integer.toString(iteration)),
        Map.entry("phase", phase),
        Map.entry("providerLifecycle", providerLifecycle),
        Map.entry("pushes", Integer.toString(pushes)));
  }

  private static String benchmarkMethod(BenchmarkParams benchmarkParams) {
    String benchmark = benchmarkParams.getBenchmark();
    return benchmark.substring(benchmark.lastIndexOf('.') + 1);
  }

  private void validateParameters() {
    if (!SAME_PROVIDER.equals(providerLifecycle)
        && !RESTARTED_PROVIDER.equals(providerLifecycle)) {
      throw new IllegalArgumentException(
          "Unsupported provider lifecycle " + providerLifecycle);
    }
    if (evidenceRepeat < 1) {
      throw new IllegalArgumentException("Evidence repeat must be positive");
    }
  }

  private void buildDeterministicHistory() throws Exception {
    ObjectId parent = null;
    for (int push = 0; push < pushes; push++) {
      byte[] payload = deterministicPayload(push);
      ObjectId blob;
      ObjectId commitId;
      try (ObjectInserter inserter = repository.newObjectInserter()) {
        blob = inserter.insert(Constants.OBJ_BLOB, payload);
        if (push % 10 == 0) {
          byte[] unreachable = deterministicPayload(100_000 + push);
          inserter.insert(Constants.OBJ_BLOB, unreachable);
          unreachableLogicalBytes += unreachable.length;
        }
        TreeFormatter tree = new TreeFormatter();
        tree.append("payload.bin", FileMode.REGULAR_FILE, blob);
        ObjectId treeId = inserter.insert(tree);

        CommitBuilder commit = new CommitBuilder();
        commit.setTreeId(treeId);
        if (parent != null) {
          commit.setParentId(parent);
        }
        PersonIdent identity =
            new PersonIdent(
                "Aging benchmark",
                "aging@example.invalid",
                Instant.ofEpochSecond(1_700_000_000L + push),
                ZoneOffset.UTC);
        commit.setAuthor(identity);
        commit.setCommitter(identity);
        commit.setMessage("Deterministic push " + push);
        commitId = inserter.insert(commit);
        inserter.flush();
      }
      publishMain(parent, commitId);
      if (oldestBlob == null) {
        oldestBlob = blob;
      }
      newestBlob = blob;
      parent = commitId;
      commits.add(commitId);
    }
    headCommit = parent;
    incrementalBoundary = commits.size() > 10 ? commits.get(commits.size() - 11) : null;
  }

  private void publishMain(ObjectId expectedOld, ObjectId newCommit) throws Exception {
    RefUpdate update = repository.updateRef("refs/heads/main");
    update.setExpectedOldObjectId(expectedOld == null ? ObjectId.zeroId() : expectedOld);
    update.setNewObjectId(newCommit);
    update.disableRefLog();
    RefUpdate.Result result = update.update();
    if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FAST_FORWARD) {
      throw new IllegalStateException("Unexpected deterministic main update result: " + result);
    }
  }

  private void applyMaintenance() {
    repackResult =
        switch (maintenanceMode) {
          case NONE -> null;
          case COMPACT_ONLY ->
              new PackStorageMaintenance(provider.getSessionFactory())
                  .repack(new RepositoryName(repositoryName), PackRepackOptions.compactOnly());
          case READ_OPTIMIZED ->
              new PackStorageMaintenance(provider.getSessionFactory())
                  .repack(new RepositoryName(repositoryName), PackRepackOptions.optimizedForReads());
          default -> throw new IllegalArgumentException("Unsupported maintenance mode " + maintenanceMode);
        };
    if (repackResult != null && !repackResult.successful()) {
      throw new IllegalStateException("JGit rejected deterministic maintenance because refs raced");
    }
  }

  private void closeAndReopen() throws Exception {
    if (repository != null) {
      repository.close();
    }
    repository = HibernateRepository.create(provider.getSessionFactory(), repositoryName);
    statistics.clear();
    bytesBeforeInvocation = repository.getStorageByteMetrics();
  }

  private void restartProvider() throws Exception {
    if (repository != null) {
      repository.close();
      repository = null;
    }
    if (provider != null) {
      provider.close();
      provider = null;
    }
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    provider = new HibernateSessionFactoryProvider(properties("validate"));
    statistics = provider.getSessionFactory().getStatistics();
    repository = HibernateRepository.create(provider.getSessionFactory(), repositoryName);
    statistics.clear();
    bytesBeforeInvocation = repository.getStorageByteMetrics();
    providerRestartCount++;
  }

  private void warmRepositoryCache() throws Exception {
    objectSize(oldestBlob);
    objectSize(newestBlob);
    try (ObjectReader reader = repository.newObjectReader()) {
      reader.has(MISSING_OBJECT);
    }
    repository.getRefDatabase().getRefsByPrefix("refs/");
    try (RevWalk walk = new RevWalk(repository)) {
      walk.markStart(walk.parseCommit(headCommit));
      for (RevCommit ignored : walk) {
        // Populate JGit's object and graph caches without adding benchmark counters.
      }
    }
    statistics.clear();
    bytesBeforeInvocation = repository.getStorageByteMetrics();
  }

  private void verifyPersistedPackOrdering() {
    try (Session session = provider.getSessionFactory().openSession()) {
      List<Instant> committedAt =
          session
              .createQuery(
                  "SELECT p.committedAt FROM GitPackEntity p "
                      + "WHERE p.repositoryName = :repo AND p.committed = true "
                      + "ORDER BY p.committedAt, p.id",
                  Instant.class)
              .setParameter("repo", repositoryName)
              .getResultList();
      Instant previous = null;
      for (Instant current : committedAt) {
        if (current == null) {
          throw new IllegalStateException("Published pack has no committedAt ordering key");
        }
        if (previous != null && current.isBefore(previous)) {
          throw new IllegalStateException("Persisted pack metadata is not ordered by committedAt");
        }
        previous = current;
      }
    }
  }

  private void verifyReachableFixture() throws Exception {
    if (objectSize(oldestBlob) != PAYLOAD_BYTES || objectSize(newestBlob) != PAYLOAD_BYTES) {
      throw new IllegalStateException("Aging fixture did not survive close/reopen or maintenance");
    }
    Ref main = repository.exactRef("refs/heads/main");
    if (main == null || !headCommit.equals(main.getObjectId())) {
      throw new IllegalStateException("Aging fixture main ref did not survive close/reopen");
    }
  }

  private long objectSize(ObjectId id) throws Exception {
    try (ObjectReader reader = repository.newObjectReader()) {
      ObjectLoader loader = reader.open(id, Constants.OBJ_BLOB);
      return loader.getSize();
    }
  }

  private long streamPayload(RevCommit commit) throws Exception {
    try (TreeWalk path = TreeWalk.forPath(repository, "payload.bin", commit.getTree())) {
      if (path == null) {
        throw new IllegalStateException("payload.bin missing from deterministic commit " + commit);
      }
      try (ObjectReader reader = repository.newObjectReader();
          InputStream input = reader.open(path.getObjectId(0), Constants.OBJ_BLOB).openStream()) {
        byte[] buffer = new byte[STREAM_BUFFER_BYTES];
        long total = 0;
        int count;
        while ((count = input.read(buffer)) >= 0) {
          total += count;
        }
        return total;
      }
    }
  }

  private Inventory inventory() {
    try (Session session = provider.getSessionFactory().openSession()) {
      long activePacks = scalar(session, "pack", null);
      long packBytes = scalar(session, "pack", "SUM");
      long indexBytes = scalar(session, "idx", "SUM");
      long reftables = scalar(session, "ref", null);
      long smallPacks =
          session
              .createQuery(
                  "SELECT COUNT(p) FROM GitPackEntity p WHERE p.repositoryName = :repo "
                      + "AND p.committed = true AND p.packExtension = 'pack' "
                      + "AND p.fileSize < :limit",
                  Long.class)
              .setParameter("repo", repositoryName)
              .setParameter("limit", (long) SMALL_PACK_LIMIT_BYTES)
              .getSingleResult();
      long storedBytes =
          session
              .createQuery(
                  "SELECT COALESCE(SUM(p.fileSize), 0) FROM GitPackEntity p "
                      + "WHERE p.repositoryName = :repo AND p.committed = true",
                  Long.class)
              .setParameter("repo", repositoryName)
              .getSingleResult();
      return new Inventory(activePacks, packBytes, indexBytes, reftables, smallPacks, storedBytes);
    }
  }

  private long scalar(Session session, String extension, String aggregate) {
    String expression = aggregate == null ? "COUNT(p)" : "COALESCE(SUM(p.fileSize), 0)";
    return session
        .createQuery(
            "SELECT "
                + expression
                + " FROM GitPackEntity p WHERE p.repositoryName = :repo "
                + "AND p.committed = true AND p.packExtension = :extension",
            Long.class)
        .setParameter("repo", repositoryName)
        .setParameter("extension", extension)
        .getSingleResult();
  }

  private Properties properties(String schemaAction) {
    Properties properties = new Properties();
    properties.put("hibernate.hbm2ddl.auto", schemaAction);
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.format_sql", "false");
    properties.put("hibernate.search.enabled", "false");
    properties.put("hibernate.generate_statistics", "true");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    switch (backend) {
      case HibernateRepositoryBenchmark.HSQLDB -> {
        properties.put("hibernate.connection.url", "jdbc:hsqldb:mem:" + repositoryName);
        properties.put("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver");
        properties.put("hibernate.dialect", "org.hibernate.dialect.HSQLDialect");
        properties.put("hibernate.connection.pool_size", "4");
      }
      case HibernateRepositoryBenchmark.POSTGRESQL,
          HibernateRepositoryBenchmark.POSTGRESQL_HIKARI -> {
        properties.put(
            "hibernate.connection.url",
            requiredProperty(HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY));
        properties.put(
            "hibernate.connection.username",
            requiredProperty(HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY));
        properties.put(
            "hibernate.connection.password",
            requiredProperty(HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY));
        properties.put("hibernate.connection.driver_class", "org.postgresql.Driver");
        properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        if (HibernateRepositoryBenchmark.POSTGRESQL_HIKARI.equals(backend)) {
          properties.put("hibernate.hikari.maximumPoolSize", "4");
          properties.put("hibernate.hikari.minimumIdle", "0");
          properties.put("hibernate.hikari.connectionTimeout", "10000");
          properties.put("hibernate.hikari.poolName", "jgit-aging-" + repositoryName);
        } else {
          properties.put("hibernate.connection.pool_size", "4");
        }
      }
      case PackStorageLayoutBenchmark.SQL_SERVER -> {
        properties.put(
            "hibernate.connection.url",
            requiredProperty(PackStorageLayoutBenchmark.SQL_SERVER_URL_PROPERTY));
        properties.put(
            "hibernate.connection.username",
            requiredProperty(PackStorageLayoutBenchmark.SQL_SERVER_USER_PROPERTY));
        properties.put(
            "hibernate.connection.password",
            requiredProperty(PackStorageLayoutBenchmark.SQL_SERVER_PASSWORD_PROPERTY));
        properties.put(
            "hibernate.connection.driver_class",
            "com.microsoft.sqlserver.jdbc.SQLServerDriver");
        properties.put("hibernate.dialect", "org.hibernate.dialect.SQLServerDialect");
        properties.put("hibernate.connection.pool_size", "4");
      }
      default -> throw new IllegalArgumentException("Unsupported aging backend " + backend);
    }
    return properties;
  }

  private static byte[] deterministicPayload(int seed) {
    byte[] payload = new byte[PAYLOAD_BYTES];
    byte[] prefix = ("push-" + seed + "\n").getBytes(StandardCharsets.UTF_8);
    System.arraycopy(prefix, 0, payload, 0, Math.min(prefix.length, payload.length));
    int value = seed ^ 0x4147494e;
    for (int index = prefix.length; index < payload.length; index++) {
      value = value * 1664525 + 1013904223;
      payload[index] = (byte) (value >>> 24);
    }
    return payload;
  }

  private static String requiredProperty(String name) {
    String value = System.getProperty(name);
    if (value != null && !value.isBlank()) {
      return value;
    }

    String connectionPropertiesFile =
        System.getProperty(
            PackStorageLayoutBenchmark.CONNECTION_PROPERTIES_FILE_PROPERTY);
    if (connectionPropertiesFile != null && !connectionPropertiesFile.isBlank()) {
      Properties connectionProperties = new Properties();
      try (InputStream input =
          Files.newInputStream(Path.of(connectionPropertiesFile))) {
        connectionProperties.load(input);
      } catch (IOException failure) {
        throw new IllegalStateException(
            "Cannot read temporary benchmark connection properties", failure);
      }
      value = connectionProperties.getProperty(name);
    }
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing benchmark system property " + name);
    }
    return value;
  }

  private static void suppressConnectionMetadataLogging() {
    java.util.logging.Level warning = java.util.logging.Level.WARNING;
    Logger.getLogger("").setLevel(warning);
    Logger.getLogger("org.hibernate").setLevel(warning);
    Logger.getLogger("org.hibernate.orm.connections.pooling").setLevel(warning);
    Logger.getLogger(
            "org.hibernate.engine.jdbc.env.internal.JdbcEnvironmentInitiator")
        .setLevel(warning);
  }

  @FunctionalInterface
  private interface CheckedAction {
    void run() throws Exception;
  }

  private static boolean jgitDfsExposesMultiPackIndexExtension() {
    return Arrays.stream(PackExt.values())
        .map(Enum::name)
        .anyMatch(name -> name.contains("MULTI") && name.contains("PACK"));
  }

  /** Structural condition, maintenance cost and physical I/O attributed to one operation. */
  @AuxCounters(AuxCounters.Type.EVENTS)
  @State(Scope.Thread)
  public static class AgingCounters {
    public long databaseQueries;
    public long databasePayloadBytesRead;
    public long readAheadBytesFetched;
    public long readAheadBytesConsumed;
    public long readAheadOverfetchBytes;
    public long activePacks;
    public long packPayloadBytes;
    public long packIndexBytes;
    public long reftables;
    public long smallPacks;
    public long smallPackRatioBasisPoints;
    public long storedExtensionBytes;
    public long unreachableLogicalBytes;
    public long maintenanceElapsedMillis;
    public long maintenanceStoredByteDelta;
    public long maintenancePackReduction;
    public long providerRestarts;
    public long jgitDfsMidxExtensionAvailable;

    @Setup(Level.Invocation)
    public void reset() {
      databaseQueries = 0;
      databasePayloadBytesRead = 0;
      readAheadBytesFetched = 0;
      readAheadBytesConsumed = 0;
      readAheadOverfetchBytes = 0;
      activePacks = 0;
      packPayloadBytes = 0;
      packIndexBytes = 0;
      reftables = 0;
      smallPacks = 0;
      smallPackRatioBasisPoints = 0;
      storedExtensionBytes = 0;
      unreachableLogicalBytes = 0;
      maintenanceElapsedMillis = 0;
      maintenanceStoredByteDelta = 0;
      maintenancePackReduction = 0;
      providerRestarts = 0;
      jgitDfsMidxExtensionAvailable = 0;
    }

    private void capture(RepositoryAgingBenchmark benchmark) {
      StorageByteMetrics bytes =
          benchmark.repository.getStorageByteMetrics().minus(benchmark.bytesBeforeInvocation);
      Inventory inventory = benchmark.inventory;
      databaseQueries = benchmark.statistics.getQueryExecutionCount();
      databasePayloadBytesRead = bytes.databasePayloadBytesRead();
      readAheadBytesFetched = bytes.readAheadBytesFetched();
      readAheadBytesConsumed = bytes.readAheadBytesConsumed();
      readAheadOverfetchBytes = bytes.readAheadOverfetchBytes();
      activePacks = inventory.activePacks();
      packPayloadBytes = inventory.packBytes();
      packIndexBytes = inventory.indexBytes();
      reftables = inventory.reftables();
      smallPacks = inventory.smallPacks();
      smallPackRatioBasisPoints =
          activePacks == 0 ? 0 : Math.multiplyExact(smallPacks, 10_000) / activePacks;
      storedExtensionBytes = inventory.storedBytes();
      unreachableLogicalBytes = benchmark.unreachableLogicalBytes;
      if (benchmark.repackResult != null) {
        maintenanceElapsedMillis =
            TimeUnit.NANOSECONDS.toMillis(benchmark.repackResult.elapsedNanos());
        maintenanceStoredByteDelta = benchmark.repackResult.storedByteDelta();
        maintenancePackReduction = benchmark.repackResult.packReduction();
      }
      providerRestarts = benchmark.providerRestartCount;
      jgitDfsMidxExtensionAvailable = jgitDfsExposesMultiPackIndexExtension() ? 1 : 0;
    }
  }

  private record Inventory(
      long activePacks,
      long packBytes,
      long indexBytes,
      long reftables,
      long smallPacks,
      long storedBytes) {}
}
