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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.RefUpdate;
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
import org.openjdk.jmh.infra.ThreadParams;
import org.openjdk.jmh.runner.IterationType;

/**
 * Compares direct durable writes with one-, four- and eight-stripe bounded writer queues.
 *
 * <p>Every queued command still executes the complete JGit object insertion and ref update and its
 * future resolves only after those database transactions finish. The benchmark therefore measures
 * scheduling and serialization effects without changing the persistence contract. JMH sample-time
 * output supplies p50/p95/p99 latency while secondary counters expose queue delay, scheduling batch
 * size, storage transactions, lock time, payload amplification and adaptive-publication choices.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(1)
@Threads(4)
@State(Scope.Benchmark)
public class DurableWriteQueueBenchmark {

  static final String DIRECT = "direct";
  static final String QUEUE_1 = "queue-1";
  static final String QUEUE_4 = "queue-4";
  static final String QUEUE_8 = "queue-8";
  private static final int MAX_THREADS = 16;

  @Param({
    HibernateRepositoryBenchmark.POSTGRESQL,
    HibernateRepositoryBenchmark.POSTGRESQL_HIKARI
  })
  public String backend;

  @Param({DIRECT, QUEUE_1, QUEUE_4, QUEUE_8})
  public String executionMode;

  @Param({"64", "384"})
  public int payloadKiB;

  private final AtomicInteger measurementIteration = new AtomicInteger();
  private String sharedRepositoryName;
  private String[] isolatedRepositoryNames;
  private HibernateSessionFactoryProvider schemaProvider;
  private DurableStripedWriteQueue queue;
  private DatabaseTelemetryCollector telemetryCollector;
  private DatabaseTelemetrySnapshot iterationTelemetryBefore;

  @Setup(Level.Trial)
  public void setupTrial() throws Exception {
    suppressConnectionMetadataLogging();
    String trialId = Long.toHexString(System.nanoTime());
    sharedRepositoryName = "jmh-queue-shared-" + trialId;
    isolatedRepositoryNames = new String[MAX_THREADS];
    schemaProvider = new HibernateSessionFactoryProvider(properties("create-drop", "schema"));
    createRepository(sharedRepositoryName);
    for (int index = 0; index < isolatedRepositoryNames.length; index++) {
      isolatedRepositoryNames[index] = "jmh-queue-isolated-" + trialId + "-" + index;
      createRepository(isolatedRepositoryNames[index]);
    }
    int stripes = stripes(executionMode);
    if (stripes > 0) {
      queue = new DurableStripedWriteQueue(DurableStripedWriteQueue.Limits.benchmarkDefaults(stripes));
    }
    telemetryCollector = databaseTelemetryCollector();
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
  public void tearDownIteration(
      BenchmarkParams benchmarkParams, IterationParams iterationParams) throws IOException {
    try {
      if (iterationTelemetryBefore != null
          && iterationParams.getType() == IterationType.MEASUREMENT) {
        DatabaseTelemetrySnapshot after = telemetryCollector.capture();
        DatabaseTelemetryJson.appendNdjson(
            requiredTelemetryOutput(),
            new DatabaseTelemetryObservation(
                telemetryCoordinate(
                    benchmarkParams, measurementIteration.incrementAndGet()),
                iterationTelemetryBefore.deltaTo(after)));
      }
    } finally {
      iterationTelemetryBefore = null;
    }
  }

  @TearDown(Level.Trial)
  public void tearDownTrial() {
    iterationTelemetryBefore = null;
    if (telemetryCollector != null) {
      telemetryCollector.close();
      telemetryCollector = null;
    }
    if (queue != null) {
      queue.close();
      queue = null;
    }
    if (schemaProvider != null) {
      schemaProvider.close();
      schemaProvider = null;
    }
  }

  /** Publish through contending handles for the same logical repository. */
  @Benchmark
  public ObjectId publishToSameRepository(
      ThreadRepositoryState state, DurableQueueCounters counters) throws Exception {
    return execute(sharedRepositoryName, state.sharedRepository, state, counters, "shared");
  }

  /** Publish through independent repositories that can progress on different writer stripes. */
  @Benchmark
  public ObjectId publishToDifferentRepositories(
      ThreadRepositoryState state, DurableQueueCounters counters) throws Exception {
    return execute(
        isolatedRepositoryNames[state.threadIndex],
        state.isolatedRepository,
        state,
        counters,
        "isolated");
  }

  private ObjectId execute(
      String repositoryName,
      HibernateRepository repository,
      ThreadRepositoryState state,
      DurableQueueCounters counters,
      String scope)
      throws Exception {
    StorageOperationMetrics operationsBefore = repository.getStorageOperationMetrics();
    StorageByteMetrics bytesBefore = repository.getStorageByteMetrics();
    StagingSpillMetrics spillsBefore = repository.getStagingSpillMetrics();
    PackPublicationSelectionMetrics selectionsBefore =
        repository.getPackPublicationSelectionMetrics();

    ObjectId result;
    long queueWaitNanos = 0;
    int batchSize = 0;
    if (queue == null) {
      result = state.publish(repository, scope);
    } else {
      DurableStripedWriteQueue.Submission<ObjectId> submission =
          queue.submit(
              repositoryName,
              state.payload.length,
              () -> state.publish(repository, scope));
      result = submission.completion().get();
      queueWaitNanos = submission.queueWaitNanos();
      batchSize = submission.batchSize();
    }

    counters.capture(
        repository.getStorageOperationMetrics().minus(operationsBefore),
        repository.getStorageByteMetrics().minus(bytesBefore),
        repository.getStagingSpillMetrics().minus(spillsBefore),
        repository.getPackPublicationSelectionMetrics().minus(selectionsBefore),
        queueWaitNanos,
        batchSize,
        state.payload.length);
    return result;
  }

  private DatabaseTelemetryCollector databaseTelemetryCollector() {
    boolean enabled = Boolean.getBoolean(DatabaseTelemetryCollectors.ENABLED_PROPERTY);
    if (!enabled) {
      return DatabaseTelemetryCollectors.disabled(
          "postgresql", "disabled-by-configuration");
    }
    return DatabaseTelemetryCollectors.create(
        "postgresql",
        true,
        requiredProperty(HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY),
        requiredProperty(HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY),
        requiredProperty(HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY));
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

  private Map<String, String> telemetryCoordinate(
      BenchmarkParams benchmarkParams, int iteration) {
    String benchmark = benchmarkParams.getBenchmark();
    String benchmarkMethod =
        benchmark.substring(benchmark.lastIndexOf('.') + 1);
    String repositoryScope =
        switch (benchmarkMethod) {
          case "publishToSameRepository" -> "shared";
          case "publishToDifferentRepositories" -> "isolated";
          default ->
              throw new IllegalArgumentException(
                  "Unsupported write-queue benchmark method " + benchmarkMethod);
        };
    return Map.of(
        "backend", backend,
        "benchmarkMethod", benchmarkMethod,
        "databaseBackend", "postgresql",
        "executionMode", executionMode,
        "measurementIteration", Integer.toString(iteration),
        "payloadKiB", Integer.toString(payloadKiB),
        "poolSize", "2",
        "repositoryScope", repositoryScope,
        "stripes", Integer.toString(stripes(executionMode)),
        "threads", Integer.toString(benchmarkParams.getThreads()));
  }

  private void createRepository(String repositoryName) throws IOException {
    try (HibernateRepository repository =
        HibernateRepository.create(schemaProvider.getSessionFactory(), repositoryName)) {
      repository.create(true);
    }
  }

  private Properties properties(String ddlMode, String poolSuffix) {
    Properties properties = new Properties();
    properties.put("hibernate.hbm2ddl.auto", ddlMode);
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.format_sql", "false");
    properties.put("hibernate.search.enabled", "false");
    properties.put("hibernate.generate_statistics", "true");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    properties.put("hibernate.connection.url", requiredProperty(HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY));
    properties.put(
        "hibernate.connection.username",
        requiredProperty(HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY));
    properties.put(
        "hibernate.connection.password",
        requiredProperty(HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY));
    properties.put("hibernate.connection.driver_class", "org.postgresql.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
    if (HibernateRepositoryBenchmark.POSTGRESQL_HIKARI.equals(backend)) {
      properties.put("hibernate.hikari.maximumPoolSize", "2");
      properties.put("hibernate.hikari.minimumIdle", "0");
      properties.put("hibernate.hikari.connectionTimeout", "10000");
      properties.put(
          "hibernate.hikari.poolName",
          "jgit-queue-" + poolSuffix + "-" + Long.toHexString(System.nanoTime()));
    } else {
      properties.put("hibernate.connection.pool_size", "2");
    }
    return properties;
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

  private static int stripes(String mode) {
    return switch (mode) {
      case DIRECT -> 0;
      case QUEUE_1 -> 1;
      case QUEUE_4 -> 4;
      case QUEUE_8 -> 8;
      default -> throw new IllegalArgumentException("Unsupported queue mode: " + mode);
    };
  }

  /** Per-producer repository handles and deterministic payload. */
  @State(Scope.Thread)
  public static class ThreadRepositoryState {
    private final AtomicLong sequence = new AtomicLong();
    private int threadIndex;
    private byte[] payload;
    private HibernateSessionFactoryProvider provider;
    private HibernateRepository sharedRepository;
    private HibernateRepository isolatedRepository;

    @Setup(Level.Trial)
    public void setup(DurableWriteQueueBenchmark benchmark, ThreadParams threadParams)
        throws Exception {
      threadIndex = threadParams.getThreadIndex();
      if (threadIndex >= MAX_THREADS) {
        throw new IllegalStateException("Benchmark supports at most " + MAX_THREADS + " threads");
      }
      payload = new byte[Math.multiplyExact(benchmark.payloadKiB, 1024) + 257];
      new Random(0x51554555454c4f4eL ^ threadIndex).nextBytes(payload);
      provider =
          new HibernateSessionFactoryProvider(
              benchmark.properties("validate", "thread-" + threadIndex));
      sharedRepository =
          HibernateRepository.create(
              provider.getSessionFactory(), benchmark.sharedRepositoryName);
      isolatedRepository =
          HibernateRepository.create(
              provider.getSessionFactory(), benchmark.isolatedRepositoryNames[threadIndex]);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
      if (isolatedRepository != null) {
        isolatedRepository.close();
        isolatedRepository = null;
      }
      if (sharedRepository != null) {
        sharedRepository.close();
        sharedRepository = null;
      }
      if (provider != null) {
        provider.close();
        provider = null;
      }
    }

    private ObjectId publish(HibernateRepository repository, String scope) throws Exception {
      long invocation = sequence.incrementAndGet();
      encode(payload, invocation);
      ObjectId objectId;
      try (ObjectInserter inserter = repository.newObjectInserter()) {
        objectId = inserter.insert(Constants.OBJ_BLOB, payload);
        inserter.flush();
      }
      RefUpdate update =
          repository.updateRef(
              "refs/heads/queue/"
                  + scope
                  + "/thread-"
                  + threadIndex
                  + "/"
                  + invocation);
      update.setNewObjectId(objectId);
      update.disableRefLog();
      RefUpdate.Result updateResult = update.update();
      if (updateResult != RefUpdate.Result.NEW) {
        throw new IOException("Unexpected queued ref result " + updateResult);
      }
      return objectId;
    }

    private static void encode(byte[] target, long value) {
      for (int index = 0; index < Long.BYTES; index++) {
        target[index] = (byte) (value >>> (index * Byte.SIZE));
      }
    }
  }

  /** Per-operation storage and scheduling counters. */
  @AuxCounters(AuxCounters.Type.EVENTS)
  @State(Scope.Thread)
  public static class DurableQueueCounters {
    public long queueWaitMicros;
    public long schedulingBatchSize;
    public long storageTransactions;
    public long repositoryLocks;
    public long repositoryLockAcquisitionMicros;
    public long temporaryFileBytesWritten;
    public long temporaryFileBytesRead;
    public long databasePayloadBytesWritten;
    public long memoryToFileSpills;
    public long spilledPrefixBytes;
    public long directPublicationSelections;
    public long prePersistedPublicationSelections;
    public long logicalPayloadBytes;
    public long databaseWriteAmplificationBasisPoints;

    @Setup(Level.Invocation)
    public void reset() {
      queueWaitMicros = 0;
      schedulingBatchSize = 0;
      storageTransactions = 0;
      repositoryLocks = 0;
      repositoryLockAcquisitionMicros = 0;
      temporaryFileBytesWritten = 0;
      temporaryFileBytesRead = 0;
      databasePayloadBytesWritten = 0;
      memoryToFileSpills = 0;
      spilledPrefixBytes = 0;
      directPublicationSelections = 0;
      prePersistedPublicationSelections = 0;
      logicalPayloadBytes = 0;
      databaseWriteAmplificationBasisPoints = 0;
    }

    private void capture(
        StorageOperationMetrics operations,
        StorageByteMetrics bytes,
        StagingSpillMetrics spills,
        PackPublicationSelectionMetrics selections,
        long waitNanos,
        int batchSize,
        long payloadBytes) {
      queueWaitMicros = TimeUnit.NANOSECONDS.toMicros(waitNanos);
      schedulingBatchSize = batchSize;
      storageTransactions = operations.transactionsStarted();
      repositoryLocks = operations.repositoryLocksAcquired();
      repositoryLockAcquisitionMicros =
          TimeUnit.NANOSECONDS.toMicros(operations.repositoryLockAcquisitionNanos());
      temporaryFileBytesWritten = bytes.temporaryFileBytesWritten();
      temporaryFileBytesRead = bytes.temporaryFileBytesRead();
      databasePayloadBytesWritten = bytes.databasePayloadBytesWritten();
      memoryToFileSpills = spills.memoryToFileSpills();
      spilledPrefixBytes = spills.spilledPrefixBytes();
      directPublicationSelections = selections.directSelections();
      prePersistedPublicationSelections = selections.prePersistedSelections();
      logicalPayloadBytes = payloadBytes;
      databaseWriteAmplificationBasisPoints =
          payloadBytes == 0 ? 0 : Math.multiplyExact(databasePayloadBytesWritten, 10_000) / payloadBytes;
    }
  }
}
