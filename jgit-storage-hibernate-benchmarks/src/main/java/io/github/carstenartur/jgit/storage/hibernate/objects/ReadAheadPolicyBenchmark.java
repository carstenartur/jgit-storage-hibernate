/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageByteMetrics;
import java.nio.ByteBuffer;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectInserter;
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
 * Measures sequential, random and short pack-channel reads with one, four and sixteen chunks of
 * read-ahead.
 *
 * <p>The benchmark directly exercises the package-private channel used by production object reads.
 * Fixture creation and pack-ID discovery stay outside measured invocations. Secondary results expose
 * database query count, fetched and consumed bytes, overfetch and logical-to-physical amplification.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 5)
@Fork(1)
@Threads(1)
@State(Scope.Thread)
public class ReadAheadPolicyBenchmark {

  static final String HSQLDB = "hsqldb";
  static final String POSTGRESQL = "postgresql";
  static final String POSTGRESQL_HIKARI = "postgresql-hikari";
  static final String POSTGRESQL_URL_PROPERTY = "jgit.storage.benchmark.postgresql.url";
  static final String POSTGRESQL_USER_PROPERTY = "jgit.storage.benchmark.postgresql.user";
  static final String POSTGRESQL_PASSWORD_PROPERTY = "jgit.storage.benchmark.postgresql.password";

  private static final int PAYLOAD_BYTES = 20 * 1024 * 1024 + 257;
  private static final int SEQUENTIAL_BUFFER_BYTES = 64 * 1024;
  private static final int RANDOM_READ_BYTES = 4 * 1024;
  private static final int RANDOM_READS = 32;

  @Param({HSQLDB, POSTGRESQL, POSTGRESQL_HIKARI})
  public String backend;

  @Param({"1", "4", "16"})
  public int readAheadChunks;

  private final AtomicInteger invocation = new AtomicInteger();
  private final ByteBuffer sequentialBuffer = ByteBuffer.allocate(SEQUENTIAL_BUFFER_BYTES);
  private final ByteBuffer randomBuffer = ByteBuffer.allocate(RANDOM_READ_BYTES);
  private HibernateSessionFactoryProvider provider;
  private HibernateRepository repository;
  private StorageByteCounters counters;
  private Statistics statistics;
  private long packId;
  private long packSize;

  @Setup(Level.Trial)
  public void setupTrial() throws Exception {
    String name = "jmh-read-ahead-" + backend + "-" + Long.toHexString(System.nanoTime());
    provider = new HibernateSessionFactoryProvider(properties(name));
    repository = HibernateRepository.create(provider.getSessionFactory(), name);
    repository.create(true);
    byte[] payload = new byte[PAYLOAD_BYTES];
    new Random(0x5245414441484541L).nextBytes(payload);
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      inserter.insert(Constants.OBJ_BLOB, payload);
      inserter.flush();
    }

