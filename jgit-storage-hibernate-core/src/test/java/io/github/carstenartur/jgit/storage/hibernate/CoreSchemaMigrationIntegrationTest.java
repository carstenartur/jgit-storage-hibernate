/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.refs.HibernateReflogWriter;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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
import org.eclipse.jgit.lib.ReflogEntry;
import org.eclipse.jgit.lib.ReflogReader;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

class CoreSchemaMigrationIntegrationTest {

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();
  private static final String H2_LEGACY_SCHEMA =
      "/db/legacy/jgit-storage-hibernate/core/0.1.4/h2/schema.sql";

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
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    migrate(database, false);
    assertEquals(List.of("0.1.4", "0.1.5"), migrationVersions(database));

    String repositoryName = "migrated-empty";
    StoredHistory storedHistory;
    try (HibernateSessionFactoryProvider provider = provider(database, "validate")) {
      storedHistory = writeHistory(provider.getSessionFactory(), repositoryName);
    }

    try (HibernateSessionFactoryProvider provider = provider(database, "validate")) {
      verifyHistory(provider.getSessionFactory(), repositoryName, storedHistory);
    }
  }

  static void verifyLegacyUpgrade(TestDatabase database) throws Exception {
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    installLegacySchema(database);

    String repositoryName = "legacy-0.1.4";
    StoredHistory storedHistory;
    try (HibernateSessionFactoryProvider provider = provider(database, "validate")) {
      storedHistory = writeHistory(provider.getSessionFactory(), repositoryName);
    }

    migrate(database, true);
    assertEquals(List.of("0.1.4", "0.1.5"), migrationVersions(database));

    try (HibernateSessionFactoryProvider provider = provider(database, "validate")) {
      verifyHistory(provider.getSessionFactory(), repositoryName, storedHistory);
    }
  }

  private static StoredHistory writeHistory(SessionFactory sessionFactory, String repositoryName)
      throws Exception {
    try (HibernateRepository repository =
        HibernateRepository.create(sessionFactory, repositoryName)) {
      repository.create(true);
      ObjectId first = createCommit(repository, null, "Initial migrated commit", "first");
      updateRef(repository, ObjectId.zeroId(), first);
      ObjectId second = createCommit(repository, first, "Second migrated commit", "second");
      updateRef(repository, first, second);

      HibernateReflogWriter reflogWriter = repository.getReflogWriter();
      PersonIdent firstWho = new PersonIdent("Migration Test", "migration@example.invalid");
      reflogWriter.log(
          "refs/heads/main",
          ObjectId.zeroId(),
          first,
          firstWho,
          "commit: initial migrated commit");
      PersonIdent secondWho =
          new PersonIdent(
              "Migration Test",
              "migration@example.invalid",
              firstWho.getWhenAsInstant().plusSeconds(1),
              firstWho.getZoneId());
      reflogWriter.log(
          "refs/heads/main", first, second, secondWho, "commit: second migrated commit");
      return new StoredHistory(first, second);
    }
  }

  private static void verifyHistory(
      SessionFactory sessionFactory, String repositoryName, StoredHistory storedHistory)
      throws Exception {
    try (HibernateRepository repository =
        HibernateRepository.create(sessionFactory, repositoryName)) {
      Ref main = repository.exactRef("refs/heads/main");
      assertNotNull(main);
      assertEquals(storedHistory.second(), main.getObjectId());

      List<String> messages = new ArrayList<>();
      try (RevWalk walk = new RevWalk(repository)) {
        walk.markStart(walk.parseCommit(storedHistory.second()));
        for (RevCommit commit : walk) {
          messages.add(commit.getFullMessage());
        }
      }
      assertEquals(List.of("Second migrated commit", "Initial migrated commit"), messages);

      ReflogReader reflogReader = repository.getReflogReader("refs/heads/main");
      List<ReflogEntry> entries = reflogReader.getReverseEntries();
      assertEquals(2, entries.size());
      assertEquals(storedHistory.second(), entries.get(0).getNewId());
      assertEquals(storedHistory.first(), entries.get(1).getNewId());
    }
  }

  private static ObjectId createCommit(
      HibernateRepository repository, ObjectId parent, String message, String content)
      throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId blobId = inserter.insert(Constants.OBJ_BLOB, content.getBytes(UTF_8));
      TreeFormatter tree = new TreeFormatter();
      tree.append("history.txt", FileMode.REGULAR_FILE, blobId);

      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(tree));
      if (parent != null) {
        commit.setParentId(parent);
      }
      PersonIdent author = new PersonIdent("Migration Test", "migration@example.invalid");
      commit.setAuthor(author);
      commit.setCommitter(author);
      commit.setMessage(message);
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      return commitId;
    }
  }

  private static void updateRef(
      HibernateRepository repository, ObjectId expectedOldId, ObjectId newId) throws Exception {
    RefUpdate update = repository.updateRef("refs/heads/main");
    update.setExpectedOldObjectId(expectedOldId);
    update.setNewObjectId(newId);
    update.disableRefLog();
    RefUpdate.Result result = update.update();
    assertTrue(
        result == RefUpdate.Result.NEW || result == RefUpdate.Result.FAST_FORWARD,
        () -> "unexpected ref update result " + result);
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
        CoreSchemaMigrationIntegrationTest.class.getResourceAsStream(resourceName)) {
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
    var configuration =
        Flyway.configure()
            .dataSource(database.url(), database.username(), database.password())
            .locations(database.coreMigrationLocation())
            .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE);
    configuration.baselineOnMigrate(true);
    if (legacyBaseline) {
      configuration
          .baselineVersion(CoreSchemaMigrations.LEGACY_SCHEMA_VERSION)
          .baselineDescription(CoreSchemaMigrations.LEGACY_BASELINE_DESCRIPTION);
    } else {
      configuration
          .baselineVersion(CoreSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION)
          .baselineDescription(CoreSchemaMigrations.PRE_MIGRATION_BASELINE_DESCRIPTION);
    }
    configuration.load().migrate();
  }

  private static List<String> migrationVersions(TestDatabase database) throws SQLException {
    List<String> versions = new ArrayList<>();
    String sql =
        "select \"version\" from \""
            + CoreSchemaMigrations.SCHEMA_HISTORY_TABLE
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
    return new HibernateSessionFactoryProvider(properties);
  }

  private static TestDatabase h2Database(String purpose) {
    String name = "core-migration-" + purpose + "-" + TEST_COUNTER.incrementAndGet();
    return new TestDatabase(
        "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1",
        "sa",
        "",
        "org.h2.Driver",
        "org.hibernate.dialect.H2Dialect",
        CoreSchemaMigrations.H2_LOCATION,
        H2_LEGACY_SCHEMA,
        () -> {});
  }

  private record StoredHistory(ObjectId first, ObjectId second) {}

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
