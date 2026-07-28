/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import io.github.carstenartur.jgit.storage.hibernate.schema.LegacyCoreSchemaAdoption;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevWalk;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mssqlserver.MSSQLServerContainer;

@Testcontainers(disabledWithoutDocker = true)
class LegacyCoreSqlServerSchemaAdoptionIntegrationTest {

  @Container
  static final MSSQLServerContainer SQL_SERVER =
      new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-CU20-ubuntu-22.04")
          .acceptLicense();

  @Test
  void adoptsLegacySandboxSchemaWithoutChangingGitData() throws Exception {
    migrateCurrent();
    StoredRepository stored = writeRepository("sandbox-legacy");
    materializeChunkedPayloads();
    List<String> packChecksumsBefore = packChecksums();
    List<ReflogRow> reflogRowsBefore = reflogRows();

    downgradeToLegacySandboxSchema();
    try (Connection connection = openConnection()) {
      LegacyCoreSchemaAdoption.LegacySchemaReport report =
          LegacyCoreSchemaAdoption.requireSafeToAdopt(connection);
      assertTrue(report.requiresAdoption());
      assertTrue(report.packRows() > 0);
    }

    migrateLegacyAdoption();
    baselineAndMigrateCurrent();

    assertEquals(packChecksumsBefore, packChecksums());
    assertEquals(reflogRowsBefore, reflogRows());
    try (Connection connection = openConnection()) {
      LegacyCoreSchemaAdoption.LegacySchemaReport report =
          LegacyCoreSchemaAdoption.requireSafeToAdopt(connection);
      assertFalse(report.requiresAdoption());
      assertTrue(report.duplicatePackIdentities().isEmpty());
      assertEquals(0, report.incompletePackRows());
      assertTrue(tableExists(connection, "git_pack_chunks"));
      assertTrue(tableExists(connection, "git_repository_lock"));
    }

    verifyRepository(stored);
  }

  private static void migrateCurrent() {
    currentFlyway(CoreSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION).migrate();
  }

  private static void migrateLegacyAdoption() {
    Flyway flyway =
        Flyway.configure()
            .dataSource(SQL_SERVER.getJdbcUrl(), SQL_SERVER.getUsername(), SQL_SERVER.getPassword())
            .locations(CoreSchemaMigrations.SQL_SERVER_LEGACY_ADOPTION_LOCATION)
            .table(CoreSchemaMigrations.LEGACY_ADOPTION_SCHEMA_HISTORY_TABLE)
            .baselineOnMigrate(true)
            .baselineVersion(CoreSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION)
            .baselineDescription("before pre-library core adoption")
            .load();
    flyway.migrate();
    assertEquals(
        CoreSchemaMigrations.LEGACY_ADOPTION_VERSION,
        flyway.info().current().getVersion().getVersion());
  }

  private static void baselineAndMigrateCurrent() {
    Flyway flyway = currentFlyway(CoreSchemaMigrations.CURRENT_SCHEMA_VERSION);
    flyway.migrate();
    assertEquals("0.1.14.2", flyway.info().current().getVersion().getVersion());
  }

  private static Flyway currentFlyway(String baselineVersion) {
    return Flyway.configure()
        .dataSource(SQL_SERVER.getJdbcUrl(), SQL_SERVER.getUsername(), SQL_SERVER.getPassword())
        .locations(CoreSchemaMigrations.SQL_SERVER_LOCATION)
        .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
        .baselineOnMigrate(true)
        .baselineVersion(baselineVersion)
        .baselineDescription("SQL Server Core schema baseline")
        .load();
  }

  private static StoredRepository writeRepository(String repositoryName) throws Exception {
    try (HibernateSessionFactoryProvider provider = provider("validate");
        HibernateGitStorage storage =
            new DefaultHibernateRepositoryFactory(provider.getSessionFactory())
                .open(new RepositoryName(repositoryName))) {
      Repository repository = storage.repository();
      ObjectId commitId = createCommit(repository, repositoryName);
      return new StoredRepository(repositoryName, commitId);
    }
  }

