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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitHistoryQuery;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitIndexer;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitSearchHit;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Public indexing/query contracts when repository names compare equal only in SQL. */
class ExactRepositoryStructuredQueryH2Test {

  private static final String REPOSITORY = "team/beta";
  private static final String ALIAS = "Team/Beta";

  @TempDir Path temporaryDirectory;

  @ParameterizedTest
  @ValueSource(strings = {"h2", "hsqldb"})
  void entityPageCountsOffsetAndLimitOnlyWithinTheExactRepository(String database) throws Exception {
    try (var fixture = new Fixture(database)) {
      var query = CommitHistoryQuery.forRepository(REPOSITORY).offset(1).limit(2).build();
      var rows = fixture.search.findChanges(query);
      assertEquals(fixture.expected.subList(1, 3), rows.stream().map(GitCommitIndex::getObjectId).toList());
      assertTrue(rows.stream().allMatch(row -> row.getRepositoryName().equals(REPOSITORY)));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"h2", "hsqldb"})
  void compactPagePreservesExactRepositoryOrderWithoutHydratingEntities(String database) throws Exception {
    try (var fixture = new Fixture(database)) {
      fixture.provider.getSessionFactory().getStatistics().clear();
      var query = CommitHistoryQuery.forRepository(REPOSITORY).offset(1).limit(2).build();
      assertEquals(fixture.expected.subList(1, 3), ids(fixture.search.findChangeSummaries(query)));
      assertEquals(0, fixture.provider.getSessionFactory().getStatistics().getEntityLoadCount());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"h2", "hsqldb"})
  void compactCursorSkipsAliasesWithoutUsingUpTheRequestedLimit(String database) throws Exception {
    try (var fixture = new Fixture(database)) {
      fixture.provider.getSessionFactory().getStatistics().clear();
      var query = CommitHistoryQuery.forRepository(REPOSITORY).limit(3).build();
      try (var cursor = fixture.search.scrollChangeSummaries(query, 2)) {
        assertEquals(fixture.expected.subList(0, 2), ids(cursor.nextChunk()));
        assertEquals(fixture.expected.subList(2, 3), ids(cursor.nextChunk()));
        assertTrue(cursor.nextChunk().isEmpty());
        assertTrue(cursor.isClosed());
      }
      assertEquals(0, fixture.provider.getSessionFactory().getStatistics().getEntityLoadCount());
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"h2", "hsqldb"})
  void exactPredicatesHandleUnicodeAndStoredTrailingSpaces(String database) throws Exception {
    try (var fixture = new Fixture(database)) {
      ExactRepositoryQueryContract.verify(fixture.provider, temporaryDirectory.resolve("exact-strings"));
    }
  }

  private static List<String> ids(List<CommitSearchHit> hits) {
    return hits.stream().map(CommitSearchHit::objectId).toList();
  }

  private final class Fixture implements AutoCloseable {
    private final HibernateSessionFactoryProvider provider;
    private final GitHistorySearchService search;
    private final List<String> expected = new ArrayList<>();

    private Fixture(String database) throws Exception {
      Properties settings = new Properties();
      String url = database.equals("h2")
          ? "jdbc:h2:mem:exact-repository-" + UUID.randomUUID() + ";IGNORECASE=TRUE;DB_CLOSE_DELAY=-1"
          : "jdbc:hsqldb:mem:exact-repository-" + UUID.randomUUID();
      settings.put("hibernate.connection.url", url);
      settings.put("hibernate.connection.username", "sa");
      settings.put("hibernate.connection.password", "");
      settings.put("hibernate.connection.driver_class", database.equals("h2") ? "org.h2.Driver" : "org.hsqldb.jdbc.JDBCDriver");
      if (database.equals("hsqldb")) {
        try (var connection = DriverManager.getConnection(url, "sa", "");
            var statement = connection.createStatement()) {
          statement.execute("SET DATABASE COLLATION SQL_TEXT_UCC");
        }
      }
      settings.put("hibernate.hbm2ddl.auto", "create-drop");
      settings.put("hibernate.generate_statistics", "true");
      settings.put("hibernate.search.backend.type", "lucene");
      settings.put("hibernate.search.backend.directory.type", "local-heap");
      settings.put("hibernate.search.indexing.plan.synchronization.strategy", "sync");
      provider = new HibernateSessionFactoryProvider(settings, SearchEntities.annotatedClasses());
      try {
        // The public CommitIndexer accepts any JGit Repository and an exact logical name. Use
        // actual distinct histories so the database's repository/object unique key is respected.
        for (int repository = 0; repository < 2; repository++) {
          String name = repository == 0 ? REPOSITORY : ALIAS;
          Path directory = temporaryDirectory.resolve("git-" + repository);
          try (var git = Git.init().setDirectory(directory.toFile()).call()) {
            var indexer = new CommitIndexer(provider.getSessionFactory(), name);
            for (int commit = 0; commit < 3; commit++) {
              Files.writeString(directory.resolve("change.txt"), name + "\n" + "payload ".repeat(2048) + commit);
              git.add().addFilepattern("change.txt").call();
              Instant when = Instant.parse("2026-11-07T00:00:00Z").plusSeconds(2L * commit + repository);
              var person = new PersonIdent("Commit author", "author@example.invalid", when, ZoneOffset.UTC);
              var id = git.commit().setAuthor(person).setCommitter(person)
                  .setMessage(name + " change " + commit).call();
              indexer.indexCommit(git.getRepository(), id);
              if (repository == 0) {
                expected.add(id.name());
              }
            }
          }
        }
        Collections.reverse(expected);
        try (var session = provider.getSessionFactory().openSession()) {
          assertEquals(1L, session.createSelectionQuery(
              "SELECT COUNT(DISTINCT c.repositoryName) FROM GitCommitIndex c", Long.class).getSingleResult());
          assertEquals(6L, session.createSelectionQuery(
              "SELECT COUNT(c) FROM GitCommitIndex c", Long.class).getSingleResult());
        }
        search = new GitHistorySearchService(provider.getSessionFactory());
      } catch (Exception | Error failure) {
        provider.close();
        throw failure;
      }
    }

    @Override
    public void close() {
      provider.close();
    }
  }
}
