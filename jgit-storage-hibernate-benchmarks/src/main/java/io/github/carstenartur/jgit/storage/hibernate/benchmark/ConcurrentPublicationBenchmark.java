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
 * <p>Each operation inserts one non-compressible payload just above the inline threshold and
 * publishes one unique ref. This intentionally measures both logical-pack and ref-publication
 * boundaries. Fixture construction, schema validation and repository opening remain outside the
 * measured interval.
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@Threads(4)
@State(Scope.Benchmark)
public class ConcurrentPublicationBenchmark {

  private static final int THREAD_COUNT = 4;
  private static final int PAYLOAD_SIZE = 256 * 1024 + 257;

  @Param({
    HibernateRepositoryBenchmark.FILESYSTEM,
    HibernateRepositoryBenchmark.HSQLDB,
    HibernateRepositoryBenchmark.POSTGRESQL,
    HibernateRepositoryBenchmark.POSTGRESQL_HIKARI
  })
  public String backend;

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
    isolatedRepositoryNames = new String[THREAD_COUNT];
    for (int index = 0; index < THREAD_COUNT; index++) {
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

  /** Publish through four independent handles contending for one repository lock row. */
  @Benchmark
  public ObjectId publishToSameRepository(
      ThreadRepositoryState state, ConcurrentStorageCounters counters) throws Exception {
    return state.publish(state.sharedRepository, "shared", counters);
  }

  /** Publish through four independent handles using four independent repository lock rows. */
  @Benchmark
  public ObjectId publishToDifferentRepositories(
      ThreadRepositoryState state, ConcurrentStorageCounters counters) throws Exception {
    return state.publish(state.isolatedRepository, "isolated", counters);
  }

  private void setupFilesystemRepositories() throws IOException {
    filesystemRoot = Files.createTempDirectory("jgit-concurrent-benchmark-");
    sharedRepositoryDirectory = filesystemRoot.resolve("shared.git");
    createFilesystemRepository(sharedRepositoryDirectory);
    isolatedRepositoryDirectories = new Path[THREAD_COUNT];
    for (int index = 0; index < THREAD_COUNT; index++) {
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
    properties.put("hibernate.connection.pool_size", "8");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");

    switch (backend) {
      case HibernateRepositoryBenchmark.HSQLDB -> {
        properties.put("hibernate.connection.url", "jdbc:hsqldb:mem:" + databaseName);
        properties.put("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver");
        properties.put("hibernate.dialect", "org.hibernate.dialect.HSQLDialect");
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
          properties.put("hibernate.hikari.maximumPoolSize", "8");
          properties.put("hibernate.hikari.minimumIdle", "1");
          properties.put("hibernate.hikari.connectionTimeout", "10000");
          properties.put("hibernate.hikari.poolName", "jgit-concurrent-" + databaseName);
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
      if (threadIndex >= THREAD_COUNT) {
        throw new IllegalStateException("Unexpected JMH thread index " + threadIndex);
      }
      payload = new byte[PAYLOAD_SIZE];
      new Random(0x434f4e4355525245L ^ threadIndex).nextBytes(payload);

      if (HibernateRepositoryBenchmark.FILESYSTEM.equals(benchmark.backend)) {
        sharedRepository = openFilesystemRepository(benchmark.sharedRepositoryDirectory);
        isolatedRepository =
            openFilesystemRepository(benchmark.isolatedRepositoryDirectories[threadIndex]);
        return;
      }

      provider =
          new HibernateSessionFactoryProvider(benchmark.hibernateProperties("validate"));
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
      StorageOperationMetrics before = metrics(repository);

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

      counters.capture(metrics(repository).minus(before));
      return objectId;
    }

    private static StorageOperationMetrics metrics(Repository repository) {
      return repository instanceof HibernateRepository hibernateRepository
          ? hibernateRepository.getStorageOperationMetrics()
          : StorageOperationMetrics.ZERO;
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

  /** Storage coordination costs for one measured publication operation. */
  @AuxCounters(AuxCounters.Type.EVENTS)
  @State(Scope.Thread)
  public static class ConcurrentStorageCounters {
    public long storageTransactions;
    public long repositoryLocks;
    public long repositoryLockAcquisitionMicros;
    public long repositoryLockHeldMicros;
    public long transactionDurationMicros;

    @Setup(Level.Invocation)
    public void reset() {
      storageTransactions = 0;
      repositoryLocks = 0;
      repositoryLockAcquisitionMicros = 0;
      repositoryLockHeldMicros = 0;
      transactionDurationMicros = 0;
    }

    private void capture(StorageOperationMetrics metrics) {
      storageTransactions = metrics.transactionsStarted();
      repositoryLocks = metrics.repositoryLocksAcquired();
      repositoryLockAcquisitionMicros =
          TimeUnit.NANOSECONDS.toMicros(metrics.repositoryLockAcquisitionNanos());
      repositoryLockHeldMicros =
          TimeUnit.NANOSECONDS.toMicros(metrics.repositoryLockHeldNanos());
      transactionDurationMicros =
          TimeUnit.NANOSECONDS.toMicros(metrics.transactionDurationNanos());
    }
  }
}
