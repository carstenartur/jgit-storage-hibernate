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
import io.github.carstenartur.jgit.storage.hibernate.entity.GitReflogEntity;
import io.github.carstenartur.jgit.storage.hibernate.refs.HibernateReflogReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.lib.ReflogEntry;
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
 * Compares legacy and selective reverse-reflog indexes on PostgreSQL and SQL Server.
 *
 * <p>Fixture creation uses direct prepared JDBC batches outside measured invocations so the result
 * isolates the production {@link HibernateReflogReader} query, entity materialization and
 * transaction boundary. The optimized shape uses the same 128-character key and full-ref residual
 * predicate as Core. The legacy shape retains only repository + descending identity ordering.
 */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Fork(1)
@Threads(1)
@State(Scope.Thread)
public class ReflogLookupBenchmark {

  public static final String POSTGRESQL = "postgresql";
  public static final String SQL_SERVER = "sqlserver";
  public static final String LEGACY_INDEX = "legacy-repository-id";
  public static final String REF_KEY_INDEX = "repository-ref-key-id";

  public static final String POSTGRESQL_URL_PROPERTY =
      "jgit.storage.benchmark.reflog.postgresql.url";
  public static final String POSTGRESQL_USER_PROPERTY =
      "jgit.storage.benchmark.reflog.postgresql.user";
  public static final String POSTGRESQL_PASSWORD_PROPERTY =
      "jgit.storage.benchmark.reflog.postgresql.password";
  public static final String SQL_SERVER_URL_PROPERTY =
      "jgit.storage.benchmark.reflog.sqlserver.url";
  public static final String SQL_SERVER_USER_PROPERTY =
      "jgit.storage.benchmark.reflog.sqlserver.user";
  public static final String SQL_SERVER_PASSWORD_PROPERTY =
      "jgit.storage.benchmark.reflog.sqlserver.password";

  private static final String REPOSITORY = "reflog-performance-repository";
  private static final String ZERO_ID = "0000000000000000000000000000000000000000";
  private static final int INSERT_BATCH = 1_000;

  @Param({POSTGRESQL, SQL_SERVER})
  public String backend;

  @Param({LEGACY_INDEX, REF_KEY_INDEX})
  public String indexMode;

  @Param({"10000"})
  public int rowCount;

  @Param({"100"})
  public int refCount;

  private HibernateSessionFactoryProvider provider;
  private HibernateReflogReader reader;
  private Statistics statistics;
  private String jdbcUrl;
  private String username;
  private String password;
  private String targetRef;

  @Setup(Level.Trial)
  public void setupTrial() throws Exception {
    resolveConnection();
    dropSchema();
    createSchema();
    populateFixture();
    provider = new HibernateSessionFactoryProvider(properties());
    statistics = provider.getSessionFactory().getStatistics();
    targetRef = refName(0);
    reader = new HibernateReflogReader(provider.getSessionFactory(), REPOSITORY, targetRef);
  }

  @Setup(Level.Invocation)
  public void setupInvocation() {
    statistics.clear();
  }

  @TearDown(Level.Trial)
  public void tearDownTrial() throws Exception {
    if (provider != null) {
      provider.close();
      provider = null;
    }
    dropSchema();
  }

  /** Read the newest entry for one ref among many repository-local refs. */
  @Benchmark
  public int lastEntry(ReflogCounters counters) throws Exception {
    ReflogEntry entry = reader.getLastEntry();
    if (entry == null) {
      throw new IllegalStateException("Expected a reflog entry for " + targetRef);
    }
    counters.capture(statistics, 1);
    return entry.getComment().hashCode() ^ entry.getNewId().hashCode();
  }

  /** Read the newest 100 entries for one ref among many repository-local refs. */
  @Benchmark
  public int lastHundred(ReflogCounters counters) throws Exception {
    List<ReflogEntry> entries = reader.getReverseEntries(100);
    int expected = Math.min(100, (rowCount + refCount - 1) / refCount);
    if (entries.size() != expected) {
      throw new IllegalStateException(
          "Expected " + expected + " reflog entries, got " + entries.size());
    }
    int checksum = entries.size();
    for (ReflogEntry entry : entries) {
      checksum = 31 * checksum + entry.getComment().hashCode();
      checksum = 31 * checksum + entry.getNewId().hashCode();
    }
    counters.capture(statistics, entries.size());
    return checksum;
  }

  private void resolveConnection() {
    switch (backend) {
      case POSTGRESQL -> {
        jdbcUrl = requiredProperty(POSTGRESQL_URL_PROPERTY);
        username = requiredProperty(POSTGRESQL_USER_PROPERTY);
        password = requiredProperty(POSTGRESQL_PASSWORD_PROPERTY);
      }
      case SQL_SERVER -> {
        jdbcUrl = requiredProperty(SQL_SERVER_URL_PROPERTY);
        username = requiredProperty(SQL_SERVER_USER_PROPERTY);
        password = requiredProperty(SQL_SERVER_PASSWORD_PROPERTY);
      }
      default -> throw new IllegalArgumentException("Unsupported reflog backend " + backend);
    }
  }

