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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import io.github.carstenartur.jgit.storage.hibernate.schema.LegacyCoreSchemaAdoption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import java.util.Random;
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
class LegacyCoreSqlServerPostAdoptionWriteIntegrationTest {

  @Container
  static final MSSQLServerContainer SQL_SERVER =
      new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-CU20-ubuntu-22.04")
          .acceptLicense();

  @Test
  void writesChunkedPackAndReflogAfterLegacyAdoption() throws Exception {
    createEmptyLegacySchema();
    try (Connection connection = openConnection()) {
      assertTrue(LegacyCoreSchemaAdoption.requireSafeToAdopt(connection).requiresAdoption());
    }

    Flyway.configure()
        .dataSource(SQL_SERVER.getJdbcUrl(), SQL_SERVER.getUsername(), SQL_SERVER.getPassword())
        .locations(CoreSchemaMigrations.SQL_SERVER_LEGACY_ADOPTION_LOCATION)
        .table(CoreSchemaMigrations.LEGACY_ADOPTION_SCHEMA_HISTORY_TABLE)
        .baselineOnMigrate(true)
        .baselineVersion(CoreSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION)
        .baselineDescription("before pre-library core adoption")
        .load()
        .migrate();

    Flyway flyway =
        Flyway.configure()
            .dataSource(SQL_SERVER.getJdbcUrl(), SQL_SERVER.getUsername(), SQL_SERVER.getPassword())
            .locations(CoreSchemaMigrations.SQL_SERVER_LOCATION)
            .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
            .baselineOnMigrate(true)
            .baselineVersion(CoreSchemaMigrations.CURRENT_SCHEMA_VERSION)
            .baselineDescription("adopted pre-library core schema")
            .load();
    flyway.migrate();
    assertEquals("0.1.14.2", flyway.info().current().getVersion().getVersion());

    ObjectId commitId;
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateGitStorage storage =
            new DefaultHibernateRepositoryFactory(provider.getSessionFactory())
                .open(new RepositoryName("post-adoption"))) {
      Repository repository = storage.repository();
      commitId = createCommit(repository);
      Ref main = repository.exactRef("refs/heads/main");
      assertNotNull(main);
      assertEquals(commitId, main.getObjectId());
      try (RevWalk walk = new RevWalk(repository)) {
        assertEquals("Write after SQL Server adoption", walk.parseCommit(commitId).getFullMessage());
      }
      assertEquals(1, repository.getReflogReader("refs/heads/main").getReverseEntries().size());
    }

    try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
      assertTrue(count(statement, "select count(*) from git_pack_chunks") > 0);
      assertTrue(
          count(
                  statement,
                  "select count(*) from git_packs "
                      + "where repository_name = 'post-adoption' and committed = 1")
              > 0);
      assertEquals(
          1,
          count(
              statement,
              "select count(*) from git_reflog "
                  + "where repository_name = 'post-adoption' and ref_name = 'refs/heads/main'"));
    }
  }

  private static void createEmptyLegacySchema() throws Exception {
    try (Connection connection = openConnection(); Statement statement = connection.createStatement()) {
      statement.execute(
          """
          create table git_packs (
            id bigint identity(1,1) not null primary key,
            repository_name varchar(255) not null,
            pack_name varchar(255) not null,
            pack_extension varchar(255) not null,
            data varbinary(max) not null,
            file_size bigint not null,
            created_at datetime2(6) not null
          )
          """);
      statement.execute("create index idx_pack_repo on git_packs (repository_name)");
      statement.execute(
          "create index idx_pack_repo_name on git_packs (repository_name, pack_name)");
      statement.execute(
          """
          create table git_reflog (
            id bigint identity(1,1) not null primary key,
            version bigint null,
            repository_name varchar(255) not null,
            ref_name nvarchar(255) not null,
            old_id varchar(40) null,
            new_id varchar(40) null,
            who_name nvarchar(255) null,
            who_email nvarchar(255) null,
            who_when datetime2(6) not null,
            message nvarchar(2048) null
          )
          """);
      statement.execute("create index idx_reflog_repo on git_reflog (repository_name)");
      statement.execute(
          "create index idx_reflog_repo_ref on git_reflog (repository_name, ref_name)");
    }
  }

  private static ObjectId createCommit(Repository repository) throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      byte[] payload = new byte[1024 * 1024];
      new Random(42).nextBytes(payload);
      ObjectId blob = inserter.insert(Constants.OBJ_BLOB, payload);
      TreeFormatter tree = new TreeFormatter();
      tree.append("post-adoption.bin", FileMode.REGULAR_FILE, blob);
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(tree));
      PersonIdent actor = new PersonIdent("Adoption Test", "adoption@example.invalid");
      commit.setAuthor(actor);
      commit.setCommitter(actor);
      commit.setMessage("Write after SQL Server adoption");
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();

      RefUpdate update = repository.updateRef("refs/heads/main");
      update.setExpectedOldObjectId(ObjectId.zeroId());
      update.setNewObjectId(commitId);
      update.setRefLogIdent(actor);
      update.setRefLogMessage("commit: write after adoption", false);
      assertEquals(RefUpdate.Result.NEW, update.update());
      return commitId;
    }
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", SQL_SERVER.getJdbcUrl());
    properties.put("hibernate.connection.username", SQL_SERVER.getUsername());
    properties.put("hibernate.connection.password", SQL_SERVER.getPassword());
    properties.put("hibernate.connection.driver_class", "com.microsoft.sqlserver.jdbc.SQLServerDriver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.SQLServerDialect");
    properties.put("hibernate.hbm2ddl.auto", "validate");
    properties.put("hibernate.show_sql", "false");
    return new HibernateSessionFactoryProvider(properties);
  }

  private static Connection openConnection() throws Exception {
    return DriverManager.getConnection(
        SQL_SERVER.getJdbcUrl(), SQL_SERVER.getUsername(), SQL_SERVER.getPassword());
  }

  private static long count(Statement statement, String sql) throws Exception {
    try (ResultSet resultSet = statement.executeQuery(sql)) {
      resultSet.next();
      return resultSet.getLong(1);
    }
  }
}
