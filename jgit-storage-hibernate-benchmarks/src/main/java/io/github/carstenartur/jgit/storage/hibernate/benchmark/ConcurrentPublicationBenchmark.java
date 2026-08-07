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
import io.github.carstenartur.jgit.storage.hibernate.transaction.PackPublicationSelectionMetrics;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StagingSpillMetrics;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageByteMetrics;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationMetrics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
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
import org.openjdk.jmh.infra.ThreadParams;

/**
 * Compares concurrent publication to one logical repository with publication to independent
 * repositories.
 *
 * <p>Every JMH thread owns a separate repository instance. Hibernate threads also own independent
 * {@link org.hibernate.SessionFactory} instances connected to the same database, so the
 * same-repository variant exercises the real cross-SessionFactory row lock rather than an
 * in-process Java lock. The different-repository variant uses one lock row per thread and therefore
 * measures how much parallelism the repository-scoped coordination model preserves.
 *
 * <p>The default 256 KiB payload remains the ordinary-publication regression guard. Focused profiles
 * override {@link #payloadMiB} with 16 or 128 and JMH's thread count with 1, 4 and 16 to compare the
 * stateful reference writer and opt-in stateless writer under real contention. Payload and physical
 * byte counters make write amplification and adaptive-publication selection visible.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@Threads(4)
@State(Scope.Benchmark)
public class ConcurrentPublicationBenchmark {

  static final String STATEFUL = "stateful";
  static final String STATELESS = "stateless";
  static final String LOCAL_TESTCONTAINERS = "local-testcontainers";
  private static final String CHUNK_WRITER_PROPERTY =
      "jgit.storage.hibernate.pack.chunk_writer";
  private static final int MAX_THREADS = 16;
  private static final int SMALL_PAYLOAD_SIZE = 256 * 1024 + 257;

  @Param({
    HibernateRepositoryBenchmark.FILESYSTEM,
    HibernateRepositoryBenchmark.HSQLDB,
    HibernateRepositoryBenchmark.POSTGRESQL,
    HibernateRepositoryBenchmark.POSTGRESQL_HIKARI
  })
  public String backend;

  @Param({STATEFUL})
  public String writeMode;

  /** Zero selects the ordinary 256 KiB regression payload. */
  @Param({"0"})
  public int payloadMiB;

  @Param({LOCAL_TESTCONTAINERS})
  public String deployment;

  private String databaseName;
  private String sharedRepositoryName;
  private String[] isolatedRepositoryNames;
  private HibernateSessionFactoryProvider schemaProvider;
  private Path filesystemRoot;
  private Path sharedRepositoryDirectory;
  private Path[] isolatedRepositoryDirectories;

  @Setup(Level.Trial)
  public void setupTrial() throws Exception {
    String trialId = Long.toHexString(System.nanoTime());
    sharedRepositoryName = "jmh-concurrent-shared-" + trialId;
    isolatedRepositoryNames = new String[MAX_THREADS];
    for (int index = 0; index < MAX_THREADS; index++) {
      isolatedRepositoryNames[index] = "jmh-concurrent-isolated-" + trialId + "-" + index;
    }

    if (HibernateRepositoryBenchmark.FILESYSTEM.equals(backend)) {
      setupFilesystemRepositories();
      return;
    }

    databaseName = "jmh-concurrent-" + backend + "-" + trialId;
    schemaProvider = new HibernateSessionFactoryProvider(hibernateProperties("create-drop"));
    createHibernateRepository(schemaProvider, sharedRepositoryName);
    for (String repositoryName : isolatedRepositoryNames) {
      createHibernateRepository(schemaProvider, repositoryName);
    }
  }

  @TearDown(Level.Trial)
  public void tearDownTrial() throws IOException {
    if (schemaProvider != null) {
      schemaProvider.close();
      schemaProvider = null;
    }
    deleteRecursively(filesystemRoot);
    filesystemRoot = null;
    sharedRepositoryDirectory = null;
    isolatedRepositoryDirectories = null;
  }

  /** Publish through independent handles contending for one repository lock row. */
  @Benchmark
  public ObjectId publishToSameRepository(
      ThreadRepositoryState state, ConcurrentStorageCounters counters) throws Exception {
    return state.publish(state.sharedRepository, "shared", counters);
  }

  /** Publish through independent handles using independent repository lock rows. */
  @Benchmark
  public ObjectId publishToDifferentRepositories(
      ThreadRepositoryState state, ConcurrentStorageCounters counters) throws Exception {
    return state.publish(state.isolatedRepository, "isolated", counters);
  }

  private void setupFilesystemRepositories() throws IOException {
    filesystemRoot = Files.createTempDirectory("jgit-concurrent-benchmark-");
    sharedRepositoryDirectory = filesystemRoot.resolve("shared.git");
    createFilesystemRepository(sharedRepositoryDirectory);
    isolatedRepositoryDirectories = new Path[MAX_THREADS];
    for (int index = 0; index < MAX_THREADS; index++) {
      Path directory = filesystemRoot.resolve("isolated-" + index + ".git");
      isolatedRepositoryDirectories[index] = directory;
      createFilesystemRepository(directory);
    }
  }

  private Properties hibernateProperties(String ddlMode) {
    Properties properties = new Properties();
    properties.put("hibernate.hbm2ddl.auto", ddlMode);
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.format_sql", "false");
    properties.put("hibernate.search.enabled", "false");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    properties.put(CHUNK_WRITER_PROPERTY, writeMode);

    switch (backend) {
      case HibernateRepositoryBenchmark.HSQLDB -> {
        properties.put("hibernate.connection.url", "jdbc:hsqldb:mem:" + databaseName);
        properties.put("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver");
        properties.put("hibernate.dialect", "org.hibernate.dialect.HSQLDialect");
        properties.put("hibernate.connection.pool_size", Integer.toString(MAX_THREADS + 2));
      }
      case HibernateRepositoryBenchmark.POSTGRESQL,
          HibernateRepositoryBenchmark.POSTGRESQL_HIKARI -> {
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
        if (HibernateRepositoryBenchmark.POSTGRESQL_HIKARI.equals(backend)) {
          properties.put("hibernate.hikari.maximumPoolSize", Integer.toString(MAX_THREADS + 2));
          properties.put("hibernate.hikari.minimumIdle", "1");
          properties.put("hibernate.hikari.connectionTimeout", "10000");
          properties.put("hibernate.hikari.poolName", "jgit-concurrent-" + databaseName);
        } else {
          properties.put("hibernate.connection.pool_size", Integer.toString(MAX_THREADS + 2));
        }
      }
      default -> throw new IllegalArgumentException("Unsupported concurrent backend: " + backend);
    }
    return properties;
  }

  private static String requiredSystemProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing benchmark system property " + name);
    }
    return value;
  }

  private static void createHibernateRepository(
      HibernateSessionFactoryProvider provider, String repositoryName) throws IOException {
    try (HibernateRepository repository =
        HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
    }
  }

  private static void createFilesystemRepository(Path directory) throws IOException {
    try (Repository repository =
        new FileRepositoryBuilder().setGitDir(directory.toFile()).setBare().build()) {
      repository.create(true);
    }
  }

  private static Repository openFilesystemRepository(Path directory) throws IOException {
    return new FileRepositoryBuilder()
        .setGitDir(directory.toFile())
        .setBare()
        .setMustExist(true)
        .build();
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

  /** Per-thread repository handles and payload state, all constructed outside measured operations. */
  @State(Scope.Thread)
  public static class ThreadRepositoryState {

    private final AtomicLong sequence = new AtomicLong();
    private byte[] payload;
    private int threadIndex;
    private HibernateSessionFactoryProvider provider;
    private Repository sharedRepository;
    private Repository isolatedRepository;

    @Setup(Level.Trial)
    public void setup(ConcurrentPublicationBenchmark benchmark, ThreadParams threadParams)
        throws Exception {
      threadIndex = threadParams.getThreadIndex();
      if (threadIndex >= MAX_THREADS) {
        throw new IllegalStateException("Benchmark supports at most " + MAX_THREADS + " threads");
      }
      int payloadSize =
          benchmark.payloadMiB == 0
              ? SMALL_PAYLOAD_SIZE
              : Math.addExact(Math.multiplyExact(benchmark.payloadMiB, 1024 * 1024), 257);
      payload = new byte[payloadSize];
      new Random(0x434f4e4355525245L ^ threadIndex ^ benchmark.payloadMiB).nextBytes(payload);

      if (HibernateRepositoryBenchmark.FILESYSTEM.equals(benchmark.backend)) {
        sharedRepository = openFilesystemRepository(benchmark.sharedRepositoryDirectory);
        isolatedRepository =
            openFilesystemRepository(benchmark.isolatedRepositoryDirectories[threadIndex]);
        return;
      }

      provider = new HibernateSessionFactoryProvider(benchmark.hibernateProperties("validate"));
      sharedRepository =
          HibernateRepository.create(provider.getSessionFactory(), benchmark.sharedRepositoryName);
      isolatedRepository =
          HibernateRepository.create(
              provider.getSessionFactory(), benchmark.isolatedRepositoryNames[threadIndex]);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
      close(isolatedRepository);
      close(sharedRepository);
      isolatedRepository = null;
      sharedRepository = null;
      if (provider != null) {
        provider.close();
        provider = null;
      }
    }

    private ObjectId publish(
        Repository repository, String scope, ConcurrentStorageCounters counters) throws Exception {
      long invocation = sequence.incrementAndGet();
      encodeInvocation(payload, invocation);
      StorageOperationMetrics operationsBefore = operationMetrics(repository);
      StorageByteMetrics bytesBefore = byteMetrics(repository);
      StagingSpillMetrics spillsBefore = spillMetrics(repository);
      PackPublicationSelectionMetrics selectionsBefore = selectionMetrics(repository);

      ObjectId objectId;
      try (ObjectInserter inserter = repository.newObjectInserter()) {
        objectId = inserter.insert(Constants.OBJ_BLOB, payload);
        inserter.flush();
      }

      RefUpdate update =
          repository.updateRef(
              "refs/heads/benchmark/"
                  + scope
                  + "/thread-"
                  + threadIndex
                  + "/"
                  + invocation);
      update.setNewObjectId(objectId);
      update.disableRefLog();
      RefUpdate.Result result = update.update();
      if (result != RefUpdate.Result.NEW) {
        throw new IOException("Unexpected concurrent ref result " + result);
      }

      counters.capture(
          operationMetrics(repository).minus(operationsBefore),
          byteMetrics(repository).minus(bytesBefore),
          spillMetrics(repository).minus(spillsBefore),
          selectionMetrics(repository).minus(selectionsBefore),
          payload.length);
      return objectId;
    }

    private static StorageOperationMetrics operationMetrics(Repository repository) {
      return repository instanceof HibernateRepository hibernateRepository
          ? hibernateRepository.getStorageOperationMetrics()
          : StorageOperationMetrics.ZERO;
    }

    private static StorageByteMetrics byteMetrics(Repository repository) {
      return repository instanceof HibernateRepository hibernateRepository
          ? hibernateRepository.getStorageByteMetrics()
          : StorageByteMetrics.ZERO;
    }

    private static StagingSpillMetrics spillMetrics(Repository repository) {
      return repository instanceof HibernateRepository hibernateRepository
          ? hibernateRepository.getStagingSpillMetrics()
          : StagingSpillMetrics.ZERO;
    }

    private static PackPublicationSelectionMetrics selectionMetrics(Repository repository) {
      return repository instanceof HibernateRepository hibernateRepository
          ? hibernateRepository.getPackPublicationSelectionMetrics()
          : PackPublicationSelectionMetrics.ZERO;
    }

    private static void encodeInvocation(byte[] target, long invocation) {
      for (int index = 0; index < Long.BYTES; index++) {
        target[index] = (byte) (invocation >>> (index * Byte.SIZE));
      }
    }

    private static void close(Repository repository) {
      if (repository != null) {
        repository.close();
      }
    }
  }

  /** Storage coordination and physical payload costs for one measured publication. */
  @AuxCounters(AuxCounters.Type.EVENTS)
  @State(Scope.Thread)
  public static class ConcurrentStorageCounters {
    public long storageTransactions;
    public long repositoryLocks;
    public long repositoryLockAcquisitionMicros;
    public long repositoryLockHeldMicros;
    public long transactionDurationMicros;
    public long logicalGitBytes;
    public long temporaryFileBytesWritten;
    public long temporaryFileBytesRead;
    public long databasePayloadBytesWritten;
    public long writeAmplificationBasisPoints;
    public long memoryToFileSpills;
    public long spilledPrefixBytes;
    public long directPublicationSelections;
    public long prePersistedPublicationSelections;

    @Setup(Level.Invocation)
    public void reset() {
      storageTransactions = 0;
      repositoryLocks = 0;
      repositoryLockAcquisitionMicros = 0;
      repositoryLockHeldMicros = 0;
      transactionDurationMicros = 0;
      logicalGitBytes = 0;
      temporaryFileBytesWritten = 0;
      temporaryFileBytesRead = 0;
      databasePayloadBytesWritten = 0;
      writeAmplificationBasisPoints = 0;
      memoryToFileSpills = 0;
      spilledPrefixBytes = 0;
      directPublicationSelections = 0;
      prePersistedPublicationSelections = 0;
    }

    private void capture(
        StorageOperationMetrics operations,
        StorageByteMetrics bytes,
        StagingSpillMetrics spills,
        PackPublicationSelectionMetrics selections,
        long payloadBytes) {
      storageTransactions = operations.transactionsStarted();
      repositoryLocks = operations.repositoryLocksAcquired();
      repositoryLockAcquisitionMicros =
          TimeUnit.NANOSECONDS.toMicros(operations.repositoryLockAcquisitionNanos());
      repositoryLockHeldMicros =
          TimeUnit.NANOSECONDS.toMicros(operations.repositoryLockHeldNanos());
      transactionDurationMicros =
          TimeUnit.NANOSECONDS.toMicros(operations.transactionDurationNanos());
      logicalGitBytes = payloadBytes;
      temporaryFileBytesWritten = bytes.temporaryFileBytesWritten();
      temporaryFileBytesRead = bytes.temporaryFileBytesRead();
      databasePayloadBytesWritten = bytes.databasePayloadBytesWritten();
      writeAmplificationBasisPoints =
          payloadBytes == 0
              ? 0
              : Math.multiplyExact(databasePayloadBytesWritten, 10_000) / payloadBytes;
      memoryToFileSpills = spills.memoryToFileSpills();
      spilledPrefixBytes = spills.spilledPrefixBytes();
      directPublicationSelections = selections.directSelections();
      prePersistedPublicationSelections = selections.prePersistedSelections();
    }
  }
}
