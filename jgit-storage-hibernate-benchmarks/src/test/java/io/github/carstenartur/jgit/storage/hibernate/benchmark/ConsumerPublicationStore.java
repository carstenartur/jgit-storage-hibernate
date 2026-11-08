/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.search.SearchEntities;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageByteMetrics;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.hibernate.SessionFactory;

/** Disposable store shared only by the publication follow-up harness. */
final class ConsumerPublicationStore implements AutoCloseable {
  private static final List<String> DURABILITY_SETTINGS = List.of("fsync", "synchronous_commit", "full_page_writes");
  private final boolean search;
  private final boolean postgres;
  private final Properties settings = new Properties();
  private final List<HibernateRepository> repositories = new ArrayList<>();
  private final List<StorageByteMetrics> beforeBytes = new ArrayList<>();
  private HibernateSessionFactoryProvider provider;
  private DatabaseTelemetryCollector telemetry;
  private DatabaseTelemetrySnapshot beforeTelemetry;
  private Map<String, String> durability = Map.of();
  private Path indexRoot;
  private String schema;
  private String measurementUrl;
  private boolean databaseCreated;
  private boolean closed;

  ConsumerPublicationStore(boolean search) throws IOException {
    this.search = search;
    postgres = System.getProperty(BenchmarkConnectionProperties.CONNECTION_PROPERTIES_FILE_PROPERTY) != null;
    BenchmarkConnectionProperties.suppressConnectionMetadataLogging();
    settings.setProperty("hibernate.hbm2ddl.auto", "create-only");
    settings.setProperty("hibernate.generate_statistics", "true");
    settings.setProperty("hibernate.session.events.auto", JdbcBatchMetricsSessionEventListener.class.getName());
    settings.setProperty(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    settings.setProperty("hibernate.search.enabled", Boolean.toString(search));
    if (search) {
      indexRoot = Files.createTempDirectory("consumer-publication-lucene-");
      settings.setProperty("hibernate.search.backend.type", "lucene");
      settings.setProperty("hibernate.search.backend.directory.type", "local-filesystem");
      settings.setProperty("hibernate.search.backend.directory.root", indexRoot.toString());
      settings.setProperty("hibernate.search.indexing.plan.synchronization.strategy", "sync");
    }
    try {
      if (postgres) {
        settings.setProperty("hibernate.connection.url", BenchmarkConnectionProperties.requiredSystemProperty(
            HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY));
        settings.setProperty("hibernate.connection.username", BenchmarkConnectionProperties.requiredSystemProperty(
            HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY));
        settings.setProperty("hibernate.connection.password", BenchmarkConnectionProperties.requiredSystemProperty(
            HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY));
        settings.setProperty("hibernate.connection.driver_class", "org.postgresql.Driver");
        var connections = new Properties();
        try (var input = Files.newInputStream(Path.of(System.getProperty(
            BenchmarkConnectionProperties.CONNECTION_PROPERTIES_FILE_PROPERTY)))) {
          connections.load(input);
        }
        measurementUrl = connections.getProperty("jgit.storage.benchmark.postgresql.measurement-url",
            settings.getProperty("hibernate.connection.url"));
        schema = "consumer_publication_" + UUID.randomUUID().toString().replace("-", "");
        settings.setProperty("hibernate.default_schema", schema);
        databaseCommand("CREATE SCHEMA " + schema);
        databaseCreated = true;
      } else {
        settings.setProperty("hibernate.connection.url", "jdbc:hsqldb:mem:consumer-publication-" + UUID.randomUUID());
        settings.setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver");
        settings.setProperty("hibernate.connection.username", "SA");
        settings.setProperty("hibernate.connection.password", "");
        databaseCreated = true;
      }
      provider = provider(settings);
      telemetry = postgres ? DatabaseTelemetryCollectors.create("postgresql", true,
          settings.getProperty("hibernate.connection.url"),
          settings.getProperty("hibernate.connection.username"),
          settings.getProperty("hibernate.connection.password"))
          : DatabaseTelemetryCollectors.disabled("hsqldb", "Functional fixture; no native claims");
    } catch (IOException | RuntimeException failure) {
      try { close(); } catch (IOException | RuntimeException cleanup) { failure.addSuppressed(cleanup); }
      throw failure;
    }
  }

  private HibernateSessionFactoryProvider provider(Properties properties) {
    return search ? new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses())
        : new HibernateSessionFactoryProvider(properties);
  }

  HibernateRepository create(String name) throws IOException {
    var repository = HibernateRepository.create(sessionFactory(), name);
    repositories.add(repository);
    repository.create(true);
    return repository;
  }

  HibernateRepository repository(int index) { return repositories.get(index); }
  SessionFactory sessionFactory() { return provider.getSessionFactory(); }

  static void requireDurability(Map<String, String> settings) {
    for (String setting : DURABILITY_SETTINGS) {
      if (!"on".equals(settings.get(setting))) {
        throw new IllegalStateException("Native publication requires observed PostgreSQL " + setting + "=on");
      }
    }
  }