  private static void verifyRepository(StoredRepository stored) throws Exception {
    try (HibernateSessionFactoryProvider provider = provider("validate");
        HibernateGitStorage storage =
            new DefaultHibernateRepositoryFactory(provider.getSessionFactory())
                .open(new RepositoryName(stored.repositoryName()))) {
      Repository repository = storage.repository();
      Ref main = repository.exactRef("refs/heads/main");
      assertNotNull(main);
      assertEquals(stored.commitId(), main.getObjectId());
      try (RevWalk walk = new RevWalk(repository)) {
        assertEquals(
            "SQL Server Legacy-Übernahme " + stored.repositoryName(),
            walk.parseCommit(stored.commitId()).getFullMessage());
      }
      assertEquals(1, repository.getReflogReader("refs/heads/main").getReverseEntries().size());
    }
  }

  private static ObjectId createCommit(Repository repository, String repositoryName)
      throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId blob =
          inserter.insert(
              Constants.OBJ_BLOB,
              ("Inhalt für " + repositoryName).getBytes(StandardCharsets.UTF_8));
      TreeFormatter tree = new TreeFormatter();
      tree.append("übernahme.txt", FileMode.REGULAR_FILE, blob);
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(tree));
      PersonIdent actor = new PersonIdent("Migrationsprüfung", "migration@example.invalid");
      commit.setAuthor(actor);
      commit.setCommitter(actor);
      commit.setMessage("SQL Server Legacy-Übernahme " + repositoryName);
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();

      RefUpdate update = repository.updateRef("refs/heads/main");
      update.setExpectedOldObjectId(ObjectId.zeroId());
      update.setNewObjectId(commitId);
      update.setRefLogIdent(actor);
      update.setRefLogMessage("commit: sichere Übernahme", false);
      assertEquals(RefUpdate.Result.NEW, update.update());
      return commitId;
    }
  }

  private static void materializeChunkedPayloads() throws Exception {
    List<Long> packIds = new ArrayList<>();
    try (Connection connection = openConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery("select id from git_packs where data is null order by id")) {
      while (resultSet.next()) {
        packIds.add(resultSet.getLong(1));
      }
    }

    for (Long packId : packIds) {
      byte[] payload;
      try (Connection connection = openConnection();
          PreparedStatement statement =
              connection.prepareStatement(
                  "select chunk_data from git_pack_chunks where pack_id = ? order by chunk_index")) {
        statement.setLong(1, packId);
        try (ResultSet resultSet = statement.executeQuery();
            ByteArrayOutputStream output = new ByteArrayOutputStream()) {
          while (resultSet.next()) {
            output.write(resultSet.getBytes(1));
          }
          payload = output.toByteArray();
        }
      }
      try (Connection connection = openConnection();
          PreparedStatement statement =
              connection.prepareStatement(
                  "update git_packs set data = ?, file_size = ? where id = ?")) {
        statement.setBytes(1, payload);
        statement.setLong(2, payload.length);
        statement.setLong(3, packId);
        assertEquals(1, statement.executeUpdate());
      }
    }
  }

  private static void downgradeToLegacySandboxSchema() throws Exception {
    try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
      assertEquals(0, count(connection, "select count(*) from git_packs where data is null"));

      statement.execute("drop table git_pack_chunks");
      statement.execute("drop table git_repository_lock");
      statement.execute("drop table " + CoreSchemaMigrations.SCHEMA_HISTORY_TABLE);

      statement.execute("drop index if exists idx_pack_repo_lease on git_packs");
      statement.execute("drop index if exists idx_pack_repo_committed on git_packs");
      statement.execute("drop index if exists idx_pack_repo_name on git_packs");
      statement.execute("drop index if exists idx_pack_repo on git_packs");
      statement.execute("alter table git_packs drop constraint if exists uk_pack_repo_name_ext");
      statement.execute("alter table git_packs alter column data varbinary(max) not null");
      statement.execute("alter table git_packs drop column write_lease_until");
      statement.execute("alter table git_packs drop column write_token");
      statement.execute("alter table git_packs drop column committed_at");
      statement.execute("alter table git_packs drop column committed");
      statement.execute("alter table git_packs alter column repository_name varchar(255) not null");
      statement.execute("alter table git_packs alter column pack_name varchar(255) not null");
      statement.execute("alter table git_packs alter column pack_extension varchar(255) not null");
      statement.execute("alter table git_packs alter column created_at datetime2(6) not null");
      statement.execute("create index idx_pack_repo on git_packs (repository_name)");
      statement.execute(
          "create index idx_pack_repo_name on git_packs (repository_name, pack_name)");

      statement.execute("drop index if exists idx_reflog_repo_ref on git_reflog");
      statement.execute("drop index if exists idx_reflog_repo on git_reflog");
      statement.execute("alter table git_reflog alter column repository_name varchar(255) not null");
      statement.execute("alter table git_reflog alter column ref_name nvarchar(255) not null");
      statement.execute("alter table git_reflog alter column who_when datetime2(6) not null");
      statement.execute("alter table git_reflog alter column message nvarchar(2048) null");
      statement.execute("create index idx_reflog_repo on git_reflog (repository_name)");
      statement.execute(
          "create index idx_reflog_repo_ref on git_reflog (repository_name, ref_name)");
    }
  }

  private static List<String> packChecksums() throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    List<String> checksums = new ArrayList<>();
    try (Connection connection = openConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "select repository_name, pack_name, pack_extension, data "
                    + "from git_packs order by repository_name, pack_name, pack_extension")) {
      while (resultSet.next()) {
        digest.reset();
        digest.update(resultSet.getBytes(4));
        checksums.add(
            resultSet.getString(1)
                + "/"
                + resultSet.getString(2)
                + "."
                + resultSet.getString(3)
                + "="
                + HexFormat.of().formatHex(digest.digest()));
      }
    }
    return List.copyOf(checksums);
  }

  private static List<ReflogRow> reflogRows() throws Exception {
    List<ReflogRow> rows = new ArrayList<>();
    try (Connection connection = openConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "select repository_name, ref_name, old_id, new_id, who_name, who_email, "
                    + "who_when, message from git_reflog order by id")) {
      while (resultSet.next()) {
        rows.add(
            new ReflogRow(
                resultSet.getString(1),
                resultSet.getString(2),
                resultSet.getString(3),
                resultSet.getString(4),
                resultSet.getString(5),
                resultSet.getString(6),
                resultSet.getTimestamp(7).toInstant(),
                resultSet.getString(8)));
      }
    }
    return List.copyOf(rows);
  }

  private static HibernateSessionFactoryProvider provider(String ddlMode) {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", SQL_SERVER.getJdbcUrl());
    properties.put("hibernate.connection.username", SQL_SERVER.getUsername());
    properties.put("hibernate.connection.password", SQL_SERVER.getPassword());
    properties.put("hibernate.connection.driver_class", "com.microsoft.sqlserver.jdbc.SQLServerDriver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.SQLServerDialect");
    properties.put("hibernate.hbm2ddl.auto", ddlMode);
    properties.put("hibernate.show_sql", "false");
    return new HibernateSessionFactoryProvider(properties);
  }

  private static Connection openConnection() throws Exception {
    return DriverManager.getConnection(
        SQL_SERVER.getJdbcUrl(), SQL_SERVER.getUsername(), SQL_SERVER.getPassword());
  }

  private static long count(Connection connection, String sql) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery(sql)) {
      resultSet.next();
      return resultSet.getLong(1);
    }
  }

  private static boolean tableExists(Connection connection, String tableName) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "select count(*) from information_schema.tables where table_name = ?")) {
      statement.setString(1, tableName);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1) == 1;
      }
    }
  }

  private record StoredRepository(String repositoryName, ObjectId commitId) {}

  private record ReflogRow(
      String repositoryName,
      String refName,
      String oldId,
      String newId,
      String whoName,
      String whoEmail,
      Instant whoWhen,
      String message) {}
}
