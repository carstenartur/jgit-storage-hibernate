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
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
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
class CoreSqlServerSchemaMigrationIntegrationTest {

  @Container
  static final MSSQLServerContainer SQL_SERVER =
      new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-CU20-ubuntu-22.04")
          .acceptLicense();

  @Test
  void migratesCleanSchemaValidatesHibernateAndPersistsRepository() throws Exception {
    Flyway flyway =
        Flyway.configure()
            .dataSource(
                SQL_SERVER.getJdbcUrl(), SQL_SERVER.getUsername(), SQL_SERVER.getPassword())
            .locations(CoreSchemaMigrations.SQL_SERVER_LOCATION)
            .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
            .baselineOnMigrate(true)
            .baselineVersion(CoreSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION)
            .baselineDescription(CoreSchemaMigrations.PRE_MIGRATION_BASELINE_DESCRIPTION)
            .load();

    flyway.migrate();

    assertEquals("0.1.18", flyway.info().current().getVersion().getVersion());
    try (Connection connection =
        DriverManager.getConnection(
            SQL_SERVER.getJdbcUrl(), SQL_SERVER.getUsername(), SQL_SERVER.getPassword())) {
      assertTrue(tableExists(connection, "git_packs"));
      assertTrue(tableExists(connection, "git_reflog"));
      assertTrue(tableExists(connection, "git_pack_chunks"));
      assertTrue(tableExists(connection, "git_repository_lock"));
      assertTrue(columnExists(connection, "git_packs", "pack_source"));
      assertTrue(columnExists(connection, "git_packs", "last_modified"));
      assertTrue(columnExists(connection, "git_packs", "object_count"));
      assertTrue(columnExists(connection, "git_packs", "delta_count"));
      assertTrue(columnExists(connection, "git_packs", "index_version"));
      assertTrue(columnExists(connection, "git_packs", "min_update_index"));
      assertTrue(columnExists(connection, "git_packs", "max_update_index"));
      assertTrue(indexExists(connection, "git_reflog", "idx_reflog_repo_id"));
      assertFalse(indexExists(connection, "git_packs", "idx_pack_repo"));
      assertFalse(indexExists(connection, "git_packs", "idx_pack_repo_name"));
      assertFalse(indexExists(connection, "git_pack_chunks", "idx_pack_chunk_pack"));
      assertFalse(indexExists(connection, "git_reflog", "idx_reflog_repo"));
      assertFalse(indexExists(connection, "git_reflog", "idx_reflog_repo_ref"));
    }

    Properties properties = hibernateProperties("validate");
    try (HibernateSessionFactoryProvider provider = new HibernateSessionFactoryProvider(properties);
        HibernateGitStorage storage =
            new DefaultHibernateRepositoryFactory(provider.getSessionFactory())
                .open(new RepositoryName("sqlserver-clean"))) {
      Repository repository = storage.repository();
      ObjectId commitId = createCommit(repository);
      Ref main = repository.exactRef("refs/heads/main");
      assertNotNull(main);
      assertEquals(commitId, main.getObjectId());
      try (RevWalk walk = new RevWalk(repository)) {
        assertEquals("SQL Server migration", walk.parseCommit(commitId).getFullMessage());
      }
    }
  }

  private static ObjectId createCommit(Repository repository) throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId blob =
          inserter.insert(
              Constants.OBJ_BLOB, "sqlserver".getBytes(StandardCharsets.UTF_8));
      TreeFormatter tree = new TreeFormatter();
      tree.append("database.txt", FileMode.REGULAR_FILE, blob);
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(tree));
      PersonIdent actor = new PersonIdent("SQL Server Test", "sqlserver@example.invalid");
      commit.setAuthor(actor);
      commit.setCommitter(actor);
      commit.setMessage("SQL Server migration");
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();

      RefUpdate update = repository.updateRef("refs/heads/main");
      update.setExpectedOldObjectId(ObjectId.zeroId());
      update.setNewObjectId(commitId);
      update.setRefLogIdent(actor);
      update.setRefLogMessage("commit: SQL Server migration", false);
      assertEquals(RefUpdate.Result.NEW, update.update());
      return commitId;
    }
  }

  private static Properties hibernateProperties(String ddlMode) {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", SQL_SERVER.getJdbcUrl());
    properties.put("hibernate.connection.username", SQL_SERVER.getUsername());
    properties.put("hibernate.connection.password", SQL_SERVER.getPassword());
    properties.put("hibernate.connection.driver_class", "com.microsoft.sqlserver.jdbc.SQLServerDriver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.SQLServerDialect");
    properties.put("hibernate.hbm2ddl.auto", ddlMode);
    properties.put("hibernate.show_sql", "false");
    return properties;
  }

  private static boolean tableExists(Connection connection, String tableName) throws Exception {
    DatabaseMetaData metadata = connection.getMetaData();
    try (ResultSet resultSet =
        metadata.getTables(null, connection.getSchema(), "%", new String[] {"TABLE"})) {
      while (resultSet.next()) {
        if (tableName.equalsIgnoreCase(resultSet.getString("TABLE_NAME"))) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean columnExists(Connection connection, String tableName, String columnName)
      throws Exception {
    DatabaseMetaData metadata = connection.getMetaData();
    try (ResultSet resultSet =
        metadata.getColumns(null, connection.getSchema(), tableName, columnName)) {
      while (resultSet.next()) {
        if (columnName.equalsIgnoreCase(resultSet.getString("COLUMN_NAME"))) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean indexExists(Connection connection, String tableName, String indexName)
      throws Exception {
    DatabaseMetaData metadata = connection.getMetaData();
    try (ResultSet resultSet =
        metadata.getIndexInfo(null, connection.getSchema(), tableName, false, false)) {
      while (resultSet.next()) {
        if (indexName.equalsIgnoreCase(resultSet.getString("INDEX_NAME"))) {
          return true;
        }
      }
    }
    return false;
  }
}
