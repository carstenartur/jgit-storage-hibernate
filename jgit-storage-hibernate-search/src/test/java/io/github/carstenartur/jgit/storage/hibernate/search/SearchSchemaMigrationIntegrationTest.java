/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.schema.SearchSchemaMigrations;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.junit.jupiter.api.Test;

class SearchSchemaMigrationIntegrationTest {

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();
  private static final String POSTGRES_URL_ENV = "JSGH_POSTGRES_URL";
  private static final String POSTGRES_USER_ENV = "JSGH_POSTGRES_USER";
  private static final String POSTGRES_PASSWORD_ENV = "JSGH_POSTGRES_PASSWORD";

  @Test
  void migratesEmptyH2DatabaseAndRestartsWithValidation() throws Exception {
    try (TestDatabase database = h2Database("empty")) {
      verifyEmptyMigrationAndRestart(database);
    }
  }

  @Test
  void upgradesLegacyH2SchemaWithoutDataLoss() throws Exception {
    try (TestDatabase database = h2Database("upgrade")) {
      verifyLegacyUpgrade(database);
    }
  }

  @Test
  void migratesEmptyPostgreSqlDatabaseAndRestartsWithValidation() throws Exception {
    try (TestDatabase database = postgresDatabase("empty")) {
      verifyEmptyMigrationAndRestart(database);
    }
  }

  @Test
  void upgradesLegacyPostgreSqlSchemaWithoutDataLoss() throws Exception {
    try (TestDatabase database = postgresDatabase("upgrade")) {
      verifyLegacyUpgrade(database);
    }
  }

  private static void verifyEmptyMigrationAndRestart(TestDatabase database) throws Exception {
    migrate(database, false);
    assertMigrationVersions(database);

    Long projectionId;
    try (HibernateSessionFactoryProvider provider = provider(database, "validate")) {
      projectionId = persistProjection(provider.getSessionFactory(), "empty-object");
    }

    try (HibernateSessionFactoryProvider provider = provider(database, "validate")) {
      verifyProjection(provider.getSessionFactory(), projectionId, "empty-object");
    }
  }

  private static void verifyLegacyUpgrade(TestDatabase database) throws Exception {
    Long projectionId;
    try (HibernateSessionFactoryProvider provider = provider(database, "create")) {
      projectionId = persistProjection(provider.getSessionFactory(), "legacy-object");
    }

    migrate(database, true);
    assertMigrationVersions(database);

    try (HibernateSessionFactoryProvider provider = provider(database, "validate")) {
      verifyProjection(provider.getSessionFactory(), projectionId, "legacy-object");
    }
  }

  private static Long persistProjection(SessionFactory sessionFactory, String objectId) {
    GitCommitIndex projection = new GitCommitIndex();
    projection.setRepositoryName("schema-migration-search");
    projection.setObjectId(objectId);
    projection.setShortMessage("Versioned migration projection");
    projection.setFullMessage("Projection persisted before a SessionFactory restart");
    projection.setAuthorName("Migration Test");
    projection.setAuthorEmail("migration@example.invalid");
    projection.setCommitTime(Instant.parse("2026-07-18T12:00:00Z"));
    projection.setChangedPaths("src/main/java/Example.java");
    projection.setChangedText("class Example {}");

    try (Session session = sessionFactory.openSession()) {
      Transaction transaction = session.beginTransaction();
      try {
        session.persist(projection);
        transaction.commit();
      } catch (RuntimeException exception) {
        if (transaction.isActive()) {
          transaction.rollback();
        }
        throw exception;
      }
    }
    assertNotNull(projection.getId());
    return projection.getId();
  }

  private static void verifyProjection(
      SessionFactory sessionFactory, Long projectionId, String expectedObjectId) {
    try (Session session = sessionFactory.openSession()) {
      GitCommitIndex projection = session.find(GitCommitIndex.class, projectionId);
      assertNotNull(projection);
      assertEquals("schema-migration-search", projection.getRepositoryName());
      assertEquals(expectedObjectId, projection.getObjectId());
      assertEquals("Versioned migration projection", projection.getShortMessage());
      assertEquals("src/main/java/Example.java", projection.getChangedPaths());
    }
  }

  private static void migrate(TestDatabase database, boolean legacyBaseline) {
    migrateLocation(
        database,
        database.coreMigrationLocation(),
        CoreSchemaMigrations.SCHEMA_HISTORY_TABLE,
        CoreSchemaMigrations.LEGACY_SCHEMA_VERSION,
        CoreSchemaMigrations.LEGACY_BASELINE_DESCRIPTION,
        CoreSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION,
        CoreSchemaMigrations.PRE_MIGRATION_BASELINE_DESCRIPTION,
        legacyBaseline);
    migrateLocation(
        database,
        database.searchMigrationLocation(),
        SearchSchemaMigrations.SCHEMA_HISTORY_TABLE,
        SearchSchemaMigrations.LEGACY_SCHEMA_VERSION,
        SearchSchemaMigrations.LEGACY_BASELINE_DESCRIPTION,
        SearchSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION,
        SearchSchemaMigrations.PRE_MIGRATION_BASELINE_DESCRIPTION,
        legacyBaseline);
  }

