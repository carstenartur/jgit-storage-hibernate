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
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
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

/** Compares portable Hibernate JDBC batching for one non-compressible multi-chunk pack publication. */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 5)
@Fork(1)
@Threads(1)
@State(Scope.Thread)
public class LargePackJdbcBatchBenchmark {

  static final String DISABLED = "disabled";
  static final String ENABLED = "enabled";
  private static final int PAYLOAD_SIZE = 12 * 1024 * 1024 + 257;

  private final AtomicInteger invocationCounter = new AtomicInteger();

  @Param({DISABLED, ENABLED})
  public String batchingMode;

  private HibernateSessionFactoryProvider provider;
  private HibernateRepository repository;
  private Statistics statistics;
  private byte[] payload;

  @Setup(Level.Trial)
  public void setupTrial() {
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
    properties.put("hibernate.connection.pool_size", "4");
    properties.put("hibernate.generate_statistics", "true");
    properties.put(
        "hibernate.session.events.auto", JdbcBatchMetricsSessionEventListener.class.getName());
    if (DISABLED.equals(batchingMode)) {
      properties.put(HibernateStorageSettings.JDBC_BATCH_SIZE, "0");
    }

    provider = new HibernateSessionFactoryProvider(properties);
    statistics = provider.getSessionFactory().getStatistics();
    payload = new byte[PAYLOAD_SIZE];
    new Random(0x4a444243).nextBytes(payload);
  }

  @Setup(Level.Invocation)
  public void setupInvocation() throws Exception {
    String repositoryName =
        "jmh-large-pack-"
            + batchingMode
            + "-"
            + invocationCounter.incrementAndGet()
            + "-"
            + Long.toHexString(System.nanoTime());
    repository = HibernateRepository.create(provider.getSessionFactory(), repositoryName);
    repository.create(true);
    statistics.clear();
    JdbcBatchMetricsSessionEventListener.resetCurrentThread();
  }

  @TearDown(Level.Invocation)
  public void tearDownInvocation() {
    if (repository != null) {
      repository.close();
      repository = null;
    }
  }

  @TearDown(Level.Trial)
  public void tearDownTrial() {
    if (provider != null) {
      provider.close();
      provider = null;
      statistics = null;
    }
  }

  @Benchmark
  public ObjectId publishTwelveMiBPack(LargePackCounters counters) throws Exception {
    ObjectId objectId;
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      objectId = inserter.insert(Constants.OBJ_BLOB, payload);
      inserter.flush();
    }

    JdbcBatchMetricsSessionEventListener.Snapshot jdbcEvents =
        JdbcBatchMetricsSessionEventListener.snapshotCurrentThread();
    counters.jdbcBatchExecutions = jdbcEvents.batchExecutions();
    counters.jdbcStatementExecutions = jdbcEvents.statementExecutions();
    counters.preparedStatements = statistics.getPrepareStatementCount();
    counters.connections = statistics.getConnectCount();
    counters.flushes = statistics.getFlushCount();
    counters.packEntityInserts =
        statistics.getEntityStatistics(GitPackEntity.class.getName()).getInsertCount();
    counters.chunkEntityInserts =
        statistics.getEntityStatistics(GitPackChunkEntity.class.getName()).getInsertCount();
    return objectId;
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

  /** Low-level JDBC and ORM write counts for the measured large-pack publication. */
  @AuxCounters(AuxCounters.Type.EVENTS)
  @State(Scope.Thread)
  public static class LargePackCounters {
    public long jdbcBatchExecutions;
    public long jdbcStatementExecutions;
    public long preparedStatements;
    public long connections;
    public long flushes;
    public long packEntityInserts;
    public long chunkEntityInserts;

    @Setup(Level.Invocation)
    public void reset() {
      jdbcBatchExecutions = 0;
      jdbcStatementExecutions = 0;
      preparedStatements = 0;
      connections = 0;
      flushes = 0;
      packEntityInserts = 0;
      chunkEntityInserts = 0;
    }
  }
}
