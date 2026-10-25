/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.search;

import static org.junit.jupiter.api.Assertions.*;

import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import io.github.carstenartur.jgit.storage.hibernate.search.schema.SearchSchemaMigrations;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitIndexer;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitHistoryQuery;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.TreeFormatter;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SearchHsqlDbSchemaMigrationIntegrationTest {
  @TempDir Path temporary;

  @Test
  void installsIntoAnEmptyDatabaseAndRemainsIdempotent() throws Exception {
    String url = "jdbc:hsqldb:mem:search_" + UUID.randomUUID();
    try {
      migrateCore(url);
      assertEquals(1, searchMigrations(url).migrate().migrationsExecuted);
      assertEquals("0.11.6", searchMigrations(url).info().current().getVersion().toString());
      assertIndexes(url);
      try (var provider = provider(url)) {
        assertTrue(new GitHistorySearchService(provider.getSessionFactory()).searchCommitText("missing", "needle", 10).isEmpty());
      }
      assertEquals(0, searchMigrations(url).migrate().migrationsExecuted);
    } finally {
      shutdown(url);
    }
  }

  @Test
  void preservesProjectionAndGitAcrossDatabaseAndLuceneRestartThenDeletesInIsolation() throws Exception {
    String url = "jdbc:hsqldb:file:" + temporary.resolve("database").toAbsolutePath().toString().replace('\\', '/');
    ObjectId commitId;
    Instant authored = Instant.parse("2024-05-06T07:08:09Z");
    try {
      migrateCore(url);
      searchMigrations(url).migrate();
      try (var provider = provider(url)) {
        var factory = new DefaultHibernateRepositoryFactory(provider.getSessionFactory());
        try (var first = factory.open(new RepositoryName("first"));
            var second = factory.open(new RepositoryName("second"))) {
          commitId = commit(first.repository(), authored);
          assertEquals(commitId, commit(second.repository(), authored));
          for (var storage : List.of(first, second)) {
            String name = storage == first ? "first" : "second";
            var indexed = new CommitIndexer(provider.getSessionFactory(), name).indexCommit(storage.repository(), commitId);
            assertEquals(authored, indexed.getAuthorTime());
            assertEquals(authored.plusSeconds(60), indexed.getCommitTime());
          }
        }
      }
      shutdown(url);
      assertEquals(0, searchMigrations(url).migrate().migrationsExecuted);
      try (var provider = provider(url)) {
        var service = new GitHistorySearchService(provider.getSessionFactory());
        var restartedHits = service.searchCommitText("first", "needle", 10);
        assertEquals(List.of(commitId.name()), restartedHits.stream().map(hit -> hit.getObjectId()).toList());
        assertEquals(authored, restartedHits.getFirst().getAuthorTime());
        assertEquals(authored.plusSeconds(60), restartedHits.getFirst().getCommitTime());
        assertEquals(List.of(commitId.name()), service.searchCommitText("second", "needle", 10).stream().map(hit -> hit.getObjectId()).toList());
        assertEquals(List.of(commitId.name()), service.findChanges(CommitHistoryQuery.forRepository("first")
            .matchingMessages("grüße").authoredBy("author@example.org").committedBy("committer@example.org")
            .authoredBetween(authored.minusSeconds(1), authored.plusSeconds(1)).limit(1).build())
            .stream().map(hit -> hit.getObjectId()).toList());
        var factory = new DefaultHibernateRepositoryFactory(provider.getSessionFactory(), List.of(new SearchRepositoryDeletionParticipant()));
        try (var reopened = factory.open(new RepositoryName("first"))) {
          assertEquals(commitId, reopened.repository().resolve("refs/heads/main"));
          var rebuilt = new CommitProjectionRebuilder(provider.getSessionFactory()).rebuild(reopened.repository(), new RepositoryName("first"));
          assertEquals(1, rebuilt.removedProjections());
          assertEquals(1, rebuilt.indexedCommits());
          assertEquals(commitId, reopened.repository().resolve("refs/heads/main"));
        }
        assertEquals(1, factory.deleteRepository(new RepositoryName("first")).projectionRows());
        assertTrue(service.searchCommitText("first", "needle", 10).isEmpty());
        assertEquals(1, service.searchCommitText("second", "needle", 10).size());
      }
    } finally {
      shutdown(url);
    }
  }

  @Test
  void refusesAnExistingCopiedProjectionWithoutChangingItsRows() throws Exception {
    String url = "jdbc:hsqldb:mem:copied_" + UUID.randomUUID();
    try {
      migrateCore(url);
      try (var connection = DriverManager.getConnection(url, "SA", ""); var statement = connection.createStatement()) {
        statement.execute("create table git_commit_index (id bigint primary key, commit_message varchar(100))");
        statement.execute("insert into git_commit_index values (1, 'copied projection retained')");
      }
      assertThrows(FlywayException.class, () -> searchMigrations(url).migrate());
      try (var connection = DriverManager.getConnection(url, "SA", ""); var statement = connection.createStatement();
          var row = statement.executeQuery("select commit_message from git_commit_index where id=1")) {
        assertTrue(row.next());
        assertEquals("copied projection retained", row.getString(1));
      }
    } finally {
      shutdown(url);
    }
  }

  private HibernateSessionFactoryProvider provider(String url) {
    var settings = new Properties();
    settings.setProperty("hibernate.connection.url", url);
    settings.setProperty("hibernate.connection.username", "SA");
    settings.setProperty("hibernate.connection.password", "");
    settings.setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver");
    settings.setProperty("hibernate.hbm2ddl.auto", "validate");
    settings.setProperty("hibernate.search.backend.directory.type", "local-filesystem");
    settings.setProperty("hibernate.search.backend.directory.root", temporary.resolve("lucene").toString());
    settings.setProperty("hibernate.search.indexing.plan.synchronization.strategy", "sync");
    return new HibernateSessionFactoryProvider(settings, SearchEntities.annotatedClasses());
  }

  private static ObjectId commit(org.eclipse.jgit.lib.Repository repository, Instant authored) throws Exception {
    ObjectId id;
    try (var inserter = repository.newObjectInserter()) {
      var commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(new TreeFormatter()));
      commit.setAuthor(new PersonIdent("Author", "author@example.org", java.util.Date.from(authored), java.util.TimeZone.getTimeZone("Asia/Tokyo")));
      commit.setCommitter(new PersonIdent("Committer", "committer@example.org", java.util.Date.from(authored.plusSeconds(60)), java.util.TimeZone.getTimeZone("UTC")));
      commit.setMessage("Needle Unicode Grüße");
      id = inserter.insert(commit);
      inserter.flush();
    }
    var ref = repository.updateRef("refs/heads/main");
    ref.setNewObjectId(id);
    assertEquals(RefUpdate.Result.NEW, ref.update());
    return id;
  }

  private static void migrateCore(String url) {
    Flyway.configure().dataSource(url, "SA", "").locations(CoreSchemaMigrations.HSQLDB_LOCATION)
        .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE).load().migrate();
  }

  private static void assertIndexes(String url) throws Exception {
    var indexes = new java.util.HashMap<String, java.util.List<String>>();
    try (var connection = DriverManager.getConnection(url, "SA", "");
        var rows = connection.getMetaData().getIndexInfo(null, "PUBLIC", "GIT_COMMIT_INDEX", false, false)) {
      while (rows.next()) {
        String name = rows.getString("INDEX_NAME");
        String column = rows.getString("COLUMN_NAME");
        if (name != null && column != null) indexes.computeIfAbsent(name.toLowerCase(java.util.Locale.ROOT),
            ignored -> new java.util.ArrayList<>()).add(column.toLowerCase(java.util.Locale.ROOT));
      }
    }
    assertEquals(List.of("repository_name"), indexes.get("idx_commit_repo"));
    assertEquals(List.of("repository_name", "commit_time"), indexes.get("idx_commit_repo_time"));
    assertEquals(List.of("repository_name", "author_time"), indexes.get("idx_commit_repo_author_time"));
    assertEquals(List.of("repository_name", "author_email"), indexes.get("idx_commit_repo_author"));
    assertEquals(List.of("repository_name", "committer_email"), indexes.get("idx_commit_repo_committer"));
  }

  private static Flyway searchMigrations(String url) {
    return Flyway.configure().dataSource(url, "SA", "").locations(SearchSchemaMigrations.HSQLDB_LOCATION)
        .table(SearchSchemaMigrations.SCHEMA_HISTORY_TABLE).failOnMissingLocations(true)
        .baselineOnMigrate(true).baselineVersion("0").load();
  }

  private static void shutdown(String url) throws Exception {
    try (var connection = DriverManager.getConnection(url, "SA", ""); var statement = connection.createStatement()) {
      statement.execute("SHUTDOWN");
    }
  }
}
