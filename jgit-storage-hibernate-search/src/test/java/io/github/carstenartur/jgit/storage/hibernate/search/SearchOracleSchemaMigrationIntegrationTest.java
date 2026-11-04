/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.search;

import static org.junit.jupiter.api.Assertions.*;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

@Testcontainers(disabledWithoutDocker = true)
class SearchOracleSchemaMigrationIntegrationTest {
  @Container static final OracleContainer ORACLE = OracleSearchTestSupport.container();
  @TempDir Path temporary;

  @Test
  void migratesValidatesIndexesAndReopensNationalizedLobsWithoutAdoptingCopiedTables()
      throws Exception {
    var core = OracleSearchTestSupport.core(ORACLE);
    assertEquals(1, core.migrate().migrationsExecuted);
    assertEquals("0.12.0", core.info().current().getVersion().toString());
    var search = OracleSearchTestSupport.search(ORACLE);
    try (var connection =
            DriverManager.getConnection(
                ORACLE.getJdbcUrl(), ORACLE.getUsername(), ORACLE.getPassword());
        var statement = connection.createStatement()) {
      statement.execute(
          "create table git_commit_index (id number primary key, retained varchar2(100 char))");
      statement.execute("insert into git_commit_index values (1, 'copied projection retained')");
      assertThrows(FlywayException.class, search::migrate);
      try (var row = statement.executeQuery("select retained from git_commit_index where id=1")) {
        assertTrue(row.next());
        assertEquals("copied projection retained", row.getString(1));
      }
      statement.execute("drop table git_commit_index purge");
    }
    // Oracle DDL is nontransactional; discard only the failed migration history from this fixture.
    search.repair();
    assertEquals(1, search.migrate().migrationsExecuted);
    assertEquals("0.12.0", search.info().current().getVersion().toString());
    core.validate();
    search.validate();
    assertEquals(0, core.migrate().migrationsExecuted);
    assertEquals(0, search.migrate().migrationsExecuted);
    assertIndexesAndColumnTypes();
    var settings = OracleSearchTestSupport.properties(ORACLE, temporary.resolve("lucene"));
    String projectionKey;
    Instant instant = Instant.parse("2026-01-01T12:34:56.123456789Z");
    try (var provider =
            new HibernateSessionFactoryProvider(settings, SearchEntities.annotatedClasses());
        var session = provider.getSessionFactory().openSession()) {
      var row = new GitCommitIndex();
      row.setRepositoryName("Grüße-東京");
      row.setObjectId("a".repeat(40));
      row.setShortMessage("ü".repeat(2048));
      row.setFullMessage("語".repeat(5000) + " messageend");
      row.setChangedPaths("p".repeat(5000) + "\nÜber_%!.java");
      row.setChangedText("字".repeat(5000) + " contentend");
      row.setAuthorName("Älice");
      row.setAuthorEmail("alice.ü@example.org");
      row.setCommitterName("Build 東京");
      row.setCommitterEmail("build@example.org");
      row.setAuthorTime(instant);
      row.setCommitterTime(instant.plusSeconds(60));
      projectionKey = row.getProjectionKey();
      var transaction = session.beginTransaction();
      session.persist(row);
      transaction.commit();
    }
    var dataSource = new oracle.jdbc.pool.OracleDataSource();
    dataSource.setURL(ORACLE.getJdbcUrl());
    dataSource.setUser(ORACLE.getUsername());
    dataSource.setPassword(ORACLE.getPassword());
    for (String key : List.of("url", "username", "password", "driver_class"))
      settings.remove("hibernate.connection." + key);
    settings.put("hibernate.connection.datasource", dataSource);
    try (var provider =
            new HibernateSessionFactoryProvider(settings, SearchEntities.annotatedClasses());
        var session = provider.getSessionFactory().openSession()) {
      var row = session.find(GitCommitIndex.class, projectionKey);
      assertNotNull(row.getId());
      assertEquals("Grüße-東京", row.getRepositoryName());
      assertEquals("ü".repeat(2048), row.getShortMessage());
      assertEquals("語".repeat(5000) + " messageend", row.getFullMessage());
      assertEquals("p".repeat(5000) + "\nÜber_%!.java", row.getChangedPaths());
      assertEquals("字".repeat(5000) + " contentend", row.getChangedText());
      assertEquals(instant, row.getAuthorTime());
      assertEquals(instant.plusSeconds(60), row.getCommitterTime());
    }
  }

  private static void assertIndexesAndColumnTypes() throws Exception {
    var indexes = new HashMap<String, java.util.List<String>>();
    try (var connection =
        DriverManager.getConnection(
            ORACLE.getJdbcUrl(), ORACLE.getUsername(), ORACLE.getPassword())) {
      try (var rows =
          connection
              .getMetaData()
              .getIndexInfo(
                  null,
                  ORACLE.getUsername().toUpperCase(Locale.ROOT),
                  "GIT_COMMIT_INDEX",
                  false,
                  false)) {
        while (rows.next()) {
          String name = rows.getString("INDEX_NAME");
          String column = rows.getString("COLUMN_NAME");
          if (name != null && column != null)
            indexes
                .computeIfAbsent(
                    name.toLowerCase(Locale.ROOT), ignored -> new java.util.ArrayList<>())
                .add(column.toLowerCase(Locale.ROOT));
        }
      }
      assertEquals(List.of("repository_name"), indexes.get("idx_commit_repo"));
      assertEquals(List.of("repository_name", "commit_time"), indexes.get("idx_commit_repo_time"));
      assertEquals(
          List.of("repository_name", "author_time"), indexes.get("idx_commit_repo_author_time"));
      assertEquals(
          List.of("repository_name", "author_email"), indexes.get("idx_commit_repo_author"));
      assertEquals(
          List.of("repository_name", "committer_email"), indexes.get("idx_commit_repo_committer"));
      assertEquals(List.of("projection_key"), indexes.get("uk_commit_projection_key"));
      assertEquals(List.of("repository_name", "object_id"), indexes.get("uk_commit_repo_object"));
      try (var statement = connection.createStatement();
          var rows =
              statement.executeQuery(
                  "select column_name, data_type from user_tab_columns where"
                      + " table_name='GIT_COMMIT_INDEX'")) {
        var types = new HashMap<String, String>();
        while (rows.next()) types.put(rows.getString(1), rows.getString(2));
        for (String name :
            List.of("SHORT_MESSAGE", "FULL_MESSAGE", "CHANGED_PATHS", "CHANGED_TEXT"))
          assertEquals("NCLOB", types.get(name));
        assertEquals("NVARCHAR2", types.get("REPOSITORY_NAME"));
      }
    }
  }
}