    Object[] row;
    try (var session = provider.getSessionFactory().openSession()) {
      row =
          session
              .createQuery(
                  "SELECT p.id, p.fileSize FROM GitPackEntity p "
                      + "WHERE p.repositoryName = :repo AND p.packExtension = :extension "
                      + "AND p.committed = true ORDER BY p.id DESC",
                  Object[].class)
              .setParameter("repo", name)
              .setParameter("extension", "pack")
              .setMaxResults(1)
              .getSingleResult();
    }
    packId = ((Number) row[0]).longValue();
    packSize = ((Number) row[1]).longValue();
    counters = StorageByteCounters.from(provider.getSessionFactory());
    statistics = provider.getSessionFactory().getStatistics();
  }

  @TearDown(Level.Trial)
  public void tearDownTrial() {
    if (repository != null) {
      repository.close();
      repository = null;
    }
    if (provider != null) {
      provider.close();
      provider = null;
    }
  }

  /** Read the complete chunked pack sequentially. */
  @Benchmark
  public long sequentialRead(ReadAheadCounters result) throws Exception {
    StorageByteMetrics before = counters.snapshot();
    statistics.clear();
    long logicalBytes = 0;
    try (var channel = channel()) {
      while (true) {
        sequentialBuffer.clear();
        int count = channel.read(sequentialBuffer);
        if (count < 0) {
          break;
        }
        logicalBytes += count;
      }
    }
    result.capture(counters.snapshot().minus(before), statistics, logicalBytes);
    return logicalBytes;
  }

  /** Perform deterministic non-sequential reads across the full pack. */
  @Benchmark
  public long randomReads(ReadAheadCounters result) throws Exception {
    StorageByteMetrics before = counters.snapshot();
    statistics.clear();
    long logicalBytes = 0;
    Random positions = new Random(0x52414e444f4dL ^ invocation.incrementAndGet());
    try (var channel = channel()) {
      for (int index = 0; index < RANDOM_READS; index++) {
        long bound = Math.max(1, packSize - RANDOM_READ_BYTES);
        long position = Math.floorMod(positions.nextLong(), bound);
        channel.position(position);
        randomBuffer.clear();
        int count = channel.read(randomBuffer);
        if (count > 0) {
          logicalBytes += count;
        }
      }
    }
    result.capture(counters.snapshot().minus(before), statistics, logicalBytes);
    return logicalBytes;
  }

  /** Read a small prefix and close immediately so unused prefetched bytes become visible. */
  @Benchmark
  public long shortRead(ReadAheadCounters result) throws Exception {
    StorageByteMetrics before = counters.snapshot();
    statistics.clear();
    long logicalBytes;
    try (var channel = channel()) {
      sequentialBuffer.clear();
      logicalBytes = Math.max(0, channel.read(sequentialBuffer));
    }
    result.capture(counters.snapshot().minus(before), statistics, logicalBytes);
    return logicalBytes;
  }

  private ReadAheadHibernateObjDatabase.ReadAheadChunkedReadableChannel channel() {
    var channel =
        new ReadAheadHibernateObjDatabase.ReadAheadChunkedReadableChannel(
            provider.getSessionFactory(), packId, packSize, counters);
    channel.setReadAheadBytes(
        Math.multiplyExact(readAheadChunks - 1, HibernateObjDatabase.PACK_CHUNK_SIZE));
    return channel;
  }

  private Properties properties(String databaseName) {
    Properties properties = new Properties();
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.format_sql", "false");
    properties.put("hibernate.search.enabled", "false");
    properties.put("hibernate.generate_statistics", "true");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    switch (backend) {
      case HSQLDB -> {
        properties.put("hibernate.connection.url", "jdbc:hsqldb:mem:" + databaseName);
        properties.put("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver");
        properties.put("hibernate.dialect", "org.hibernate.dialect.HSQLDialect");
        properties.put("hibernate.connection.pool_size", "4");
      }
      case POSTGRESQL, POSTGRESQL_HIKARI -> {
        properties.put("hibernate.connection.url", requiredProperty(POSTGRESQL_URL_PROPERTY));
        properties.put("hibernate.connection.username", requiredProperty(POSTGRESQL_USER_PROPERTY));
        properties.put("hibernate.connection.password", requiredProperty(POSTGRESQL_PASSWORD_PROPERTY));
        properties.put("hibernate.connection.driver_class", "org.postgresql.Driver");
        properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
        if (POSTGRESQL_HIKARI.equals(backend)) {
          properties.put("hibernate.hikari.maximumPoolSize", "4");
          properties.put("hibernate.hikari.minimumIdle", "0");
          properties.put("hibernate.hikari.connectionTimeout", "10000");
          properties.put("hibernate.hikari.poolName", "jgit-read-ahead-" + databaseName);
        } else {
          properties.put("hibernate.connection.pool_size", "4");
        }
      }
      default -> throw new IllegalArgumentException("Unsupported backend: " + backend);
    }
    return properties;
  }

  private static String requiredProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing benchmark system property " + name);
    }
    return value;
  }

  /** Physical-byte and query attribution for one measured read pattern. */
  @AuxCounters(AuxCounters.Type.EVENTS)
  @State(Scope.Thread)
  public static class ReadAheadCounters {
    public long databaseQueries;
    public long databasePayloadBytesRead;
    public long readAheadBytesFetched;
    public long readAheadBytesConsumed;
    public long readAheadOverfetchBytes;
    public long logicalBytesRead;
    public long physicalReadAmplificationBasisPoints;

    @Setup(Level.Invocation)
    public void reset() {
      databaseQueries = 0;
      databasePayloadBytesRead = 0;
      readAheadBytesFetched = 0;
      readAheadBytesConsumed = 0;
      readAheadOverfetchBytes = 0;
      logicalBytesRead = 0;
      physicalReadAmplificationBasisPoints = 0;
    }

    private void capture(StorageByteMetrics bytes, Statistics statistics, long logicalBytes) {
      databaseQueries = statistics.getQueryExecutionCount();
      databasePayloadBytesRead = bytes.databasePayloadBytesRead();
      readAheadBytesFetched = bytes.readAheadBytesFetched();
      readAheadBytesConsumed = bytes.readAheadBytesConsumed();
      readAheadOverfetchBytes = bytes.readAheadOverfetchBytes();
      logicalBytesRead = logicalBytes;
      physicalReadAmplificationBasisPoints =
          logicalBytes == 0 ? 0 : Math.multiplyExact(readAheadBytesFetched, 10_000) / logicalBytes;
    }
  }
}
