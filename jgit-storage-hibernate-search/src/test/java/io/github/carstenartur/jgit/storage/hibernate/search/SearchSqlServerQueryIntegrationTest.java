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

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitHistoryQuery;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitIndexer;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import io.github.carstenartur.jgit.storage.hibernate.search.schema.SearchSchemaMigrations;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Properties;
import java.util.TimeZone;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.TreeFormatter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mssqlserver.MSSQLServerContainer;

@Testcontainers(disabledWithoutDocker = true)
class SearchSqlServerQueryIntegrationTest {

  private static final String REPOSITORY_NAME = "sqlserver-search";
  private static final Instant FIRST_AUTHOR_TIME = Instant.parse("2026-01-01T10:00:00Z");
  private static final Instant FIRST_COMMIT_TIME = Instant.parse("2026-01-01T11:00:00Z");
  private static final Instant SECOND_AUTHOR_TIME = Instant.parse("2026-01-02T10:00:00Z");
  private static final Instant SECOND_COMMIT_TIME = Instant.parse("2026-01-02T11:00:00Z");

  @Container
  static final MSSQLServerContainer SQL_SERVER =
      new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-CU20-ubuntu-22.04")
          .acceptLicense();

  @Test
  void indexesGitHistoryAndRunsPublicCompoundQueries() throws Exception {
    migrate(CoreSchemaMigrations.SQL_SERVER_LOCATION, CoreSchemaMigrations.SCHEMA_HISTORY_TABLE);
    migrate(SearchSchemaMigrations.SQL_SERVER_LOCATION, SearchSchemaMigrations.SCHEMA_HISTORY_TABLE);

    try (HibernateSessionFactoryProvider provider =
            new HibernateSessionFactoryProvider(properties(), SearchEntities.annotatedClasses());
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), REPOSITORY_NAME)) {
      repository.create(true);
      ObjectId first =
          createCommit(
              repository,
              "Initialize SQL Server history",
              "class Main { int version = 1; }",
              FIRST_AUTHOR_TIME,
              FIRST_COMMIT_TIME,
              null);
      ObjectId second =
          createCommit(
              repository,
              "Update SQL Server history",
              "class Main { int version = 2; }",
              SECOND_AUTHOR_TIME,
              SECOND_COMMIT_TIME,
              first);
      updateMain(repository, second);

      CommitIndexer indexer = new CommitIndexer(provider.getSessionFactory(), REPOSITORY_NAME);
      indexer.indexCommit(repository, first);
      indexer.indexCommit(repository, second);

      GitHistorySearchService search =
          new GitHistorySearchService(provider.getSessionFactory());
      assertEquals(2, search.searchCommitText(REPOSITORY_NAME, "SQL Server", 10).size());
      assertEquals(2, search.findByAuthorEmail(REPOSITORY_NAME, "alice@example.org", 10).size());
      assertEquals(2, search.findByPath(REPOSITORY_NAME, "src/Main.java", 10).size());
      assertEquals(
          2,
          search
              .findBetween(
                  REPOSITORY_NAME,
                  Instant.parse("2026-01-01T00:00:00Z"),
                  Instant.parse("2026-01-03T00:00:00Z"),
                  10)
              .size());

      CommitHistoryQuery compound =
          CommitHistoryQuery.forRepository(REPOSITORY_NAME)
              .matchingText("Update")
              .authoredBy("alice@example.org")
              .touchingPath("src/Main.java")
              .committedBetween(
                  Instant.parse("2026-01-02T00:00:00Z"),
                  Instant.parse("2026-01-03T00:00:00Z"))
              .limit(10)
              .build();
      assertEquals(1, search.findChanges(compound).size());
      assertEquals(second.name(), search.findChanges(compound).getFirst().objectId());
    }
  }

  private static void migrate(String location, String historyTable) {
    Flyway.configure()
        .dataSource(SQL_SERVER.getJdbcUrl(), SQL_SERVER.getUsername(), SQL_SERVER.getPassword())
        .locations(location)
        .table(historyTable)
        .baselineOnMigrate(true)
        .baselineVersion("0")
        .load()
        .migrate();
  }

  private static ObjectId createCommit(
      HibernateRepository repository,
      String message,
      String content,
      Instant authorTime,
      Instant commitTime,
      ObjectId parent)
      throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId blob =
          inserter.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8));
      TreeFormatter tree = new TreeFormatter();
      tree.append("src/Main.java", FileMode.REGULAR_FILE, blob);
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(tree));
      if (parent != null) {
        commit.setParentId(parent);
      }
      commit.setAuthor(person("Alice", "alice@example.org", authorTime));
      commit.setCommitter(person("Build Bot", "build@example.org", commitTime));
      commit.setMessage(message);
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      return commitId;
    }
  }

  private static PersonIdent person(String name, String email, Instant instant) {
    return new PersonIdent(
        name, email, Date.from(instant), TimeZone.getTimeZone("UTC"));
  }

  private static void updateMain(HibernateRepository repository, ObjectId commitId)
      throws Exception {
    RefUpdate update = repository.updateRef("refs/heads/main");
    update.setExpectedOldObjectId(ObjectId.zeroId());
    update.setNewObjectId(commitId);
    assertEquals(RefUpdate.Result.NEW, update.update());
  }

  private static Properties properties() {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", SQL_SERVER.getJdbcUrl());
    properties.put("hibernate.connection.username", SQL_SERVER.getUsername());
    properties.put("hibernate.connection.password", SQL_SERVER.getPassword());
    properties.put("hibernate.connection.driver_class", "com.microsoft.sqlserver.jdbc.SQLServerDriver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.SQLServerDialect");
    properties.put("hibernate.hbm2ddl.auto", "validate");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-heap");
    return properties;
  }
}
