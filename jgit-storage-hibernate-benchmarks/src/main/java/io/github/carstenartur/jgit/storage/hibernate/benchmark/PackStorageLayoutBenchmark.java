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
import io.github.carstenartur.jgit.storage.hibernate.entity.GitRepositoryLifecycleEntity;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.SessionFactory;
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
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;

/**
 * Measures candidate chunk sizes and inline thresholds without changing the production format.
 *
 * <p>Every trial uses the real Core Hibernate entities in a disposable schema. Candidate chunk size,
 * inline threshold, retained writer budget and read-ahead budget are benchmark parameters only. No
 * candidate row is opened through the production repository implementation, because production
 * readers intentionally continue to interpret rows without explicit layout metadata as the current
 * one-MiB format. This benchmark establishes the evidence required before a versioned format is
 * introduced.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1)
@Threads(1)
@State(Scope.Thread)
public class PackStorageLayoutBenchmark {

  public static final String WRITE = "write";
  public static final String SEQUENTIAL_READ = "sequential-read";
  public static final String SHORT_READ = "short-read";
  public static final String RANDOM_READ = "random-read";
  public static final String SQL_SERVER = "sqlserver";

  public static final String SQL_SERVER_URL_PROPERTY =
      "jgit.storage.benchmark.sqlserver.url";
  public static final String SQL_SERVER_USER_PROPERTY =
      "jgit.storage.benchmark.sqlserver.user";
  public static final String SQL_SERVER_PASSWORD_PROPERTY =
      "jgit.storage.benchmark.sqlserver.password";
  static final String CONNECTION_PROPERTIES_FILE_PROPERTY =
      "jgit.storage.benchmark.connection-properties-file";

  private static final int SHORT_READ_BYTES = 64 * 1024;
  private static final int RANDOM_READ_BYTES = 4 * 1024;
  private static final int RANDOM_READ_COUNT = 32;
  private static final int SEQUENTIAL_READ_BYTES = 128 * 1024;
  private static final byte[] DETERMINISTIC_PATTERN = deterministicPattern();

  private final AtomicInteger invocationCounter = new AtomicInteger();

  @Param({WRITE})
  public String operation;

  @Param({HibernateRepositoryBenchmark.HSQLDB})
  public String backend;

  @Param({"1024"})
  public int payloadKiB;

  @Param({"1024"})
  public int chunkKiB;

  @Param({"256"})
  public int inlineKiB;

  @Param({"16"})
  public int retainedMiB;

  @Param({"1024"})
  public int readAheadKiB;

  @Param({LargePackJdbcBatchBenchmark.LOCAL_TESTCONTAINERS})
  public String deployment;

  private HibernateSessionFactoryProvider provider;
  private SessionFactory sessionFactory;
  private Statistics statistics;
  private PackStorageLayoutCandidate candidate;
  private long payloadBytes;
  private String repositoryName;
  private Long fixturePackId;
  private Long invocationPackId;
  private DatabaseTelemetryCollector telemetryCollector;
  private DatabaseTelemetrySnapshot invocationTelemetryBefore;

  @Setup(Level.Trial)
  public void setupTrial() {
    suppressConnectionMetadataLogging();
    requireOperation(operation);
    payloadBytes = Math.multiplyExact((long) payloadKiB, 1024L);
    candidate =
        new PackStorageLayoutCandidate(
            Math.multiplyExact(chunkKiB, 1024),
            Math.multiplyExact(inlineKiB, 1024),
            Math.multiplyExact((long) retainedMiB, 1024L * 1024L),
            Math.multiplyExact(readAheadKiB, 1024));
    repositoryName =
        "jmh-pack-layout-"
            + backend
            + "-"
            + Long.toUnsignedString(System.nanoTime(), 36);
    provider = new HibernateSessionFactoryProvider(properties());
    sessionFactory = provider.getSessionFactory();
    statistics = sessionFactory.getStatistics();
    persistRepositoryLifecycle();
    if (!WRITE.equals(operation)) {
      fixturePackId = persistLayout("fixture");
    }
    telemetryCollector = databaseTelemetryCollector();
  }

  @Setup(Level.Invocation)
  public void setupInvocation(IterationParams iterationParams) {
    invocationPackId = null;
    invocationTelemetryBefore = null;
    statistics.clear();
    JdbcBatchMetricsSessionEventListener.resetCurrentThread();
    if (telemetryCollector.enabled()
        && iterationParams.getType() == IterationType.MEASUREMENT) {
      invocationTelemetryBefore = telemetryCollector.capture();
    }
  }

  @TearDown(Level.Invocation)
  public void tearDownInvocation(IterationParams iterationParams) throws IOException {
    try {
      if (invocationTelemetryBefore != null
          && iterationParams.getType() == IterationType.MEASUREMENT) {
        DatabaseTelemetrySnapshot after = telemetryCollector.capture();
        DatabaseTelemetryJson.appendNdjson(
            requiredTelemetryOutput(),
            new DatabaseTelemetryObservation(
                telemetryCoordinate(), invocationTelemetryBefore.deltaTo(after)));
      }
    } finally {
      invocationTelemetryBefore = null;
      if (invocationPackId != null) {
        deletePack(invocationPackId);
        invocationPackId = null;
      }
    }
  }

  @TearDown(Level.Trial)
  public void tearDownTrial() {
    fixturePackId = null;
    invocationTelemetryBefore = null;
    if (telemetryCollector != null) {
      telemetryCollector.close();
      telemetryCollector = null;
    }
    statistics = null;
    sessionFactory = null;
    if (provider != null) {
      provider.close();
      provider = null;
    }
  }

  /** Execute the selected write or read shape and expose structural counters. */
  @Benchmark
  public long execute(LayoutCounters counters) {
    OperationResult result =
        switch (operation) {
          case WRITE -> writeCandidate();
          case SEQUENTIAL_READ -> readSequentially();
          case SHORT_READ -> readShortRange();
          case RANDOM_READ -> readRandomRanges();
          default -> throw new IllegalStateException("Validated operation was " + operation);
        };
    counters.capture(this, result);
    return result.value();
  }

  private OperationResult writeCandidate() {
    String packName = "write-" + invocationCounter.incrementAndGet();
    invocationPackId = persistLayout(packName);
    return new OperationResult(
        invocationPackId,
        payloadBytes,
        payloadBytes,
        0,
        invocationPackId);
  }

  private OperationResult readSequentially() {
    try (LayoutReader reader = new LayoutReader(requiredFixturePackId())) {
      long checksum = 0;
      long position = 0;
      while (position < payloadBytes) {
        int length =
            Math.toIntExact(Math.min(SEQUENTIAL_READ_BYTES, payloadBytes - position));
        checksum += reader.read(position, length);
        position += length;
      }
      return reader.result(checksum);
    }
  }

  private OperationResult readShortRange() {
    int length = Math.toIntExact(Math.min(SHORT_READ_BYTES, payloadBytes));
    long position = Math.max(0L, (payloadBytes - length) / 3L);
    try (LayoutReader reader = new LayoutReader(requiredFixturePackId())) {
      long checksum = reader.read(position, length);
      return reader.result(checksum);
    }
  }

  private OperationResult readRandomRanges() {
    int length = Math.toIntExact(Math.min(RANDOM_READ_BYTES, payloadBytes));
    long bound = payloadBytes - length + 1L;
    long state = 0x4a4749545f4c4159L;
    long checksum = 0;
    try (LayoutReader reader = new LayoutReader(requiredFixturePackId())) {
      for (int index = 0; index < RANDOM_READ_COUNT; index++) {
        state = state * 6364136223846793005L + 1442695040888963407L;
        long position = Math.floorMod(state, bound);
        checksum += reader.read(position, length);
      }
      return reader.result(checksum);
    }
  }

  private Long persistLayout(String packName) {
    return sessionFactory.fromTransaction(
        session -> {
          Instant now = Instant.now();
          boolean inline = candidate.inline(payloadBytes);
          GitPackEntity pack = new GitPackEntity();
          pack.setRepositoryName(repositoryName);
          pack.setPackName(packName);
          pack.setPackExtension("pack");
          pack.setFileSize(payloadBytes);
          pack.setCommitted(true);
          pack.setCreatedAt(now);
          pack.setCommittedAt(now);
          pack.setWriteToken(null);
          pack.setWriteLeaseUntil(null);
          pack.setData(inline ? deterministicBytes(0, Math.toIntExact(payloadBytes)) : null);
          session.persist(pack);
          session.flush();
          Long packId = pack.getId();
          if (inline) {
            return packId;
          }

          long position = 0;
          int chunkIndex = 0;
          int pending = 0;
          while (position < payloadBytes) {
            int length =
                Math.toIntExact(Math.min(candidate.chunkBytes(), payloadBytes - position));
            GitPackChunkEntity chunk = new GitPackChunkEntity();
            chunk.setPackId(packId);
            chunk.setChunkIndex(chunkIndex);
            chunk.setChunkSize(length);
            chunk.setData(deterministicBytes(position, length));
            session.persist(chunk);
            position += length;
            chunkIndex++;
            pending++;
            if (pending == candidate.chunksPerBatch()) {
              session.flush();
              session.clear();
              pending = 0;
            }
          }
          if (pending > 0) {
            session.flush();
          }
          return packId;
        });
  }

  private void persistRepositoryLifecycle() {
    sessionFactory.inTransaction(
        session -> {
          GitRepositoryLifecycleEntity lifecycle = new GitRepositoryLifecycleEntity();
          lifecycle.setRepositoryName(repositoryName);
          lifecycle.setCreatedAt(Instant.now());
          session.persist(lifecycle);
        });
  }

  private void deletePack(Long packId) {
    sessionFactory.inTransaction(
        session -> {
          session
              .createMutationQuery(
                  "DELETE FROM GitPackChunkEntity c WHERE c.packId = :packId")
              .setParameter("packId", packId)
              .executeUpdate();
          session
              .createMutationQuery("DELETE FROM GitPackEntity p WHERE p.id = :packId")
              .setParameter("packId", packId)
              .executeUpdate();
        });
  }

  private DatabaseTelemetryCollector databaseTelemetryCollector() {
    boolean enabled = Boolean.getBoolean(DatabaseTelemetryCollectors.ENABLED_PROPERTY);
    return switch (backend) {
      case HibernateRepositoryBenchmark.POSTGRESQL ->
          DatabaseTelemetryCollectors.create(
              backend,
              enabled,
              requiredSystemProperty(HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY),
              requiredSystemProperty(HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY),
              requiredSystemProperty(
                  HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY));
      case SQL_SERVER ->
          DatabaseTelemetryCollectors.create(
              backend,
              enabled,
              requiredSystemProperty(SQL_SERVER_URL_PROPERTY),
              requiredSystemProperty(SQL_SERVER_USER_PROPERTY),
              requiredSystemProperty(SQL_SERVER_PASSWORD_PROPERTY));
      default -> DatabaseTelemetryCollectors.disabled(backend, "unsupported-backend");
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

  private Map<String, String> telemetryCoordinate() {
    return Map.of(
        "backend", backend,
        "deployment", deployment,
        "operation", operation,
        "payloadKiB", Integer.toString(payloadKiB),
        "chunkKiB", Integer.toString(chunkKiB),
        "inlineKiB", Integer.toString(inlineKiB),
        "retainedMiB", Integer.toString(retainedMiB),
        "readAheadKiB", Integer.toString(readAheadKiB));
  }

  private Properties properties() {
    Properties properties = new Properties();
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.format_sql", "false");
    properties.put("hibernate.search.enabled", "false");
    properties.put("hibernate.generate_statistics", "true");
    properties.put(
        "hibernate.session.events.auto", JdbcBatchMetricsSessionEventListener.class.getName());
    properties.put(
        HibernateStorageSettings.JDBC_BATCH_SIZE,
        Integer.toString(candidate.chunksPerBatch()));
    properties.put(HibernateStorageSettings.ORDER_INSERTS, Boolean.TRUE.toString());

    switch (backend) {
      case HibernateRepositoryBenchmark.HSQLDB -> {
        properties.put("hibernate.connection.url", "jdbc:hsqldb:mem:" + repositoryName);
        properties.put("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver");
        properties.put("hibernate.dialect", "org.hibernate.dialect.HSQLDialect");
        properties.put("hibernate.connection.pool_size", "4");
      }
      case HibernateRepositoryBenchmark.POSTGRESQL -> {
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
        properties.put("hibernate.connection.pool_size", "4");
      }
      case SQL_SERVER -> {
        properties.put(
            "hibernate.connection.url", requiredSystemProperty(SQL_SERVER_URL_PROPERTY));
        properties.put(
            "hibernate.connection.username", requiredSystemProperty(SQL_SERVER_USER_PROPERTY));
        properties.put(
            "hibernate.connection.password", requiredSystemProperty(SQL_SERVER_PASSWORD_PROPERTY));
        properties.put(
            "hibernate.connection.driver_class", "com.microsoft.sqlserver.jdbc.SQLServerDriver");
        properties.put("hibernate.dialect", "org.hibernate.dialect.SQLServerDialect");
        properties.put("hibernate.connection.pool_size", "4");
      }
      default -> throw new IllegalArgumentException("Unsupported layout backend " + backend);
    }
    return properties;
  }

  private Long requiredFixturePackId() {
    if (fixturePackId == null) {
      throw new IllegalStateException("Read operation has no persisted layout fixture");
    }
    return fixturePackId;
  }

  private static void requireOperation(String value) {
    if (!WRITE.equals(value)
        && !SEQUENTIAL_READ.equals(value)
        && !SHORT_READ.equals(value)
        && !RANDOM_READ.equals(value)) {
      throw new IllegalArgumentException("Unsupported layout operation " + value);
    }
  }

  private static String requiredSystemProperty(String name) {
    String value = System.getProperty(name);
    if (value != null && !value.isBlank()) {
      return value;
    }

    String connectionPropertiesFile =
        System.getProperty(CONNECTION_PROPERTIES_FILE_PROPERTY);
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

  private static byte[] deterministicPattern() {
    byte[] pattern = new byte[64 * 1024];
    int value = 0x4c41594f;
    for (int index = 0; index < pattern.length; index++) {
      value = value * 1664525 + 1013904223;
      pattern[index] = (byte) (value >>> 24);
    }
    return pattern;
  }

  private static byte[] deterministicBytes(long offset, int length) {
    byte[] result = new byte[length];
    int written = 0;
    while (written < length) {
      int patternOffset = Math.toIntExact((offset + written) % DETERMINISTIC_PATTERN.length);
      int count = Math.min(length - written, DETERMINISTIC_PATTERN.length - patternOffset);
      System.arraycopy(DETERMINISTIC_PATTERN, patternOffset, result, written, count);
      written += count;
    }
    return result;
  }

  private final class LayoutReader implements AutoCloseable {
    private final Long packId;
    private Window window;
    private long fetchedBytes;
    private long logicalConsumedBytes;
    private long overfetchBytes;
    private boolean closed;

    private LayoutReader(Long packId) {
      this.packId = packId;
    }

    private long read(long position, int length) {
      if (closed) {
        throw new IllegalStateException("Layout reader is closed");
      }
      if (position < 0 || length < 0 || position > payloadBytes - length) {
        throw new IllegalArgumentException("Read range is outside the payload");
      }
      ensureWindow(position, length);
      int offset = Math.toIntExact(position - window.start());
      long checksum = 0;
      for (int index = 0; index < length; index++) {
        checksum = checksum * 31 + Byte.toUnsignedInt(window.data()[offset + index]);
      }
      window.markConsumed(offset, length);
      logicalConsumedBytes += length;
      return checksum;
    }

    private void ensureWindow(long position, int length) {
      long requestedEnd = position + length;
      if (window != null && window.start() <= position && window.endExclusive() >= requestedEnd) {
        return;
      }
      discardWindow();
      window = candidate.inline(payloadBytes) ? loadInlineWindow() : loadChunkWindow(position, length);
      if (window.start() > position || window.endExclusive() < requestedEnd) {
        throw new IllegalStateException("Loaded layout window does not cover the requested range");
      }
    }

    private Window loadInlineWindow() {
      Object[] row =
          sessionFactory.fromTransaction(
              session ->
                  session
                      .createSelectionQuery(
                          "SELECT p.data, p.fileSize FROM GitPackEntity p WHERE p.id = :packId",
                          Object[].class)
                      .setParameter("packId", packId)
                      .getSingleResult());
      byte[] data = (byte[]) row[0];
      long declaredSize = ((Number) row[1]).longValue();
      if (data == null || data.length != declaredSize || declaredSize != payloadBytes) {
        throw new IllegalStateException("Inline benchmark layout is corrupt");
      }
      fetchedBytes += data.length;
      return new Window(0, data);
    }

    private Window loadChunkWindow(long position, int length) {
      int firstChunk = Math.toIntExact(position / candidate.chunkBytes());
      long desiredEnd =
          Math.min(
              payloadBytes,
              Math.addExact(
                  position,
                  Math.addExact((long) length, (long) candidate.readAheadBytes())));
      int lastChunk = Math.toIntExact((desiredEnd - 1L) / candidate.chunkBytes());
      List<Object[]> rows =
          sessionFactory.fromTransaction(
              session ->
                  session
                      .createSelectionQuery(
                          "SELECT c.chunkIndex, c.data, c.chunkSize FROM GitPackChunkEntity c "
                              + "WHERE c.packId = :packId AND c.chunkIndex BETWEEN :first AND :last "
                              + "ORDER BY c.chunkIndex",
                          Object[].class)
                      .setParameter("packId", packId)
                      .setParameter("first", firstChunk)
                      .setParameter("last", lastChunk)
                      .getResultList());
      int expectedChunk = firstChunk;
      int totalBytes = 0;
      for (Object[] row : rows) {
        int actualChunk = ((Number) row[0]).intValue();
        byte[] data = (byte[]) row[1];
        int declaredSize = ((Number) row[2]).intValue();
        if (actualChunk != expectedChunk
            || data.length != declaredSize
            || declaredSize < 1
            || declaredSize > candidate.chunkBytes()) {
          throw new IllegalStateException(
              "Invalid candidate chunk " + actualChunk + " for pack " + packId);
        }
        totalBytes = Math.addExact(totalBytes, data.length);
        expectedChunk++;
      }
      if (expectedChunk <= lastChunk) {
        throw new IllegalStateException(
            "Missing candidate chunk " + expectedChunk + " for pack " + packId);
      }
      byte[] combined = new byte[totalBytes];
      int targetOffset = 0;
      for (Object[] row : rows) {
        byte[] data = (byte[]) row[1];
        System.arraycopy(data, 0, combined, targetOffset, data.length);
        targetOffset += data.length;
      }
      fetchedBytes += combined.length;
      return new Window(Math.multiplyExact((long) firstChunk, candidate.chunkBytes()), combined);
    }

    private OperationResult result(long checksum) {
      close();
      return new OperationResult(
          checksum, fetchedBytes, logicalConsumedBytes, overfetchBytes, packId);
    }

    private void discardWindow() {
      if (window != null) {
        overfetchBytes += window.unconsumedBytes();
        window = null;
      }
    }

    @Override
    public void close() {
      if (!closed) {
        closed = true;
        discardWindow();
      }
    }
  }

  private static final class Window {
    private final long start;
    private final byte[] data;
    private final BitSet consumed;
    private int consumedBytes;

    private Window(long start, byte[] data) {
      this.start = start;
      this.data = data;
      this.consumed = new BitSet(data.length);
    }

    private long start() {
      return start;
    }

    private long endExclusive() {
      return start + data.length;
    }

    private byte[] data() {
      return data;
    }

    private void markConsumed(int offset, int length) {
      int end = offset + length;
      int cursor = offset;
      while (cursor < end) {
        int nextConsumed = consumed.nextSetBit(cursor);
        if (nextConsumed < 0 || nextConsumed >= end) {
          consumedBytes += end - cursor;
          break;
        }
        consumedBytes += nextConsumed - cursor;
        cursor = consumed.nextClearBit(nextConsumed);
      }
      consumed.set(offset, end);
    }

    private long unconsumedBytes() {
      return data.length - consumedBytes;
    }
  }

  private record OperationResult(
      long value,
      long fetchedBytes,
      long consumedBytes,
      long overfetchBytes,
      Long packId) {}

  /** Structural, JDBC and byte-amplification evidence for one layout operation. */
  @AuxCounters(AuxCounters.Type.EVENTS)
  @State(Scope.Thread)
  public static class LayoutCounters {
    public long configuredChunkBytes;
    public long configuredInlineThresholdBytes;
    public long configuredRetainedBudgetBytes;
    public long actualRetainedChunkBytes;
    public long configuredReadAheadBytes;
    public long readAheadChunks;
    public long chunksPerBatch;
    public long proposedLayoutVersion;
    public long logicalPayloadBytes;
    public long packRows;
    public long chunkRows;
    public long jdbcBatchExecutions;
    public long jdbcStatementExecutions;
    public long preparedStatements;
    public long hibernateQueries;
    public long flushes;
    public long packEntityInserts;
    public long chunkEntityInserts;
    public long databasePayloadBytes;
    public long logicalBytesConsumed;
    public long overfetchBytes;

    @Setup(Level.Invocation)
    public void reset() {
      configuredChunkBytes = 0;
      configuredInlineThresholdBytes = 0;
      configuredRetainedBudgetBytes = 0;
      actualRetainedChunkBytes = 0;
      configuredReadAheadBytes = 0;
      readAheadChunks = 0;
      chunksPerBatch = 0;
      proposedLayoutVersion = 0;
      logicalPayloadBytes = 0;
      packRows = 0;
      chunkRows = 0;
      jdbcBatchExecutions = 0;
      jdbcStatementExecutions = 0;
      preparedStatements = 0;
      hibernateQueries = 0;
      flushes = 0;
      packEntityInserts = 0;
      chunkEntityInserts = 0;
      databasePayloadBytes = 0;
      logicalBytesConsumed = 0;
      overfetchBytes = 0;
    }

    private void capture(PackStorageLayoutBenchmark benchmark, OperationResult result) {
      PackStorageLayoutCandidate candidate = benchmark.candidate;
      JdbcBatchMetricsSessionEventListener.Snapshot jdbc =
          JdbcBatchMetricsSessionEventListener.snapshotCurrentThread();
      configuredChunkBytes = candidate.chunkBytes();
      configuredInlineThresholdBytes = candidate.inlineThresholdBytes();
      configuredRetainedBudgetBytes = candidate.retainedPayloadBudgetBytes();
      actualRetainedChunkBytes = candidate.retainedChunkBytes();
      configuredReadAheadBytes = candidate.readAheadBytes();
      readAheadChunks = candidate.readAheadChunks();
      chunksPerBatch = candidate.chunksPerBatch();
      proposedLayoutVersion = candidate.proposedLayoutVersion();
      logicalPayloadBytes = benchmark.payloadBytes;
      packRows = 1;
      chunkRows = candidate.chunkCount(benchmark.payloadBytes);
      jdbcBatchExecutions = jdbc.batchExecutions();
      jdbcStatementExecutions = jdbc.statementExecutions();
      preparedStatements = benchmark.statistics.getPrepareStatementCount();
      hibernateQueries = benchmark.statistics.getQueryExecutionCount();
      flushes = benchmark.statistics.getFlushCount();
      packEntityInserts =
          benchmark.statistics.getEntityStatistics(GitPackEntity.class.getName()).getInsertCount();
      chunkEntityInserts =
          benchmark.statistics
              .getEntityStatistics(GitPackChunkEntity.class.getName())
              .getInsertCount();
      databasePayloadBytes = result.fetchedBytes();
      logicalBytesConsumed = result.consumedBytes();
      overfetchBytes = result.overfetchBytes();
    }
  }
}
