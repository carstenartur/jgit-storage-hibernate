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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import io.github.carstenartur.jgit.storage.hibernate.schema.LegacyCoreSchemaAdoption;
import io.github.carstenartur.jgit.storage.hibernate.schema.LegacyCoreSchemaAdoptionException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
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

class LegacyCoreSchemaAdoptionIntegrationTest {

  private static final int LEGACY_TAXONOMY_COLUMN_LENGTH = 255;
  private static final int CORE_PACK_EXTENSION_LENGTH = 32;
  private static final int CORE_REF_NAME_LENGTH = 1024;
  private static final String RELEASED_0_1_8_ADOPTION_VERSION = "1";

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();

  @Test
  void adoptsPreLibraryHsqlDbSchemaWithoutChangingPackBlobs() throws Exception {
    AdoptionDatabase database = hsqlDatabase("legacy_adoption");
    try {
      verifyAdoption(database);
    } finally {
      shutdown(database);
    }
  }

  @Test
  void rejectsDuplicatePackIdentityBeforeChangingHsqlDbSchema() throws Exception {
    AdoptionDatabase database = hsqlDatabase("legacy_duplicate");
    try {
      migrateCurrent(database);
      StoredRepository stored = writeRepository(database, "duplicate-repository");
      downgradeToPreLibrarySchema(database);
      duplicateFirstPack(database);

      try (Connection connection = database.openConnection()) {
        LegacyCoreSchemaAdoptionException exception =
            assertThrows(
                LegacyCoreSchemaAdoptionException.class,
                () -> LegacyCoreSchemaAdoption.requireSafeToAdopt(connection));
        assertTrue(exception.getMessage().contains("duplicate"));
        LegacyCoreSchemaAdoption.LegacySchemaReport report =
            LegacyCoreSchemaAdoption.inspect(connection);
        assertTrue(report.requiresAdoption());
        assertEquals(1, report.duplicatePackIdentities().size());
        assertTrue(report.packRows() > stored.packRows());
      }
      assertLegacySchemaUnchanged(database);
    } finally {
      shutdown(database);
    }
  }

  @Test
  void rejectsIncompletePackBeforeChangingHsqlDbSchema() throws Exception {
    AdoptionDatabase database = hsqlDatabase("legacy_incomplete");
    try {
      migrateCurrent(database);
      writeRepository(database, "incomplete-repository");
      downgradeToPreLibrarySchema(database);
      makeFirstPackIncomplete(database);

      try (Connection connection = database.openConnection()) {
        LegacyCoreSchemaAdoptionException exception =
            assertThrows(
                LegacyCoreSchemaAdoptionException.class,
                () -> LegacyCoreSchemaAdoption.requireSafeToAdopt(connection));
        assertTrue(exception.getMessage().contains("incomplete"));
      }
      assertLegacySchemaUnchanged(database);
    } finally {
      shutdown(database);
    }
  }

  @Test
  void rejectsOverlongPackExtensionBeforeChangingHsqlDbSchema() throws Exception {
    AdoptionDatabase database = hsqlDatabase("legacy_overlong_extension");
    try {
      migrateCurrent(database);
      writeRepository(database, "overlong-extension-repository");
      downgradeToPreLibrarySchema(database);
      setFirstPackExtension(database, "x".repeat(CORE_PACK_EXTENSION_LENGTH + 1));

      try (Connection connection = database.openConnection()) {
        LegacyCoreSchemaAdoptionException exception =
            assertThrows(
                LegacyCoreSchemaAdoptionException.class,
                () -> LegacyCoreSchemaAdoption.requireSafeToAdopt(connection));
        assertTrue(exception.getMessage().contains("pack_extension"));
        assertTrue(exception.getMessage().contains("longer than 32"));
      }
      assertLegacySchemaUnchanged(database);
    } finally {
      shutdown(database);
    }
  }

