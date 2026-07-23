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
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
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

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();

  @Test
  void adoptsPreLibraryHsqlDbSchemaWithoutChangingPackBlobs() throws Exception {
    String databaseName = "legacy_adoption_" + TEST_COUNTER.incrementAndGet();
    AdoptionDatabase database =
        new AdoptionDatabase(
            "jdbc:hsqldb:mem:" + databaseName,
            "SA",
            "",
            "org.hsqldb.jdbc.JDBCDriver",
            "org.hibernate.dialect.HSQLDialect",
            CoreSchemaMigrations.HSQLDB_LOCATION,
            CoreSchemaMigrations.HSQLDB_LEGACY_ADOPTION_LOCATION);
    verifyAdoption(database);
    shutdown(database);
  }

  @Test
  void rejectsDuplicatePackIdentityBeforeChangingHsqlDbSchema() throws Exception {
    String databaseName = "legacy_duplicate_" + TEST_COUNTER.incrementAndGet();
    AdoptionDatabase database =
        new AdoptionDatabase(
            "jdbc:hsqldb:mem:" + databaseName,
            "SA",
            "",
            "org.hsqldb.jdbc.JDBCDriver",
            "org.hibernate.dialect.HSQLDialect",
            CoreSchemaMigrations.HSQLDB_LOCATION,
            CoreSchemaMigrations.HSQLDB_LEGACY_ADOPTION_LOCATION);
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
    shutdown(database);
  }

  static void verifyAdoption(AdoptionDatabase database) throws Exception {
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    migrateCurrent(database);
    StoredRepository first = writeRepository(database, "taxonomy-main");
    StoredRepository second = writeRepository(database, "taxonomy-secondary");
    List<String> checksumsBefore = packChecksums(database);

    downgradeToPreLibrarySchema(database);
    try (Connection connection = database.openConnection()) {
      LegacyCoreSchemaAdoption.LegacySchemaReport report =
          LegacyCoreSchemaAdoption.requireSafeToAdopt(connection);
      assertTrue(report.requiresAdoption());
      assertEquals(first.packRows() + second.packRows(), report.packRows());
    }

    migrateLegacyAdoption(database);
    baselineCurrentCoreSchema(database);

    assertEquals(checksumsBefore, packChecksums(database));
    try (Connection connection = database.openConnection()) {
      LegacyCoreSchemaAdoption.LegacySchemaReport report =
          LegacyCoreSchemaAdoption.requireSafeToAdopt(connection);
      assertTrue(!report.requiresAdoption());
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
    Flyway.configure()
        .dataSource(database.url(), database.username(), database.password())
        .locations(database.adoptionMigrationLocation())
        .table(CoreSchemaMigrations.LEGACY_ADOPTION_SCHEMA_HISTORY_TABLE)
        .load()
        .migrate();
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
}