  private Properties properties() {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", jdbcUrl);
    properties.put("hibernate.connection.username", username);
    properties.put("hibernate.connection.password", password);
    properties.put("hibernate.hbm2ddl.auto", "none");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.generate_statistics", "true");
    properties.put("hibernate.search.enabled", "false");
    if (POSTGRESQL.equals(backend)) {
      properties.put("hibernate.connection.driver_class", "org.postgresql.Driver");
      properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
    } else {
      properties.put("hibernate.connection.driver_class", "com.microsoft.sqlserver.jdbc.SQLServerDriver");
      properties.put("hibernate.dialect", "org.hibernate.dialect.SQLServerDialect");
    }
    properties.put("hibernate.connection.pool_size", "2");
    return properties;
  }

  private void createSchema() throws Exception {
    try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
        Statement statement = connection.createStatement()) {
      if (POSTGRESQL.equals(backend)) {
        statement.execute(
            "create table git_reflog ("
                + "id bigint generated by default as identity not null primary key, "
                + "version bigint, repository_name varchar(255) not null, "
                + "ref_name varchar(1024) not null, ref_name_key varchar(128) not null, "
                + "old_id varchar(40), new_id varchar(40), who_name varchar(255), "
                + "who_email varchar(255), who_when timestamp(6) with time zone not null, "
                + "message varchar(2000))");
      } else {
        statement.execute(
            "create table git_reflog ("
                + "id bigint identity(1,1) not null constraint pk_reflog_benchmark primary key, "
                + "version bigint null, repository_name nvarchar(255) not null, "
                + "ref_name nvarchar(1024) not null, ref_name_key nvarchar(128) not null, "
                + "old_id varchar(40) null, new_id varchar(40) null, who_name nvarchar(255) null, "
                + "who_email nvarchar(255) null, who_when datetimeoffset(7) not null, "
                + "message nvarchar(2000) null)");
      }

      if (LEGACY_INDEX.equals(indexMode)) {
        statement.execute(
            "create index idx_reflog_benchmark on git_reflog (repository_name, id desc)");
      } else if (POSTGRESQL.equals(backend)) {
        statement.execute(
            "create index idx_reflog_benchmark on git_reflog "
                + "(repository_name, ref_name_key, id desc)");
      } else {
        statement.execute(
            "create index idx_reflog_benchmark on git_reflog "
                + "(repository_name, ref_name_key, id desc) include (ref_name)");
      }
    }
  }

  private void populateFixture() throws Exception {
    String sql =
        "insert into git_reflog "
            + "(version, repository_name, ref_name, ref_name_key, old_id, new_id, "
            + "who_name, who_email, who_when, message) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
    try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
        PreparedStatement insert = connection.prepareStatement(sql)) {
      connection.setAutoCommit(false);
      for (int index = 0; index < rowCount; index++) {
        String ref = refName(index % refCount);
        insert.setLong(1, 0L);
        insert.setString(2, REPOSITORY);
        insert.setString(3, ref);
        insert.setString(4, GitReflogEntity.refNameKey(ref));
        insert.setString(5, ZERO_ID);
        insert.setString(6, objectId(index));
        insert.setString(7, "Reflog benchmark");
        insert.setString(8, "reflog@example.invalid");
        insert.setObject(
            9,
            OffsetDateTime.ofInstant(
                Instant.ofEpochSecond(1_700_000_000L + index), ZoneOffset.UTC));
        insert.setString(10, "reflog entry " + index);
        insert.addBatch();
        if ((index + 1) % INSERT_BATCH == 0) {
          insert.executeBatch();
        }
      }
      if (rowCount % INSERT_BATCH != 0) {
        insert.executeBatch();
      }
      connection.commit();
    }
  }

  private void dropSchema() throws Exception {
    try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
        Statement statement = connection.createStatement()) {
      if (POSTGRESQL.equals(backend)) {
        statement.execute("drop table if exists git_reflog");
      } else {
        statement.execute(
            "if object_id('git_reflog', 'U') is not null drop table git_reflog");
      }
    }
  }

  private static String refName(int index) {
    return "refs/heads/benchmark-" + String.format("%04d", index);
  }

  private static String objectId(int index) {
    return String.format("%040x", index + 1L);
  }

  private static String requiredProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing reflog benchmark property " + name);
    }
    return value;
  }

  /** Database work attributed to one reverse-reflog lookup. */
  @AuxCounters(AuxCounters.Type.EVENTS)
  @State(Scope.Thread)
  public static class ReflogCounters {
    public long resultCount;
    public long queryExecutions;
    public long entityLoads;
    public long preparedStatements;
    public long transactions;

    @Setup(Level.Invocation)
    public void reset() {
      resultCount = 0;
      queryExecutions = 0;
      entityLoads = 0;
      preparedStatements = 0;
      transactions = 0;
    }

    private void capture(Statistics statistics, long results) {
      resultCount = results;
      queryExecutions = statistics.getQueryExecutionCount();
      entityLoads = statistics.getEntityLoadCount();
      preparedStatements = statistics.getPrepareStatementCount();
      transactions = statistics.getTransactionCount();
    }
  }
}