  @Test
  void normalizesAlreadyAdopted018HsqlDbSchemaWithFollowUpMigration() throws Exception {
    AdoptionDatabase database = hsqlDatabase("legacy_adoption_follow_up");
    try {
      DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
      migrateCurrent(database);
      StoredRepository stored = writeRepository(database, "already-adopted-repository");
      downgradeToPreLibrarySchema(database);
      List<String> checksumsBefore = packChecksums(database);
      List<ReflogRow> reflogRowsBefore = reflogRows(database);

      try (Connection connection = database.openConnection()) {
        assertTrue(LegacyCoreSchemaAdoption.requireSafeToAdopt(connection).requiresAdoption());
      }
      migrateLegacyAdoption(database, RELEASED_0_1_8_ADOPTION_VERSION);
      baselineCurrentCoreSchema(database);

      assertEquals(
          LEGACY_TAXONOMY_COLUMN_LENGTH,
          columnSize(database, "git_packs", "pack_extension"));
      assertEquals(
          LEGACY_TAXONOMY_COLUMN_LENGTH,
          columnSize(database, "git_reflog", "ref_name"));
      assertEquals(
          List.of("0", RELEASED_0_1_8_ADOPTION_VERSION), adoptionVersions(database));
      try (Connection connection = database.openConnection()) {
        assertFalse(LegacyCoreSchemaAdoption.requireSafeToAdopt(connection).requiresAdoption());
      }

      migrateLegacyAdoption(database);

      assertEquals(
          CORE_PACK_EXTENSION_LENGTH, columnSize(database, "git_packs", "pack_extension"));
      assertEquals(CORE_REF_NAME_LENGTH, columnSize(database, "git_reflog", "ref_name"));
      assertEquals(
          List.of(
              "0",
              RELEASED_0_1_8_ADOPTION_VERSION,
              CoreSchemaMigrations.LEGACY_ADOPTION_VERSION),
          adoptionVersions(database));
      assertEquals(checksumsBefore, packChecksums(database));
      assertEquals(reflogRowsBefore, reflogRows(database));
      verifyRepository(database, stored);
    } finally {
      shutdown(database);
    }
  }

  static void verifyAdoption(AdoptionDatabase database) throws Exception {
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    migrateCurrent(database);
    StoredRepository first = writeRepository(database, "taxonomy-main");
    StoredRepository second = writeRepository(database, "taxonomy-secondary");

    downgradeToPreLibrarySchema(database);
    assertEquals(
        LEGACY_TAXONOMY_COLUMN_LENGTH,
        columnSize(database, "git_packs", "pack_extension"));
    assertEquals(
        LEGACY_TAXONOMY_COLUMN_LENGTH, columnSize(database, "git_reflog", "ref_name"));
    List<String> checksumsBefore = packChecksums(database);
    List<ReflogRow> reflogRowsBefore = reflogRows(database);
    try (Connection connection = database.openConnection()) {
      LegacyCoreSchemaAdoption.LegacySchemaReport report =
          LegacyCoreSchemaAdoption.requireSafeToAdopt(connection);
      assertTrue(report.requiresAdoption());
      assertEquals(first.packRows() + second.packRows(), report.packRows());
    }

    migrateLegacyAdoption(database);
    baselineCurrentCoreSchema(database);

    assertEquals(
        CORE_PACK_EXTENSION_LENGTH, columnSize(database, "git_packs", "pack_extension"));
    assertEquals(CORE_REF_NAME_LENGTH, columnSize(database, "git_reflog", "ref_name"));
    assertEquals(
        List.of(
            "0",
            RELEASED_0_1_8_ADOPTION_VERSION,
            CoreSchemaMigrations.LEGACY_ADOPTION_VERSION),
        adoptionVersions(database));
    assertEquals(checksumsBefore, packChecksums(database));
    assertEquals(reflogRowsBefore, reflogRows(database));
    try (Connection connection = database.openConnection()) {
      LegacyCoreSchemaAdoption.LegacySchemaReport report =
          LegacyCoreSchemaAdoption.requireSafeToAdopt(connection);
      assertFalse(report.requiresAdoption());
      assertEquals(0, report.incompletePackRows());
      assertTrue(report.duplicatePackIdentities().isEmpty());
      try (Statement statement = connection.createStatement();
          ResultSet resultSet =
              statement.executeQuery(
                  "select count(*) from git_packs "
                      + "where committed = false or committed_at is null")) {
        resultSet.next();
        assertEquals(0, resultSet.getLong(1));
      }
    }

    verifyRepository(database, first);
    verifyRepository(database, second);
  }