  private static void migrateLocation(
      TestDatabase database,
      String location,
      String historyTable,
      String baselineVersion,
      String baselineDescription,
      String preMigrationBaselineVersion,
      String preMigrationBaselineDescription,
      boolean legacyBaseline) {
    var configuration =
        Flyway.configure()
            .dataSource(database.url(), database.username(), database.password())
            .locations(location)
            .table(historyTable);
    configuration.baselineOnMigrate(true);
    if (legacyBaseline) {
      configuration
          .baselineVersion(baselineVersion)
          .baselineDescription(baselineDescription);
    } else {
      configuration
          .baselineVersion(preMigrationBaselineVersion)
          .baselineDescription(preMigrationBaselineDescription);
    }
    configuration.load().migrate();
  }

  private static void assertMigrationVersions(TestDatabase database) throws SQLException {
    assertEquals(
        List.of("0.1.4", "0.1.5"),
        migrationVersions(database, CoreSchemaMigrations.SCHEMA_HISTORY_TABLE));
    assertEquals(
        List.of("0.1.4", "0.1.5"),
        migrationVersions(database, SearchSchemaMigrations.SCHEMA_HISTORY_TABLE));
  }

  private static List<String> migrationVersions(TestDatabase database, String historyTable)
      throws SQLException {
    List<String> versions = new ArrayList<>();
    String sql =
        "select \"version\" from \""
            + historyTable
            + "\" where \"success\" = true and \"version\" <> '0' order by \"installed_rank\"";
    try (Connection connection = database.openConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      while (resultSet.next()) {
        versions.add(resultSet.getString(1));
      }
    }
    return versions;
  }

  private static HibernateSessionFactoryProvider provider(TestDatabase database, String ddlMode) {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", database.url());
    properties.put("hibernate.connection.username", database.username());
    properties.put("hibernate.connection.password", database.password());
    properties.put("hibernate.connection.driver_class", database.driverClass());
    properties.put("hibernate.dialect", database.hibernateDialect());
    properties.put("hibernate.hbm2ddl.auto", ddlMode);
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-heap");
    return new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses());
  }

  private static TestDatabase h2Database(String purpose) {
    String name = "search-migration-" + purpose + "-" + TEST_COUNTER.incrementAndGet();
    return new TestDatabase(
        "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1",
        "sa",
        "",
        "org.h2.Driver",
        "org.hibernate.dialect.H2Dialect",
        CoreSchemaMigrations.H2_LOCATION,
        SearchSchemaMigrations.H2_LOCATION,
        () -> {});
  }

  private static TestDatabase postgresDatabase(String purpose) throws SQLException {
    String baseUrl = System.getenv(POSTGRES_URL_ENV);
    assumeTrue(
        baseUrl != null && !baseUrl.isBlank(),
        () -> POSTGRES_URL_ENV + " is not configured; PostgreSQL integration test skipped");
    String username = environmentOrDefault(POSTGRES_USER_ENV, "postgres");
    String password = environmentOrDefault(POSTGRES_PASSWORD_ENV, "postgres");
    String schema =
        "search_migration_" + purpose + "_" + TEST_COUNTER.incrementAndGet();

    try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
        Statement statement = connection.createStatement()) {
      statement.execute("create schema " + schema);
    }

    String schemaUrl = appendParameter(baseUrl, "currentSchema", schema);
    return new TestDatabase(
        schemaUrl,
        username,
        password,
        "org.postgresql.Driver",
        "org.hibernate.dialect.PostgreSQLDialect",
        CoreSchemaMigrations.POSTGRESQL_LOCATION,
        SearchSchemaMigrations.POSTGRESQL_LOCATION,
        () -> {
          try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
              Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
          }
        });
  }

  private static String appendParameter(String url, String key, String value) {
    return url + (url.contains("?") ? "&" : "?") + key + "=" + value;
  }

  private static String environmentOrDefault(String name, String defaultValue) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? defaultValue : value;
  }

  @FunctionalInterface
  private interface SqlCleanup {
    void run() throws SQLException;
  }

  private record TestDatabase(
      String url,
      String username,
      String password,
      String driverClass,
      String hibernateDialect,
      String coreMigrationLocation,
      String searchMigrationLocation,
      SqlCleanup cleanup)
      implements AutoCloseable {

    private Connection openConnection() throws SQLException {
      return DriverManager.getConnection(url, username, password);
    }

    @Override
    public void close() throws SQLException {
      cleanup.run();
    }
  }
}
