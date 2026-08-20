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
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.hibernate.SessionFactory;
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
 * Measures one pack-layout candidate with 1, 4 or 16 concurrent database workers.
 *
 * <p>Unlike the single-thread layout fixture, this benchmark owns exactly one shared schema and one
 * thread-safe {@link SessionFactory} per JMH trial. Worker threads use independent repository and
 * pack rows, so the result represents database, connection-pool and allocation pressure rather than
 * an artificial race between concurrent {@code hbm2ddl create-drop} bootstraps. Read workers retain
 * one immutable fixture each; write workers publish and delete one unique pack per invocation.
 *
 * <p>The benchmark remains isolated from the production reader and writer. Candidate rows exist only
 * in the disposable benchmark schema, and the current one-MiB/256-KiB production layout remains
 * authoritative.
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class PackStorageLayoutConcurrencyBenchmark {

  public static final String WRITE = "write";
  public static final String SEQUENTIAL_READ = "sequential-read";
  public static final String SHORT_READ = "short-read";
  public static final String RANDOM_READ = "random-read";

  private static final int SHORT_READ_BYTES = 64 * 1024;
  private static final int RANDOM_READ_BYTES = 4 * 1024;
  private static final int RANDOM_READ_COUNT = 32;
  private static final int SEQUENTIAL_READ_BYTES = 128 * 1024;
  private static final byte[] DETERMINISTIC_PATTERN = deterministicPattern();

  @Param({HibernateRepositoryBenchmark.POSTGRESQL})
  public String backend;

  @Param({WRITE})
  public String operation;

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

  @Param({"1"})
  public int concurrency;

  @Param({LargePackJdbcBatchBenchmark.LOCAL_TESTCONTAINERS})
  public String deployment;

  private HibernateSessionFactoryProvider provider;
  private SessionFactory sessionFactory;
  private PackStorageLayoutCandidate candidate;
  private long payloadBytes;
  private List<String> repositoryNames;
  private List<Long> fixturePackIds;
  private AtomicInteger workerSequence;
  private AtomicLong writeSequence;
  private ThreadLocal<Integer> workerIndex;
  private ThreadLocal<Long> lastWrittenPackId;

  @Setup(Level.Trial)
  public void setupTrial() {
    requireOperation(operation);
    requireConcurrency(concurrency);
    if (!HibernateRepositoryBenchmark.POSTGRESQL.equals(backend)) {
      throw new IllegalArgumentException("Concurrency evidence currently requires PostgreSQL");
    }
    payloadBytes = Math.multiplyExact((long) payloadKiB, 1024L);
    candidate =
        new PackStorageLayoutCandidate(
            Math.multiplyExact(chunkKiB, 1024),
            Math.multiplyExact(inlineKiB, 1024),
            Math.multiplyExact((long) retainedMiB, 1024L * 1024L),
            Math.multiplyExact(readAheadKiB, 1024));
    provider = new HibernateSessionFactoryProvider(properties());
    sessionFactory = provider.getSessionFactory();
    repositoryNames = new ArrayList<>(concurrency);
    fixturePackIds = new ArrayList<>(concurrency);
    String prefix = "jmh-pack-layout-concurrency-" + Long.toUnsignedString(System.nanoTime(), 36);
    for (int index = 0; index < concurrency; index++) {
      repositoryNames.add(prefix + "-worker-" + index);
      fixturePackIds.add(null);
    }
    persistRepositoryLifecycles();
    if (!WRITE.equals(operation)) {
      for (int index = 0; index < concurrency; index++) {
        fixturePackIds.set(index, persistLayout(index, "fixture"));
      }
    }
    workerSequence = new AtomicInteger();
    writeSequence = new AtomicLong();
    workerIndex =
        ThreadLocal.withInitial(
            () -> {
              int assigned = workerSequence.getAndIncrement();
              if (assigned >= concurrency) {
                throw new IllegalStateException(
                    "JMH created more worker threads than configured concurrency " + concurrency);
              }
              return assigned;
            });
    lastWrittenPackId = new ThreadLocal<>();
  }

  @Setup(Level.Invocation)
  public void setupInvocation() {
    lastWrittenPackId.remove();
    JdbcBatchMetricsSessionEventListener.resetCurrentThread();
  }

  @TearDown(Level.Invocation)
  public void tearDownInvocation() {
    Long packId = lastWrittenPackId.get();
    if (packId != null) {
      deletePack(packId);
      lastWrittenPackId.remove();
    }
  }

  @TearDown(Level.Trial)
  public void tearDownTrial() {
    repositoryNames = List.of();
    fixturePackIds = List.of();
    if (workerIndex != null) {
      workerIndex.remove();
      workerIndex = null;
    }
    if (lastWrittenPackId != null) {
      lastWrittenPackId.remove();
      lastWrittenPackId = null;
    }
    sessionFactory = null;
    if (provider != null) {
      provider.close();
      provider = null;
    }
  }

  /** Execute one concurrent worker operation and capture its bounded structural evidence. */
  @Benchmark
  public long execute(ConcurrencyCounters counters) {
    int index = workerIndex.get();
    OperationResult result =
        switch (operation) {
          case WRITE -> writeCandidate(index);
          case SEQUENTIAL_READ -> readSequentially(index);
          case SHORT_READ -> readShortRange(index);
          case RANDOM_READ -> readRandomRanges(index);
          default -> throw new IllegalStateException("Validated operation was " + operation);
        };
    counters.capture(this, result);
    return result.value();
  }

  private OperationResult writeCandidate(int index) {
    String packName = "write-" + index + "-" + writeSequence.incrementAndGet();
    Long packId = persistLayout(index, packName);
    lastWrittenPackId.set(packId);
    return new OperationResult(packId, payloadBytes, payloadBytes, 0);
  }

  private OperationResult readSequentially(int index) {
    try (LayoutReader reader = new LayoutReader(requiredFixturePackId(index))) {
      long checksum = 0;
      long position = 0;
      while (position < payloadBytes) {
        int length = Math.toIntExact(Math.min(SEQUENTIAL_READ_BYTES, payloadBytes - position));
        checksum += reader.read(position, length);
        position += length;
      }
      return reader.result(checksum);
    }
  }

  private OperationResult readShortRange(int index) {
    int length = Math.toIntExact(Math.min(SHORT_READ_BYTES, payloadBytes));
    long position = Math.max(0L, (payloadBytes - length) / 3L);
    try (LayoutReader reader = new LayoutReader(requiredFixturePackId(index))) {
      return reader.result(reader.read(position, length));
    }
  }

  private OperationResult readRandomRanges(int index) {
    int length = Math.toIntExact(Math.min(RANDOM_READ_BYTES, payloadBytes));
    long bound = payloadBytes - length + 1L;
    long state = 0x4a4749545f434f4eL ^ index;
    long checksum = 0;
    try (LayoutReader reader = new LayoutReader(requiredFixturePackId(index))) {
      for (int read = 0; read < RANDOM_READ_COUNT; read++) {
        state = state * 6364136223846793005L + 1442695040888963407L;
        long position = Math.floorMod(state, bound);
        checksum += reader.read(position, length);
      }
      return reader.result(checksum);
    }
  }

  private Long persistLayout(int worker, String packName) {
    String repositoryName = repositoryNames.get(worker);
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
            int length = Math.toIntExact(Math.min(candidate.chunkBytes(), payloadBytes - position));
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

  private void persistRepositoryLifecycles() {
    sessionFactory.inTransaction(
        session -> {
          Instant now = Instant.now();
          for (String repositoryName : repositoryNames) {
            GitRepositoryLifecycleEntity lifecycle = new GitRepositoryLifecycleEntity();
            lifecycle.setRepositoryName(repositoryName);
            lifecycle.setCreatedAt(now);
            session.persist(lifecycle);
          }
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
    properties.put("hibernate.search.enabled", "false");
    properties.put("hibernate.generate_statistics", "false");
    properties.put(
        "hibernate.session.events.auto", JdbcBatchMetricsSessionEventListener.class.getName());
    properties.put(
        HibernateStorageSettings.JDBC_BATCH_SIZE,
        Integer.toString(candidate.chunksPerBatch()));
    properties.put(HibernateStorageSettings.ORDER_INSERTS, Boolean.TRUE.toString());
    properties.put("hibernate.hikari.maximumPoolSize", Integer.toString(Math.max(4, concurrency + 2)));
    properties.put("hibernate.hikari.minimumIdle", "0");
    properties.put("hibernate.hikari.connectionTimeout", "30000");
    properties.put(
        "hibernate.hikari.poolName", "jgit-pack-layout-concurrency-" + concurrency + "-" + chunkKiB);
    return properties;
  }

  private Long requiredFixturePackId(int index) {
    Long packId = fixturePackIds.get(index);
    if (packId == null) {
      throw new IllegalStateException("Read worker " + index + " has no persisted fixture");
    }
    return packId;
  }

  private static void requireOperation(String value) {
    if (!WRITE.equals(value)
        && !SEQUENTIAL_READ.equals(value)
        && !SHORT_READ.equals(value)
        && !RANDOM_READ.equals(value)) {
      throw new IllegalArgumentException("Unsupported concurrency operation " + value);
    }
  }

  private static void requireConcurrency(int value) {
    if (value != 1 && value != 4 && value != 16) {
      throw new IllegalArgumentException("concurrency must be 1, 4 or 16 but was " + value);
    }
  }

  private static String requiredSystemProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing benchmark system property " + name);
    }
    return value;
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
        throw new IllegalStateException("Inline concurrency fixture is corrupt");
      }
      fetchedBytes += data.length;
      return new Window(0, data);
    }

    private Window loadChunkWindow(long position, int length) {
      int firstChunk = Math.toIntExact(position / candidate.chunkBytes());
      long desiredEnd =
          Math.min(
              payloadBytes,
              Math.addExact(position, Math.addExact((long) length, candidate.readAheadBytes())));
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
      return new OperationResult(checksum, fetchedBytes, logicalConsumedBytes, overfetchBytes);
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
      consumed = new BitSet(data.length);
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
      long value, long fetchedBytes, long consumedBytes, long overfetchBytes) {}

  /** Structural and byte evidence attached to every concurrent JMH sample. */
  @AuxCounters(AuxCounters.Type.EVENTS)
  @State(Scope.Thread)
  public static class ConcurrencyCounters {
    public long configuredConcurrency;
    public long configuredChunkBytes;
    public long configuredInlineThresholdBytes;
    public long configuredRetainedBudgetBytes;
    public long actualRetainedChunkBytes;
    public long configuredReadAheadBytes;
    public long readAheadChunks;
    public long chunksPerBatch;
    public long logicalPayloadBytes;
    public long chunkRows;
    public long jdbcBatchExecutions;
    public long jdbcStatementExecutions;
    public long databasePayloadBytes;
    public long logicalBytesConsumed;
    public long overfetchBytes;

    @Setup(Level.Invocation)
    public void reset() {
      configuredConcurrency = 0;
      configuredChunkBytes = 0;
      configuredInlineThresholdBytes = 0;
      configuredRetainedBudgetBytes = 0;
      actualRetainedChunkBytes = 0;
      configuredReadAheadBytes = 0;
      readAheadChunks = 0;
      chunksPerBatch = 0;
      logicalPayloadBytes = 0;
      chunkRows = 0;
      jdbcBatchExecutions = 0;
      jdbcStatementExecutions = 0;
      databasePayloadBytes = 0;
      logicalBytesConsumed = 0;
      overfetchBytes = 0;
    }

    private void capture(
        PackStorageLayoutConcurrencyBenchmark benchmark, OperationResult result) {
      JdbcBatchMetricsSessionEventListener.Snapshot jdbc =
          JdbcBatchMetricsSessionEventListener.snapshotCurrentThread();
      configuredConcurrency = benchmark.concurrency;
      configuredChunkBytes = benchmark.candidate.chunkBytes();
      configuredInlineThresholdBytes = benchmark.candidate.inlineThresholdBytes();
      configuredRetainedBudgetBytes = benchmark.candidate.retainedPayloadBudgetBytes();
      actualRetainedChunkBytes = benchmark.candidate.retainedChunkBytes();
      configuredReadAheadBytes = benchmark.candidate.readAheadBytes();
      readAheadChunks = benchmark.candidate.readAheadChunks();
      chunksPerBatch = benchmark.candidate.chunksPerBatch();
      logicalPayloadBytes = benchmark.payloadBytes;
      chunkRows = benchmark.candidate.chunkCount(benchmark.payloadBytes);
      jdbcBatchExecutions = jdbc.batchExecutions();
      jdbcStatementExecutions = jdbc.statementExecutions();
      databasePayloadBytes = result.fetchedBytes();
      logicalBytesConsumed = result.consumedBytes();
      overfetchBytes = result.overfetchBytes();
    }
  }
}