  private static AdoptionDatabase hsqlDatabase(String purpose) {
    String databaseName = purpose + "_" + TEST_COUNTER.incrementAndGet();
    return new AdoptionDatabase(
        "jdbc:hsqldb:mem:" + databaseName,
        "SA",
        "",
        "org.hsqldb.jdbc.JDBCDriver",
        "org.hibernate.dialect.HSQLDialect",
        CoreSchemaMigrations.HSQLDB_LOCATION,
        CoreSchemaMigrations.HSQLDB_LEGACY_ADOPTION_LOCATION);
  }

  private static void migrateCurrent(AdoptionDatabase database) {
    Flyway.configure()
        .dataSource(database.url(), database.username(), database.password())
        .locations(database.coreMigrationLocation())
        .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
        .baselineOnMigrate(true)
        .baselineVersion(CoreSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION)
        .baselineDescription(CoreSchemaMigrations.PRE_MIGRATION_BASELINE_DESCRIPTION)
        .load()
        .migrate();
  }

  private static void migrateLegacyAdoption(AdoptionDatabase database) {
    migrateLegacyAdoption(database, null);
  }

  private static void migrateLegacyAdoption(AdoptionDatabase database, String targetVersion) {
    var configuration =
        Flyway.configure()
            .dataSource(database.url(), database.username(), database.password())
            .locations(database.adoptionMigrationLocation())
            .table(CoreSchemaMigrations.LEGACY_ADOPTION_SCHEMA_HISTORY_TABLE)
            .baselineOnMigrate(true)
            .baselineVersion(CoreSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION)
            .baselineDescription("before pre-library core adoption");
    if (targetVersion != null) {
      configuration.target(targetVersion);
    }
    configuration.load().migrate();
  }

  private static void baselineCurrentCoreSchema(AdoptionDatabase database) {
    Flyway.configure()
        .dataSource(database.url(), database.username(), database.password())
        .locations(database.coreMigrationLocation())
        .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
        .baselineOnMigrate(true)
        .baselineVersion(CoreSchemaMigrations.CURRENT_SCHEMA_VERSION)
        .baselineDescription("adopted pre-library core schema")
        .load()
        .migrate();
  }

  private static StoredRepository writeRepository(
      AdoptionDatabase database, String repositoryName) throws Exception {
    try (HibernateSessionFactoryProvider provider = provider(database, "validate");
        HibernateGitStorage storage =
            new DefaultHibernateRepositoryFactory(provider.getSessionFactory())
                .open(new RepositoryName(repositoryName))) {
      ObjectId commitId = createCommit(storage.repository(), repositoryName);
      long packRows = countPacks(database, repositoryName);
      assertTrue(packRows > 0);
      return new StoredRepository(repositoryName, commitId, packRows);
    }
  }

