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
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateStorageSettings;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackChunkEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.PackPublicationSelectionMetrics;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StagingSpillMetrics;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageByteMetrics;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
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

/**
 * Compares the stateful and shared-transaction stateless chunk writers at explicit payload sizes.
 *
 * <p>Each invocation receives a fresh schema so large samples do not accumulate in the database.
 * Payload creation, schema setup, close/reopen verification and schema cleanup are outside measured
 * time. The teardown streams the object through JGit and verifies type, size and SHA-256, avoiding a
 * second full-size heap copy. Run with JMH's {@code gc} profiler to capture allocation, GC count and
 * GC time together with the secondary storage counters exposed here.
 *
 * <p>Every benchmark label pins the corresponding production mode explicitly. This prevents the
 * evidence-based {@code auto} default from turning a future stateful reference measurement into an
 * accidental second stateless measurement.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 5)
@Fork(1)
@Threads(1)
@State(Scope.Thread)
public class LargePackJdbcBatchBenchmark {

  static final String STATEFUL_BATCHING_DISABLED = "stateful-batching-disabled";
  static final String STATEFUL_BATCHING = "stateful-batching";
  static final String STATEFUL_BATCHING_REWRITE = "stateful-batching-rewrite";
  static final String STATELESS = "stateless";
  static final String LOCAL_TESTCONTAINERS = "local-testcontainers";
  private static final int STREAM_BUFFER_BYTES = 128 * 1024;

  private final AtomicInteger invocationCounter = new AtomicInteger();

  @Param({
    STATEFUL_BATCHING_DISABLED,
    STATEFUL_BATCHING,
    STATEFUL_BATCHING_REWRITE,
    STATELESS
  })
  public String writeMode;

  @Param({
    HibernateRepositoryBenchmark.POSTGRESQL,
    HibernateRepositoryBenchmark.POSTGRESQL_HIKARI
  })
  public String backend;

  /** Overridden to 16, 128 and 512 by the full threshold profile. */
  @Param({"16"})
  public int payloadMiB;

  /** Bounded one-MiB chunk arrays retained and submitted per writer flush. */
  @Param({"8"})
  public int chunkBatchSize;

  /** Result label distinguishing local Testcontainers from an external deployment. */
  @Param({LOCAL_TESTCONTAINERS})
  public String deployment;

  private HibernateSessionFactoryProvider provider;
  private HibernateRepository repository;
  private Statistics statistics;
  private byte[] payload;
  private byte[] expectedSha256;
  private String repositoryName;
  private ObjectId publishedObjectId;

  @Setup(Level.Trial)
  public void setupTrial() throws Exception {
    payload = new byte[Math.addExact(Math.multiplyExact(payloadMiB, 1024 * 1024), 257)];
    new Random(0x4a444243L ^ payloadMiB).nextBytes(payload);
    expectedSha256 = MessageDigest.getInstance("SHA-256").digest(payload);
  }

  @Setup(Level.Invocation)
  public void setupInvocation() throws Exception {
    repositoryName =
        "jmh-large-pack-"
            + backend
            + "-"
            + writeMode
            + "-"
            + payloadMiB
            + "-batch-"
            + chunkBatchSize
            + "-"
            + invocationCounter.incrementAndGet()
            + "-"
            + Long.toHexString(System.nanoTime());
    provider = new HibernateSessionFactoryProvider(properties());
    statistics = provider.getSessionFactory().getStatistics();
    repository = HibernateRepository.create(provider.getSessionFactory(), repositoryName);
    repository.create(true);
    publishedObjectId = null;
    statistics.clear();
    JdbcBatchMetricsSessionEventListener.resetCurrentThread();
  }

  @TearDown(Level.Invocation)
  public void tearDownInvocation() throws Exception {
    try {
      if (repository != null) {
        repository.close();
        repository = null;
      }
      if (publishedObjectId != null) {
        verifyReopenedObject();
      }
    } finally {
      if (provider != null) {
        provider.close();
        provider = null;
        statistics = null;
      }
    }
  }

  @TearDown(Level.Trial)
  public void tearDownTrial() {
    payload = null;
    expectedSha256 = null;
  }

  @Benchmark
  public ObjectId publishLargePack(LargePackCounters counters) throws Exception {
    StorageByteMetrics bytesBefore = repository.getStorageByteMetrics();
    StagingSpillMetrics spillsBefore = repository.getStagingSpillMetrics();
    PackPublicationSelectionMetrics selectionsBefore =
        repository.getPackPublicationSelectionMetrics();

    try (ObjectInserter inserter = repository.newObjectInserter()) {
      publishedObjectId = inserter.insert(Constants.OBJ_BLOB, payload);
      inserter.flush();
    }

    StorageByteMetrics bytes = repository.getStorageByteMetrics().minus(bytesBefore);
    StagingSpillMetrics spills = repository.getStagingSpillMetrics().minus(spillsBefore);
    PackPublicationSelectionMetrics selections =
        repository.getPackPublicationSelectionMetrics().minus(selectionsBefore);
    JdbcBatchMetricsSessionEventListener.Snapshot jdbcEvents =
        JdbcBatchMetricsSessionEventListener.snapshotCurrentThread();
    counters.capture(
        jdbcEvents,
        statistics,
        bytes,
        spills,
        selections,
        payload.length,
        chunkBatchSize);
    return publishedObjectId;
  }

  private Properties properties() {
    Properties properties = new Properties();
    String jdbcUrl =
        requiredSystemProperty(HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY);
    if (STATEFUL_BATCHING_REWRITE.equals(writeMode)) {
      jdbcUrl = appendJdbcParameter(jdbcUrl, "reWriteBatchedInserts", "true");
    }
    properties.put("hibernate.connection.url", jdbcUrl);
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
    properties.put("hibernate.search.enabled", "false");
    properties.put("hibernate.generate_statistics", "true");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    properties.put(
        "hibernate.session.events.auto", JdbcBatchMetricsSessionEventListener.class.getName());
    properties.put(
        HibernateStorageSettings.PACK_CHUNK_BATCH_SIZE, Integer.toString(chunkBatchSize));
    properties.put(
        HibernateStorageSettings.JDBC_BATCH_SIZE,
        STATEFUL_BATCHING_DISABLED.equals(writeMode)
            ? "0"
            : Integer.toString(chunkBatchSize));
    properties.put(
        HibernateStorageSettings.PACK_CHUNK_WRITER,
        STATELESS.equals(writeMode)
            ? HibernateStorageSettings.STATELESS_CHUNK_WRITER
            : HibernateStorageSettings.STATEFUL_CHUNK_WRITER);
    if (HibernateRepositoryBenchmark.POSTGRESQL_HIKARI.equals(backend)) {
      properties.put("hibernate.hikari.maximumPoolSize", "4");
      properties.put("hibernate.hikari.minimumIdle", "0");
      properties.put("hibernate.hikari.connectionTimeout", "10000");
      properties.put("hibernate.hikari.poolName", "jgit-large-pack-" + repositoryName);
    } else {
      properties.put("hibernate.connection.pool_size", "4");
    }
    return properties;
  }

  private void verifyReopenedObject() throws Exception {
    try (HibernateRepository reopened =
        HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      ObjectLoader loader = reopened.open(publishedObjectId);
      if (loader.getType() != Constants.OBJ_BLOB) {
        throw new IllegalStateException(
            "Reopened object type mismatch: expected blob, got " + loader.getType());
      }
      if (loader.getSize() != payload.length) {
        throw new IllegalStateException(
            "Reopened object size mismatch: expected "
                + payload.length
                + ", got "
                + loader.getSize());
      }
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[STREAM_BUFFER_BYTES];
      try (InputStream input = loader.openStream()) {
        int count;
        while ((count = input.read(buffer)) >= 0) {
          if (count > 0) {
            digest.update(buffer, 0, count);
          }
        }
      }
      if (!Arrays.equals(expectedSha256, digest.digest())) {
        throw new IllegalStateException("Reopened payload SHA-256 mismatch");
      }
    }
  }

  private static String appendJdbcParameter(String jdbcUrl, String name, String value) {
    return jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + name + "=" + value;
  }

  private static String requiredSystemProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "Missing PostgreSQL benchmark system property "
              + name
              + "; run through the large-pack-jdbc-batch profile");
    }
    return value;
  }

  /** Low-level JDBC, ORM and byte-amplification counts for one large publication. */
  @AuxCounters(AuxCounters.Type.EVENTS)
  @State(Scope.Thread)
  public static class LargePackCounters {
    public long configuredChunkBatchSize;
    public long jdbcBatchExecutions;
    public long jdbcStatementExecutions;
    public long preparedStatements;
    public long connections;
    public long flushes;
    public long packEntityInserts;
    public long chunkEntityInserts;
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
      configuredChunkBatchSize = 0;
      jdbcBatchExecutions = 0;
      jdbcStatementExecutions = 0;
      preparedStatements = 0;
      connections = 0;
      flushes = 0;
      packEntityInserts = 0;
      chunkEntityInserts = 0;
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
        JdbcBatchMetricsSessionEventListener.Snapshot jdbcEvents,
        Statistics statistics,
        StorageByteMetrics bytes,
        StagingSpillMetrics spills,
        PackPublicationSelectionMetrics selections,
        long payloadBytes,
        int chunkBatchSize) {
      configuredChunkBatchSize = chunkBatchSize;
      jdbcBatchExecutions = jdbcEvents.batchExecutions();
      jdbcStatementExecutions = jdbcEvents.statementExecutions();
      preparedStatements = statistics.getPrepareStatementCount();
      connections = statistics.getConnectCount();
      flushes = statistics.getFlushCount();
      packEntityInserts =
          statistics.getEntityStatistics(GitPackEntity.class.getName()).getInsertCount();
      chunkEntityInserts =
          statistics.getEntityStatistics(GitPackChunkEntity.class.getName()).getInsertCount();
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
