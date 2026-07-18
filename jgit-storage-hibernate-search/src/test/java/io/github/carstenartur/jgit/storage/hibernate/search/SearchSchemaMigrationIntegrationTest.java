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

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.schema.SearchSchemaMigrations;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
  private static final String H2_LEGACY_SCHEMA =
      "/db/legacy/jgit-storage-hibernate/search/0.1.4/h2/schema.sql";

  @Test
  void migratesEmptyH2DatabaseAndRestartsWithValidation() throws Exception {
    try (TestDatabase database = h2Database("empty")) {
      verifyEmptyMigrationAndRestart(database);
    }
  }

  @Test
  void upgradesImmutableLegacyH2FixtureWithoutDataLoss() throws Exception {
    try (TestDatabase database = h2Database("upgrade")) {
      verifyLegacyUpgrade(database);
    }
  }

  static void verifyEmptyMigrationAndRestart(TestDatabase database) throws Exception {
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

  static void verifyLegacyUpgrade(TestDatabase database) throws Exception {
    installLegacySchema(database);

    Long projectionId;
    try (HibernateSessionFactoryProvider provider = provider(database, "validate")) {
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

  private static void installLegacySchema(TestDatabase database) throws IOException, SQLException {
    String script = readResource(database.legacySchemaResource());
    try (Connection connection = database.openConnection();
        Statement statement = connection.createStatement()) {
      for (String sql : sqlStatements(script)) {
        statement.execute(sql);
      }
    }
  }

  private static String readResource(String resourceName) throws IOException {
    try (InputStream input =
        SearchSchemaMigrationIntegrationTest.class.getResourceAsStream(resourceName)) {
      if (input == null) {
        throw new IOException("missing test resource " + resourceName);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static List<String> sqlStatements(String script) {
    StringBuilder withoutComments = new StringBuilder();
    for (String line : script.lines().toList()) {
      if (!line.stripLeading().startsWith("--")) {
        withoutComments.append(line).append('\n');
      }
    }
    return java.util.Arrays.stream(withoutComments.toString().split(";"))
        .map(String::trim)
        .filter(statement -> !statement.isEmpty())
        .toList();
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
        H2_LEGACY_SCHEMA,
        () -> {});
  }

  @FunctionalInterface
  interface SqlCleanup {
    void run() throws SQLException;
  }

  record TestDatabase(
      String url,
      String username,
      String password,
      String driverClass,
      String hibernateDialect,
      String coreMigrationLocation,
      String searchMigrationLocation,
      String legacySchemaResource,
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
