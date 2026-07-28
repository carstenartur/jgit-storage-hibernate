/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationMetrics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.TestProtocol;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UploadPack;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;

/**
 * Measures real JGit upload-pack and receive-pack workflows against each storage backend.
 *
 * <p>Every measured invocation receives a fresh server and client prepared outside the timing
 * window. This avoids repository growth across samples and makes initial and incremental scenarios
 * independent. Clients are in-memory repositories so the result emphasizes server-side pack
 * generation, ingestion, ref publication and backend persistence.
 *
 * <p>For Hibernate backends, JMH secondary results expose query, statement, transaction, connection
 * and repository-lock costs for the same measured operation. Metrics are reset after fixture
 * preparation and therefore exclude schema creation and baseline history construction.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 5)
@Fork(1)
@State(Scope.Thread)
public class GitProtocolBenchmark {

  private static final int INITIAL_HISTORY_COMMITS = 24;
  private static final int BASE_HISTORY_COMMITS = 20;
  private static final int INCREMENTAL_COMMITS = 4;
  private static final int PAYLOAD_BYTES = 32 * 1024;
  private static final int INITIAL_PUSH_SEED = 0x13572468;
  private static final int INCREMENTAL_PUSH_SEED = 0x24681357;
  private static final int INITIAL_CLONE_SEED = 0x31415926;
  private static final int INCREMENTAL_FETCH_SEED = 0x27182818;
  private static final String SOURCE_REF = "refs/heads/source";
  private static final String LOCAL_MAIN_REF = "refs/heads/main";
  private static final String REMOTE_MAIN_REF = "refs/heads/main";
  private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

  private final AtomicInteger invocationCounter = new AtomicInteger();

  @Param({
    HibernateRepositoryBenchmark.FILESYSTEM,
    HibernateRepositoryBenchmark.HSQLDB,
    HibernateRepositoryBenchmark.POSTGRESQL,
    HibernateRepositoryBenchmark.POSTGRESQL_HIKARI
  })
  public String backend;

  private HibernateSessionFactoryProvider provider;
  private Repository server;
  private Path serverDirectory;
  private TestProtocol<Object> protocol;
  private URIish serverUri;
  private InMemoryRepository client;
  private ObjectId expectedOld;
  private ObjectId expectedTip;
  private Statistics hibernateStatistics;
  private StorageOperationMetrics storageMetricsBaseline = StorageOperationMetrics.ZERO;

  @Setup(Level.Trial)
  public void setupTrial() {
    if (!HibernateRepositoryBenchmark.FILESYSTEM.equals(backend)) {
      String databaseName = "jmh-protocol-" + backend + "-" + Long.toHexString(System.nanoTime());
      Properties properties =
          switch (backend) {
            case HibernateRepositoryBenchmark.HSQLDB -> hsqlDbProperties(databaseName);
            case HibernateRepositoryBenchmark.POSTGRESQL -> postgreSqlProperties(false);
            case HibernateRepositoryBenchmark.POSTGRESQL_HIKARI -> postgreSqlProperties(true);
            default -> throw new IllegalArgumentException("Unsupported benchmark backend: " + backend);
          };
      provider = new HibernateSessionFactoryProvider(properties);
      hibernateStatistics = provider.getSessionFactory().getStatistics();
    }
  }

  @Setup(Level.Invocation)
  public void setupInvocation(BenchmarkParams benchmarkParams) throws Exception {
    int invocation = invocationCounter.incrementAndGet();
    String operation = benchmarkParams.getBenchmark();
    operation = operation.substring(operation.lastIndexOf('.') + 1);
    String serverName =
        "jmh-protocol-"
            + backend
            + "-"
            + operation
            + "-"
            + invocation
            + "-"
            + Long.toHexString(System.nanoTime());

    server = createServerRepository(serverName);
    server.create(true);
    client = newMemoryRepository(operation + "-" + invocation);
    expectedOld = ObjectId.zeroId();

    switch (operation) {
      case "initialPushViaReceivePack" ->
          expectedTip =
              writeHistory(
                  client,
                  null,
                  0,
                  INITIAL_HISTORY_COMMITS,
                  INITIAL_PUSH_SEED,
                  SOURCE_REF);
      case "incrementalPushViaReceivePack" -> prepareIncrementalPush();
      case "initialCloneViaUploadPack" ->
          expectedTip =
              writeHistory(
                  server,
                  null,
                  0,
                  INITIAL_HISTORY_COMMITS,
                  INITIAL_CLONE_SEED,
                  REMOTE_MAIN_REF);
      case "incrementalFetchViaUploadPack" -> prepareIncrementalFetch();
      default -> throw new IllegalArgumentException("Unsupported protocol benchmark: " + operation);
    }

    protocol =
        new TestProtocol<>(
            (Object request, Repository repository) -> new UploadPack(repository),
            (Object request, Repository repository) -> new ReceivePack(repository));
    Transport.register(protocol);
    serverUri = protocol.register(new Object(), server);

    if (server instanceof HibernateRepository hibernateRepository) {
      hibernateStatistics.clear();
      storageMetricsBaseline = hibernateRepository.getStorageOperationMetrics();
    }
  }

  @TearDown(Level.Invocation)
  public void tearDownInvocation() throws IOException {
    if (protocol != null) {
      Transport.unregister(protocol);
      protocol = null;
    }
    close(client);
    close(server);
    client = null;
    server = null;
    serverUri = null;
    expectedOld = null;
    expectedTip = null;
    storageMetricsBaseline = StorageOperationMetrics.ZERO;
    deleteRecursively(serverDirectory);
    serverDirectory = null;
  }

  @TearDown(Level.Trial)
  public void tearDownTrial() {
    if (provider != null) {
      provider.close();
      provider = null;
      hibernateStatistics = null;
    }
  }

  /** Receives an unrelated complete history and creates a new remote branch. */
  @Benchmark
  public ObjectId initialPushViaReceivePack(ProtocolStorageCounters counters) throws Exception {
    push(expectedOld, expectedTip);
    captureStorageCounters(counters);
    return expectedTip;
  }

  /** Receives four descendants after the server already has the twenty-commit base history. */
  @Benchmark
  public ObjectId incrementalPushViaReceivePack(ProtocolStorageCounters counters) throws Exception {
    push(expectedOld, expectedTip);
    captureStorageCounters(counters);
    return expectedTip;
  }

  /** Fetches a complete history into an otherwise empty bare repository. */
  @Benchmark
  public ObjectId initialCloneViaUploadPack(ProtocolStorageCounters counters) throws Exception {
    fetch(expectedTip);
    captureStorageCounters(counters);
    return expectedTip;
  }

  /** Fetches only four descendants into a client that already has the twenty-commit base. */
  @Benchmark
  public ObjectId incrementalFetchViaUploadPack(ProtocolStorageCounters counters) throws Exception {
    fetch(expectedTip);
    captureStorageCounters(counters);
    return expectedTip;
  }

  private void captureStorageCounters(ProtocolStorageCounters counters) {
    if (!(server instanceof HibernateRepository hibernateRepository)) {
      return;
    }
    StorageOperationMetrics storageDelta =
        hibernateRepository.getStorageOperationMetrics().minus(storageMetricsBaseline);
    counters.hibernateQueries = hibernateStatistics.getQueryExecutionCount();
    counters.preparedStatements = hibernateStatistics.getPrepareStatementCount();
    counters.hibernateTransactions = hibernateStatistics.getTransactionCount();
    counters.connections = hibernateStatistics.getConnectCount();
    counters.storageTransactions = storageDelta.transactionsStarted();
    counters.storageCommits = storageDelta.transactionsCommitted();
    counters.storageRollbacks = storageDelta.transactionsRolledBack();
    counters.repositoryLocks = storageDelta.repositoryLocksAcquired();
    counters.repositoryLockAcquisitionMicros =
        TimeUnit.NANOSECONDS.toMicros(storageDelta.repositoryLockAcquisitionNanos());
  }

  private void prepareIncrementalPush() throws Exception {
    ObjectId serverBase =
        writeHistory(
            server,
            null,
            0,
            BASE_HISTORY_COMMITS,
            INCREMENTAL_PUSH_SEED,
            REMOTE_MAIN_REF);
    ObjectId clientBase =
        writeHistory(
            client,
            null,
            0,
            BASE_HISTORY_COMMITS,
            INCREMENTAL_PUSH_SEED,
            SOURCE_REF);
    requireSameBase(serverBase, clientBase);
    expectedOld = serverBase;
    expectedTip =
        writeHistory(
            client,
            clientBase,
            BASE_HISTORY_COMMITS,
            INCREMENTAL_COMMITS,
            INCREMENTAL_PUSH_SEED,
            SOURCE_REF);
  }

  private void prepareIncrementalFetch() throws Exception {
    ObjectId serverBase =
        writeHistory(
            server,
            null,
            0,
            BASE_HISTORY_COMMITS,
            INCREMENTAL_FETCH_SEED,
            "refs/heads/base");
    expectedTip =
        writeHistory(
            server,
            serverBase,
            BASE_HISTORY_COMMITS,
            INCREMENTAL_COMMITS,
            INCREMENTAL_FETCH_SEED,
            REMOTE_MAIN_REF);
    ObjectId clientBase =
        writeHistory(
            client,
            null,
            0,
            BASE_HISTORY_COMMITS,
            INCREMENTAL_FETCH_SEED,
            LOCAL_MAIN_REF);
    requireSameBase(serverBase, clientBase);
  }

  private void push(ObjectId oldId, ObjectId tip) throws Exception {
    RemoteRefUpdate update =
        new RemoteRefUpdate(client, SOURCE_REF, REMOTE_MAIN_REF, false, null, oldId);
    try (Transport transport = Transport.open(client, serverUri)) {
      transport.push(NullProgressMonitor.INSTANCE, List.of(update));
    }
    if (update.getStatus() != RemoteRefUpdate.Status.OK
        && update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {
      throw new IllegalStateException(
          "Push failed with " + update.getStatus() + ": " + update.getMessage());
    }
    Ref remote = server.exactRef(REMOTE_MAIN_REF);
    if (remote == null || !tip.equals(remote.getObjectId())) {
      throw new IllegalStateException("Remote main did not reach " + tip);
    }
  }

  private void fetch(ObjectId tip) throws Exception {
    try (Transport transport = Transport.open(client, serverUri)) {
      transport.fetch(
          NullProgressMonitor.INSTANCE,
          List.of(new RefSpec(REMOTE_MAIN_REF + ":" + LOCAL_MAIN_REF)));
    }
    Ref local = client.exactRef(LOCAL_MAIN_REF);
    if (local == null || !tip.equals(local.getObjectId())) {
      throw new IllegalStateException("Local main did not reach " + tip);
    }
  }

  private static ObjectId writeHistory(
      Repository repository,
      ObjectId parent,
      int startIndex,
      int commitCount,
      int seed,
      String refName)
      throws Exception {
    ObjectId tip = parent;
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      for (int index = startIndex; index < startIndex + commitCount; index++) {
        byte[] payload = new byte[PAYLOAD_BYTES];
        new Random((((long) seed) << 32) ^ index).nextBytes(payload);
        ObjectId blob = inserter.insert(Constants.OBJ_BLOB, payload);

        TreeFormatter tree = new TreeFormatter();
        tree.append("payload.bin", FileMode.REGULAR_FILE, blob);
        ObjectId treeId = inserter.insert(tree);

        Date timestamp =
            Date.from(Instant.ofEpochSecond(1_700_000_000L + (seed & 0xffffL) * 100L + index));
        PersonIdent identity =
            new PersonIdent("Protocol benchmark", "benchmark@example.invalid", timestamp, UTC);
        CommitBuilder commit = new CommitBuilder();
        commit.setTreeId(treeId);
        if (tip != null) {
          commit.setParentId(tip);
        }
        commit.setAuthor(identity);
        commit.setCommitter(identity);
        commit.setMessage("Protocol history " + seed + " commit " + index);
        tip = inserter.insert(commit);
      }
      inserter.flush();
    }
    updateRef(repository, refName, tip);
    return tip;
  }

  private static void requireSameBase(ObjectId serverBase, ObjectId clientBase) {
    if (!serverBase.equals(clientBase)) {
      throw new IllegalStateException(
          "Deterministic protocol base differs: server=" + serverBase + ", client=" + clientBase);
    }
  }

  private static void updateRef(Repository repository, String refName, ObjectId objectId)
      throws IOException {
    RefUpdate update = repository.updateRef(refName);
    update.setNewObjectId(objectId);
    RefUpdate.Result result = update.update();
    if (result != RefUpdate.Result.NEW
        && result != RefUpdate.Result.FAST_FORWARD
        && result != RefUpdate.Result.FORCED) {
      throw new IOException("Unexpected ref update result " + result + " for " + refName);
    }
  }

  private Repository createServerRepository(String name) throws IOException {
    if (HibernateRepositoryBenchmark.FILESYSTEM.equals(backend)) {
      serverDirectory = Files.createTempDirectory("jgit-protocol-filesystem-benchmark-");
      return new FileRepositoryBuilder().setGitDir(serverDirectory.toFile()).setBare().build();
    }
    return HibernateRepository.create(provider.getSessionFactory(), name);
  }

  private static InMemoryRepository newMemoryRepository(String name) throws IOException {
    InMemoryRepository repository =
        new InMemoryRepository(new DfsRepositoryDescription("jmh-" + name));
    repository.create(true);
    return repository;
  }

  private static Properties hsqlDbProperties(String name) {
    Properties properties = commonHibernateProperties();
    properties.put("hibernate.connection.url", "jdbc:hsqldb:mem:" + name);
    properties.put("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.HSQLDialect");
    return properties;
  }

  private static Properties postgreSqlProperties(boolean hikari) {
    Properties properties = commonHibernateProperties();
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
    if (hikari) {
      properties.put("hibernate.hikari.maximumPoolSize", "4");
      properties.put("hibernate.hikari.minimumIdle", "1");
      properties.put("hibernate.hikari.connectionTimeout", "10000");
      properties.put("hibernate.hikari.poolName", "jgit-storage-hibernate-protocol-benchmark");
    }
    return properties;
  }

  private static Properties commonHibernateProperties() {
    Properties properties = new Properties();
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.format_sql", "false");
    properties.put("hibernate.connection.pool_size", "4");
    properties.put("hibernate.generate_statistics", "true");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    return properties;
  }

  private static String requiredSystemProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "Missing PostgreSQL benchmark system property "
              + name
              + "; run through the Maven benchmark-comparison profile");
    }
    return value;
  }

  private static void close(Repository repository) {
    if (repository != null) {
      repository.close();
    }
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (Stream<Path> paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    }
  }

  /** Per-invocation storage costs published as JMH secondary event counters. */
  @AuxCounters(AuxCounters.Type.EVENTS)
  @State(Scope.Thread)
  public static class ProtocolStorageCounters {
    public long hibernateQueries;
    public long preparedStatements;
    public long hibernateTransactions;
    public long connections;
    public long storageTransactions;
    public long storageCommits;
    public long storageRollbacks;
    public long repositoryLocks;
    public long repositoryLockAcquisitionMicros;

    @Setup(Level.Invocation)
    public void reset() {
      hibernateQueries = 0;
      preparedStatements = 0;
      hibernateTransactions = 0;
      connections = 0;
      storageTransactions = 0;
      storageCommits = 0;
      storageRollbacks = 0;
      repositoryLocks = 0;
      repositoryLockAcquisitionMicros = 0;
    }
  }
}
