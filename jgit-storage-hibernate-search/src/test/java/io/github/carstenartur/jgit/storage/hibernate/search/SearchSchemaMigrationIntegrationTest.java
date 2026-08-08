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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.profile.SearchIndexingProfile;
import io.github.carstenartur.jgit.storage.hibernate.search.schema.SearchSchemaMigrations;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;

class SearchSchemaMigrationIntegrationTest {

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
    migrateCoreAndSearch(database, false);
    assertMigrationVersions(database);
    try (HibernateSessionFactoryProvider first = validatingProvider(database);
        HibernateSessionFactoryProvider restarted = validatingProvider(database)) {
      assertEquals(0L, count(restarted, "GitCommitIndex"));
    }
  }

  static void verifyLegacyUpgrade(TestDatabase database) throws Exception {
    installLegacyFixture(database);
    String repositoryName = "legacy-search-repository";
    String objectId = "1111111111111111111111111111111111111111";
    String originalMessage = "Legacy searchable commit";
    insertLegacyCommit(database, repositoryName, objectId, originalMessage);

    migrateCoreAndSearch(database, true);
    assertMigrationVersions(database);

    try (HibernateSessionFactoryProvider provider = validatingProvider(database)) {
      try (Session session = provider.getSessionFactory().openSession()) {
        GitCommitIndex indexed =
            session
                .createQuery(
                    "FROM GitCommitIndex c WHERE c.repositoryName = :repo "
                        + "AND c.objectId = :objectId",
                    GitCommitIndex.class)
                .setParameter("repo", repositoryName)
                .setParameter("objectId", objectId)
                .getSingleResult();
        assertEquals(originalMessage, indexed.getShortMessage());
        assertEquals("legacy-author@example.invalid", indexed.getAuthorEmail());
        assertNotNull(indexed.getProjectionKey());
        assertTrue(indexed.getProjectionKey().startsWith("legacy-"));
        assertEquals(SearchIndexingProfile.CONTENT.id(), indexed.getIndexProfile());
      }
    }
  }

  private static TestDatabase h2Database(String purpose) {
    String name = "search-schema-" + purpose + "-" + UUID.randomUUID();
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

  private static HibernateSessionFactoryProvider validatingProvider(TestDatabase database) {
    Properties properties = database.hibernateProperties();
    properties.put("hibernate.hbm2ddl.auto", "validate");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-heap");
    return new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses());
  }

  private static long count(HibernateSessionFactoryProvider provider, String entityName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery("SELECT COUNT(e) FROM " + entityName + " e", Long.class)
          .getSingleResult();
    }
  }

  private static void installLegacyFixture(TestDatabase database) throws Exception {
    if (database.legacySchemaResource() == null) {
      throw new IllegalArgumentException("Legacy fixture is not configured for " + database.url());
    }
    executeScript(database, database.legacySchemaResource());
  }

  private static void insertLegacyCommit(
      TestDatabase database, String repositoryName, String objectId, String message)
      throws SQLException {
    String sql =
        "insert into git_commit_index "
            + "(repository_name, object_id, short_message, full_message, author_name, "
            + "author_email, commit_time, changed_paths, changed_text) "
            + "values (?, ?, ?, ?, ?, ?, current_timestamp, ?, ?)";
    try (Connection connection = database.openConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, repositoryName);
      statement.setString(2, objectId);
      statement.setString(3, message);
      statement.setString(4, message + "\n\nLegacy body");
      statement.setString(5, "Legacy Author");
      statement.setString(6, "legacy-author@example.invalid");
      statement.setString(7, "legacy/path.txt");
      statement.setString(8, "legacy searchable text");
      statement.executeUpdate();
    }
  }

  private static void migrateCoreAndSearch(TestDatabase database, boolean legacy) {
    migrate(
        database,
        database.coreLocation(),
        CoreSchemaMigrations.SCHEMA_HISTORY_TABLE,
        CoreSchemaMigrations.LEGACY_SCHEMA_VERSION,
        CoreSchemaMigrations.LEGACY_BASELINE_DESCRIPTION,
        CoreSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION,
        CoreSchemaMigrations.PRE_MIGRATION_BASELINE_DESCRIPTION,
        legacy);
    migrate(
        database,
        database.searchLocation(),
        SearchSchemaMigrations.SCHEMA_HISTORY_TABLE,
        SearchSchemaMigrations.LEGACY_SCHEMA_VERSION,
        SearchSchemaMigrations.LEGACY_BASELINE_DESCRIPTION,
        SearchSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION,
        SearchSchemaMigrations.PRE_MIGRATION_BASELINE_DESCRIPTION,
        legacy);
  }

  private static void migrate(
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
            .table(historyTable)
            .baselineOnMigrate(true);
    if (legacyBaseline) {
      configuration.baselineVersion(baselineVersion).baselineDescription(baselineDescription);
    } else {
      configuration
          .baselineVersion(preMigrationBaselineVersion)
          .baselineDescription(preMigrationBaselineDescription);
    }
    configuration.load().migrate();
  }

  private static void assertMigrationVersions(TestDatabase database) throws SQLException {
    assertEquals(
        List.of(
            "0.1.4",
            "0.1.5",
            "0.1.14",
            "0.1.14.1",
            "0.1.14.2",
            "0.1.17",
            "0.1.18",
            "0.9.1"),
        migrationVersions(database, CoreSchemaMigrations.SCHEMA_HISTORY_TABLE));
    assertEquals(
        List.of("0.1.4", "0.1.5", "0.1.14", "0.9.1", "0.9.1.1"),
        migrationVersions(database, SearchSchemaMigrations.SCHEMA_HISTORY_TABLE));
  }

  private static List<String> migrationVersions(TestDatabase database, String historyTable)
      throws SQLException {
    List<String> versions = new ArrayList<>();
    String sql =
        "select \"version\" from \""
            + historyTable
            + "\" where \"success\" = ? and \"version\" <> '0' order by \"installed_rank\"";
    try (Connection connection = database.openConnection();
        PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setBoolean(1, true);
      try (ResultSet resultSet = statement.executeQuery()) {
        while (resultSet.next()) {
          versions.add(resultSet.getString(1));
        }
      }
    }
    return versions;
  }

  private static void executeScript(TestDatabase database, String resource) throws Exception {
    try (InputStream stream = SearchSchemaMigrationIntegrationTest.class.getResourceAsStream(resource)) {
      assertNotNull(stream, "Missing legacy schema resource " + resource);
      List<String> statements = splitStatements(stream);
      try (Connection connection = database.openConnection();
          Statement statement = connection.createStatement()) {
        for (String sql : statements) {
          if (!sql.isBlank()) {
            statement.execute(sql);
          }
        }
      }
    }
  }

  private static List<String> splitStatements(InputStream stream) throws IOException {
    List<String> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("--")) {
          continue;
        }
        current.append(line).append('\n');
        if (trimmed.endsWith(";")) {
          statements.add(current.toString());
          current.setLength(0);
        }
      }
    }
    if (!current.toString().isBlank()) {
      statements.add(current.toString());
    }
    return statements;
  }

  record TestDatabase(
      String url,
      String username,
      String password,
      String driver,
      String dialect,
      String coreLocation,
      String searchLocation,
      String legacySchemaResource,
      CheckedRunnable closeAction)
      implements AutoCloseable {

    Connection openConnection() throws SQLException {
      return DriverManager.getConnection(url, username, password);
    }

    Properties hibernateProperties() {
      Properties properties = new Properties();
      properties.put("hibernate.connection.url", url);
      properties.put("hibernate.connection.username", username);
      properties.put("hibernate.connection.password", password);
      properties.put("hibernate.connection.driver_class", driver);
      properties.put("hibernate.dialect", dialect);
      return properties;
    }

    @Override
    public void close() throws Exception {
      closeAction.run();
    }
  }

  @FunctionalInterface
  interface CheckedRunnable {
    void run() throws Exception;
  }
}