  private Map<String, String> readDurability() {
    try (var session = sessionFactory().openSession()) {
      var observed = session.doReturningWork(connection -> {
        Map<String, String> values = new LinkedHashMap<>();
        try (var statement = connection.createStatement()) {
          for (String setting : DURABILITY_SETTINGS) {
            try (var result = statement.executeQuery("SHOW " + setting)) {
              if (result.next()) values.put(setting, result.getString(1));
            }
          }
        }
        return values;
      });
      requireDurability(observed);
      return Map.copyOf(observed);
    }
  }

  void restart() throws IOException {
    var names = repositories.stream().map(HibernateRepository::getIdentifier).toList();
    closeProvider();
    var reopened = new Properties();
    reopened.putAll(settings);
    reopened.setProperty("hibernate.hbm2ddl.auto", "validate");
    if (measurementUrl != null) reopened.setProperty("hibernate.connection.url", measurementUrl);
    provider = provider(reopened);
    for (String name : names) {
      repositories.add(HibernateRepository.openExisting(sessionFactory(), name, request -> {}, () -> {}));
    }
  }

  void begin(boolean measured) {
    if (postgres) durability = readDurability(); // Same measured provider, outside statistics/WAL/timer.
    sessionFactory().getStatistics().clear();
    JdbcBatchMetricsSessionEventListener.resetCurrentThread();
    beforeBytes.clear();
    repositories.forEach(repository -> beforeBytes.add(repository.getStorageByteMetrics()));
    beforeTelemetry = measured && telemetry.enabled() ? telemetry.capture() : null;
  }

  void capture(ConsumerPublicationBenchmark.Counters counters, long workerBatches) {
    var statistics = sessionFactory().getStatistics();
    counters.preparedStatements = statistics.getPrepareStatementCount();
    counters.transactions = statistics.getTransactionCount();
    counters.jdbcBatches = workerBatches;
    counters.databaseBytesRead = 0;
    counters.databaseBytesWritten = 0;
    for (int index = 0; index < repositories.size(); index++) {
      var bytes = repositories.get(index).getStorageByteMetrics().minus(beforeBytes.get(index));
      counters.databaseBytesRead += bytes.databasePayloadBytesRead();
      counters.databaseBytesWritten += bytes.databasePayloadBytesWritten();
    }
  }

  DatabaseTelemetryDelta endTelemetry() {
    if (beforeTelemetry == null) return null;
    var delta = beforeTelemetry.deltaTo(telemetry.capture());
    beforeTelemetry = null;
    return delta;
  }

  void retain(DatabaseTelemetryDelta delta, Map<String, String> coordinate,
      ConsumerPublicationBenchmark.Verification verified) throws IOException {
    if (delta == null) return;
    Number lsn = delta.counters().get("postgresql.wal.insert_lsn_bytes");
    if (lsn == null || lsn.longValue() <= 0) {
      throw new IllegalStateException("Native publication requires an observed positive WAL insert-LSN delta");
    }
    var metadata = new LinkedHashMap<>(delta.metadata());
    durability.forEach((setting, value) -> metadata.put("consumer.postgresql." + setting, value));
    metadata.put("consumer.verification", "complete Git/ref/reflog membership; SQL/Lucene where enabled; equal after reopen");
    metadata.put("consumer.membership_sha256", verified.membershipSha256());
    metadata.put("consumer.heads", String.join(",", verified.heads()));
    metadata.put("consumer.reopened_verified", "true");
    metadata.put("consumer.telemetry_scope", "publication only; capture precedes verification and provider reopen");
    var gauges = new LinkedHashMap<>(delta.gauges());
    gauges.put("consumer.git_commits", verified.gitCommits());
    gauges.put("consumer.sql_commits", verified.sqlCommits());
    gauges.put("consumer.lucene_commits", verified.indexedCommits());
    if (indexRoot != null) gauges.put("consumer.lucene.index_bytes", ConsumerBenchmarkFixtures.liveDirectoryBytes(indexRoot));
    DatabaseTelemetryJson.appendNdjson(Path.of(System.getProperty(DatabaseTelemetryCollectors.OUTPUT_PROPERTY)),
        new DatabaseTelemetryObservation(coordinate,
            new DatabaseTelemetryDelta(delta.backend(), delta.enabled(), delta.startedAt(), delta.completedAt(),
                delta.serverVersion(), delta.counters(), gauges, metadata, delta.unsupported())));
  }

  private void databaseCommand(String sql) throws IOException {
    try (var connection = DriverManager.getConnection(settings.getProperty("hibernate.connection.url"),
            settings.getProperty("hibernate.connection.username"), settings.getProperty("hibernate.connection.password"));
        var statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException failure) {
      throw new IOException("Cannot manage isolated publication database", failure);
    }
  }

  private void closeProvider() {
    try (var factory = provider) {
      repositories.forEach(HibernateRepository::close);
    } finally {
      repositories.clear();
      provider = null;
    }
  }

  @Override
  public void close() throws IOException {
    if (closed) return;
    closed = true;
    try {
      if (telemetry != null) telemetry.close();
      closeProvider();
    } finally {
      try {
        if (databaseCreated) databaseCommand(postgres ? "DROP SCHEMA IF EXISTS " + schema + " CASCADE" : "SHUTDOWN");
      } finally {
        if (indexRoot != null && Files.exists(indexRoot)) {
          try (var paths = Files.walk(indexRoot)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
          }
        }
      }
    }
  }
}