  private static void verifyRepository(
      AdoptionDatabase database, StoredRepository storedRepository) throws Exception {
    try (HibernateSessionFactoryProvider provider = provider(database, "validate");
        HibernateGitStorage storage =
            new DefaultHibernateRepositoryFactory(provider.getSessionFactory())
                .open(new RepositoryName(storedRepository.repositoryName()))) {
      Repository repository = storage.repository();
      Ref main = repository.exactRef("refs/heads/main");
      assertNotNull(main);
      assertEquals(storedRepository.commitId(), main.getObjectId());
      try (RevWalk walk = new RevWalk(repository)) {
        assertEquals(
            "Persist " + storedRepository.repositoryName(),
            walk.parseCommit(storedRepository.commitId()).getFullMessage());
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
              ("content for " + repositoryName).getBytes(StandardCharsets.UTF_8));
      TreeFormatter tree = new TreeFormatter();
      tree.append("taxonomy.dsl", FileMode.REGULAR_FILE, blob);
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(tree));
      PersonIdent actor = new PersonIdent("Taxonomy Migration", "migration@example.invalid");
      commit.setAuthor(actor);
      commit.setCommitter(actor);
      commit.setMessage("Persist " + repositoryName);
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();

      RefUpdate update = repository.updateRef("refs/heads/main");
      update.setExpectedOldObjectId(ObjectId.zeroId());
      update.setNewObjectId(commitId);
      update.setRefLogIdent(actor);
      update.setRefLogMessage("commit: taxonomy adoption", false);
      assertEquals(RefUpdate.Result.NEW, update.update());
      return commitId;
    }
  }

  private static void downgradeToPreLibrarySchema(AdoptionDatabase database) throws Exception {
    try (Connection connection = database.openConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("drop table \"" + CoreSchemaMigrations.SCHEMA_HISTORY_TABLE + "\"");
      statement.execute("drop index idx_pack_repo_committed");
      statement.execute("alter table git_packs drop constraint uk_pack_repo_name_ext");
      statement.execute("alter table git_packs drop column committed_at");
      statement.execute("alter table git_packs drop column committed");
      statement.execute(
          "alter table git_packs alter column pack_extension set data type varchar(255)");
      statement.execute(
          "alter table git_reflog alter column ref_name set data type varchar(255)");
    }
  }

  private static void duplicateFirstPack(AdoptionDatabase database) throws Exception {
    try (Connection connection = database.openConnection();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "insert into git_packs "
              + "(repository_name, pack_name, pack_extension, data, file_size, created_at) "
              + "select repository_name, pack_name, pack_extension, data, file_size, created_at "
              + "from git_packs fetch first 1 row only");
    }
  }

  private static void makeFirstPackIncomplete(AdoptionDatabase database) throws Exception {
    try (Connection connection = database.openConnection();
        Statement statement = connection.createStatement()) {
      assertEquals(
          1,
          statement.executeUpdate(
              "update git_packs set file_size = -1 "
                  + "where id = (select min(id) from git_packs)"));
    }
  }

  private static void setFirstPackExtension(AdoptionDatabase database, String extension)
      throws Exception {
    try (Connection connection = database.openConnection();
        var statement =
            connection.prepareStatement(
                "update git_packs set pack_extension = ? "
                    + "where id = (select min(id) from git_packs)")) {
      statement.setString(1, extension);
      assertEquals(1, statement.executeUpdate());
    }
  }

  private static void assertLegacySchemaUnchanged(AdoptionDatabase database) throws Exception {
    try (Connection connection = database.openConnection()) {
      LegacyCoreSchemaAdoption.LegacySchemaReport report =
          LegacyCoreSchemaAdoption.inspect(connection);
      assertTrue(report.requiresAdoption());
      assertEquals(
          LEGACY_TAXONOMY_COLUMN_LENGTH,
          columnSize(connection, "git_packs", "pack_extension"));
      assertEquals(
          LEGACY_TAXONOMY_COLUMN_LENGTH,
          columnSize(connection, "git_reflog", "ref_name"));
      assertFalse(
          tableExists(connection, CoreSchemaMigrations.LEGACY_ADOPTION_SCHEMA_HISTORY_TABLE));
    }
  }

  private static List<String> adoptionVersions(AdoptionDatabase database) throws Exception {
    List<String> versions = new ArrayList<>();
    try (Connection connection = database.openConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "select version from \""
                    + CoreSchemaMigrations.LEGACY_ADOPTION_SCHEMA_HISTORY_TABLE
                    + "\" where success = true order by installed_rank")) {
      while (resultSet.next()) {
        versions.add(resultSet.getString(1));
      }
    }
    return List.copyOf(versions);
  }

  private static int columnSize(AdoptionDatabase database, String table, String column)
      throws Exception {
    try (Connection connection = database.openConnection()) {
      return columnSize(connection, table, column);
    }
  }

  private static int columnSize(Connection connection, String table, String column)
      throws Exception {
    DatabaseMetaData metadata = connection.getMetaData();
    try (ResultSet resultSet = metadata.getColumns(null, connection.getSchema(), "%", "%")) {
      while (resultSet.next()) {
        if (table.equalsIgnoreCase(resultSet.getString("TABLE_NAME"))
            && column.equalsIgnoreCase(resultSet.getString("COLUMN_NAME"))) {
          return resultSet.getInt("COLUMN_SIZE");
        }
      }
    }
    throw new AssertionError("Column not found: " + table + "." + column);
  }

  private static boolean tableExists(Connection connection, String table) throws Exception {
    DatabaseMetaData metadata = connection.getMetaData();
    try (ResultSet resultSet =
        metadata.getTables(null, connection.getSchema(), "%", new String[] {"TABLE"})) {
      while (resultSet.next()) {
        if (table.equalsIgnoreCase(resultSet.getString("TABLE_NAME"))) {
          return true;
        }
      }
    }
    return false;
  }

  private static List<String> packChecksums(AdoptionDatabase database) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    List<String> checksums = new ArrayList<>();
    try (Connection connection = database.openConnection();
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

  private static List<ReflogRow> reflogRows(AdoptionDatabase database) throws Exception {
    List<ReflogRow> rows = new ArrayList<>();
    try (Connection connection = database.openConnection();
        Statement statement = connection.createStatement();
        ResultSet resultSet =
            statement.executeQuery(
                "select id, version, repository_name, ref_name, old_id, new_id, "
                    + "who_name, who_email, who_when, message from git_reflog order by id")) {
      while (resultSet.next()) {
        long versionValue = resultSet.getLong(2);
        Long version = resultSet.wasNull() ? null : versionValue;
        rows.add(
            new ReflogRow(
                resultSet.getLong(1),
                version,
                resultSet.getString(3),
                resultSet.getString(4),
                resultSet.getString(5),
                resultSet.getString(6),
                resultSet.getString(7),
                resultSet.getString(8),
                resultSet.getTimestamp(9).toInstant(),
                resultSet.getString(10)));
      }
    }
    return List.copyOf(rows);
  }

  private static long countPacks(AdoptionDatabase database, String repositoryName)
      throws Exception {
    try (Connection connection = database.openConnection();
        var statement =
            connection.prepareStatement(
                "select count(*) from git_packs where repository_name = ?")) {
      statement.setString(1, repositoryName);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    }
  }

  private static HibernateSessionFactoryProvider provider(
      AdoptionDatabase database, String ddlMode) {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", database.url());
    properties.put("hibernate.connection.username", database.username());
    properties.put("hibernate.connection.password", database.password());
    properties.put("hibernate.connection.driver_class", database.driverClass());
    properties.put("hibernate.dialect", database.hibernateDialect());
    properties.put("hibernate.hbm2ddl.auto", ddlMode);
    properties.put("hibernate.show_sql", "false");
    return new HibernateSessionFactoryProvider(properties);
  }

  private static void shutdown(AdoptionDatabase database) {
    try (Connection connection = database.openConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("SHUTDOWN");
    } catch (Exception ignored) {
      // Database may already be closed.
    }
  }

  record AdoptionDatabase(
      String url,
      String username,
      String password,
      String driverClass,
      String hibernateDialect,
      String coreMigrationLocation,
      String adoptionMigrationLocation) {

    Connection openConnection() throws Exception {
      return DriverManager.getConnection(url, username, password);
    }
  }

  private record StoredRepository(String repositoryName, ObjectId commitId, long packRows) {}

  private record ReflogRow(
      long id,
      Long version,
      String repositoryName,
      String refName,
      String oldId,
      String newId,
      String whoName,
      String whoEmail,
      Instant whoWhen,
      String message) {}
}
