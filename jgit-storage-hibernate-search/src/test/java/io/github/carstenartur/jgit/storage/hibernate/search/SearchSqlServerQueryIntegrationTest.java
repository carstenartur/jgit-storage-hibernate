/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryDeletionResult;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.schema.SearchSchemaMigrations;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitHistoryQuery;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitIndexer;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder.RebuildProgress;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder.RebuildResult;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder.RebuildState;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.TreeFormatter;
import org.flywaydb.core.Flyway;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mssqlserver.MSSQLServerContainer;

@Testcontainers(disabledWithoutDocker = true)
class SearchSqlServerQueryIntegrationTest {

  private static final String PRIMARY_REPOSITORY = "sqlserver-search-primary";
  private static final String SECONDARY_REPOSITORY = "sqlserver-search-secondary";
  private static final String UNICODE_PATH = "Überblick.java";
  private static final String AUTHOR_EMAIL = "alice.ü@example.org";
  private static final String COMMITTER_EMAIL = "build.böt@example.org";
  private static final Instant ROOT_AUTHOR_TIME = Instant.parse("2026-01-01T10:00:00Z");
  private static final Instant ROOT_COMMITTER_TIME = Instant.parse("2026-01-01T11:00:00Z");
  private static final Instant MODIFY_AUTHOR_TIME = Instant.parse("2026-01-02T10:00:00Z");
  private static final Instant MODIFY_COMMITTER_TIME = Instant.parse("2026-01-02T11:00:00Z");
  private static final Instant DELETE_AUTHOR_TIME = Instant.parse("2026-01-03T10:00:00Z");
  private static final Instant DELETE_COMMITTER_TIME = Instant.parse("2026-01-03T11:00:00Z");
  private static final Instant BRANCH_AUTHOR_TIME = Instant.parse("2026-01-04T10:00:00Z");
  private static final Instant BRANCH_COMMITTER_TIME = Instant.parse("2026-01-04T11:00:00Z");
  private static final Instant MERGE_AUTHOR_TIME = Instant.parse("2026-01-05T10:00:00Z");
  private static final Instant MERGE_COMMITTER_TIME = Instant.parse("2026-01-05T11:00:00Z");

  @Container
  static final MSSQLServerContainer SQL_SERVER =
      new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-CU20-ubuntu-22.04")
          .acceptLicense();

  @TempDir Path temporaryDirectory;

  @Test
  void provesIndexQueryRebuildDeletionFailureIsolationAndPersistentRestart() throws Exception {
    migrate(CoreSchemaMigrations.SQL_SERVER_LOCATION, CoreSchemaMigrations.SCHEMA_HISTORY_TABLE);
    migrate(SearchSchemaMigrations.SQL_SERVER_LOCATION, SearchSchemaMigrations.SCHEMA_HISTORY_TABLE);
    Path luceneRoot = temporaryDirectory.resolve("lucene");
    Files.createDirectories(luceneRoot);

    History primaryHistory;
    ObjectId secondaryCommit;
    ObjectId publishedButUnindexed;

    try (HibernateSessionFactoryProvider provider =
        new HibernateSessionFactoryProvider(
            sqlServerProperties(luceneRoot), SearchEntities.annotatedClasses())) {
      primaryHistory = createPrimaryHistory(provider);
      secondaryCommit = createSecondaryHistory(provider);

      try (HibernateRepository primary =
              HibernateRepository.create(
                  provider.getSessionFactory(), PRIMARY_REPOSITORY);
          HibernateRepository secondary =
              HibernateRepository.create(
                  provider.getSessionFactory(), SECONDARY_REPOSITORY)) {
        CommitProjectionRebuilder rebuilder =
            new CommitProjectionRebuilder(provider.getSessionFactory());
        List<RebuildProgress> interruptedProgress = new ArrayList<>();

        assertThrows(
            InterruptedIOException.class,
            () ->
                rebuilder.rebuild(
                    primary,
                    new RepositoryName(PRIMARY_REPOSITORY),
                    event -> {
                      interruptedProgress.add(event);
                      if (event.state() == RebuildState.INDEXING
                          && event.indexedCommits() == 2) {
                        Thread.currentThread().interrupt();
                      }
                    }));
        assertTrue(Thread.interrupted(), "Clear the deliberately set interrupted status");
        assertEquals(RebuildState.INTERRUPTED, interruptedProgress.getLast().state());
        assertEquals(
            InterruptedIOException.class.getName(),
            interruptedProgress.getLast().failureType());
        assertEquals(2, countRows(provider, PRIMARY_REPOSITORY));

        RebuildResult primaryResult =
            rebuilder.rebuild(primary, new RepositoryName(PRIMARY_REPOSITORY));
        assertEquals(RebuildState.COMPLETED, primaryResult.state());
        assertEquals(2, primaryResult.removedProjections());
        assertEquals(2, primaryResult.refTips());
        assertEquals(5, primaryResult.visitedCommits());
        assertEquals(5, primaryResult.indexedCommits());
        assertEquals(0, primaryResult.skippedCommits());

        RebuildResult secondaryResult =
            rebuilder.rebuild(secondary, new RepositoryName(SECONDARY_REPOSITORY));
        assertEquals(1, secondaryResult.refTips());
        assertEquals(1, secondaryResult.indexedCommits());
      }

      GitHistorySearchService search =
          new GitHistorySearchService(provider.getSessionFactory());
      verifyPrimaryQueries(provider, search, primaryHistory);
      assertEquals(1, search.countIndexedCommits(SECONDARY_REPOSITORY));
      assertEquals(
          secondaryCommit.name(),
          search.searchCommitText(SECONDARY_REPOSITORY, "secondary", 10).getFirst().getObjectId());

      DefaultHibernateRepositoryFactory factory =
          new DefaultHibernateRepositoryFactory(
              provider.getSessionFactory(), List.of(new SearchRepositoryDeletionParticipant()));
      RepositoryDeletionResult deletion =
          factory.deleteRepository(new RepositoryName(SECONDARY_REPOSITORY));
      assertEquals(1, deletion.projectionRows());
      assertEquals(0, search.countIndexedCommits(SECONDARY_REPOSITORY));
      assertEquals(
          List.of(), search.searchCommitText(SECONDARY_REPOSITORY, "secondary", 10));
      assertEquals(5, search.countIndexedCommits(PRIMARY_REPOSITORY));

      try (HibernateRepository primary =
          HibernateRepository.create(provider.getSessionFactory(), PRIMARY_REPOSITORY)) {
        publishedButUnindexed =
            createCommit(
                primary,
                "Published while projection fails",
                Map.of(UNICODE_PATH, "class Überblick { int version = 3; }"),
                List.of(primaryHistory.merge()),
                Instant.parse("2026-01-06T10:00:00Z"),
                Instant.parse("2026-01-06T11:00:00Z"));
        updateRef(primary, "refs/heads/published", publishedButUnindexed);

        SessionFactory closedProjectionFactory = closedProjectionSessionFactory();
        assertThrows(
            RuntimeException.class,
            () ->
                new CommitIndexer(closedProjectionFactory, PRIMARY_REPOSITORY)
                    .indexCommit(primary, publishedButUnindexed));
        assertEquals(
            publishedButUnindexed,
            primary.exactRef("refs/heads/published").getObjectId(),
            "Projection failure must not roll back an already published Git ref");
      }
      assertEquals(5, search.countIndexedCommits(PRIMARY_REPOSITORY));
    }

    try (HibernateSessionFactoryProvider restarted =
            new HibernateSessionFactoryProvider(
                sqlServerProperties(luceneRoot), SearchEntities.annotatedClasses());
        HibernateRepository primary =
            HibernateRepository.create(
                restarted.getSessionFactory(), PRIMARY_REPOSITORY)) {
      GitHistorySearchService search =
          new GitHistorySearchService(restarted.getSessionFactory());
      assertEquals(5, search.countIndexedCommits(PRIMARY_REPOSITORY));
      assertEquals(1, search.searchCommitText(PRIMARY_REPOSITORY, "Überprüfung", 10).size());
      assertEquals(0, search.countIndexedCommits(SECONDARY_REPOSITORY));

      Ref published = primary.exactRef("refs/heads/published");
      assertNotNull(published);
      assertEquals(publishedButUnindexed, published.getObjectId());
    }
  }

  private static History createPrimaryHistory(HibernateSessionFactoryProvider provider)
      throws Exception {
    try (HibernateRepository repository =
        HibernateRepository.create(provider.getSessionFactory(), PRIMARY_REPOSITORY)) {
      repository.create(true);
      ObjectId root =
          createCommit(
              repository,
              "Einführung\n\nVollständige Überprüfung der Änderung",
              Map.of(UNICODE_PATH, "class Überblick { String status = \"Änderung\"; }"),
              List.of(),
              ROOT_AUTHOR_TIME,
              ROOT_COMMITTER_TIME);
      ObjectId modified =
          createCommit(
              repository,
              "Unicode-Datei ändern",
              Map.of(UNICODE_PATH, "class Überblick { String status = \"Geändert\"; }"),
              List.of(root),
              MODIFY_AUTHOR_TIME,
              MODIFY_COMMITTER_TIME);
      ObjectId deleted =
          createCommit(
              repository,
              "Unicode-Datei löschen",
              Map.of(),
              List.of(modified),
              DELETE_AUTHOR_TIME,
              DELETE_COMMITTER_TIME);
      ObjectId branch =
          createCommit(
              repository,
              "Feature branch",
              Map.of(
                  UNICODE_PATH,
                  "class Überblick { String status = \"Geändert\"; }",
                  "branch.txt",
                  "branch content"),
              List.of(modified),
              BRANCH_AUTHOR_TIME,
              BRANCH_COMMITTER_TIME);
      ObjectId merge =
          createCommit(
              repository,
              "Merge Prüfung",
              Map.of(
                  UNICODE_PATH,
                  "class Überblick { String status = \"Geändert\"; }",
                  "branch.txt",
                  "branch content"),
              List.of(deleted, branch),
              MERGE_AUTHOR_TIME,
              MERGE_COMMITTER_TIME);
      updateRef(repository, "refs/heads/main", merge);
      updateRef(repository, "refs/heads/feature", branch);
      return new History(root, modified, deleted, branch, merge);
    }
  }

  private static ObjectId createSecondaryHistory(HibernateSessionFactoryProvider provider)
      throws Exception {
    try (HibernateRepository repository =
        HibernateRepository.create(provider.getSessionFactory(), SECONDARY_REPOSITORY)) {
      repository.create(true);
      ObjectId commit =
          createCommit(
              repository,
              "Secondary repository",
              Map.of("secondary.txt", "secondary content"),
              List.of(),
              ROOT_AUTHOR_TIME,
              ROOT_COMMITTER_TIME);
      updateRef(repository, "refs/heads/main", commit);
      return commit;
    }
  }

  private static void verifyPrimaryQueries(
      HibernateSessionFactoryProvider provider,
      GitHistorySearchService search,
      History history) {
    assertEquals(5, search.countIndexedCommits(PRIMARY_REPOSITORY));
    assertEquals(1, search.searchCommitText(PRIMARY_REPOSITORY, "Überprüfung", 10).size());
    assertEquals(5, search.findByAuthorEmail(PRIMARY_REPOSITORY, AUTHOR_EMAIL, 10).size());
    assertEquals(
        5, search.findByCommitterEmail(PRIMARY_REPOSITORY, COMMITTER_EMAIL, 10).size());
    assertEquals(4, search.findByPath(PRIMARY_REPOSITORY, UNICODE_PATH, 10).size());
    assertEquals(2, search.findByPath(PRIMARY_REPOSITORY, "branch.txt", 10).size());

    assertEquals(
        List.of(history.branch().name(), history.deleted().name()),
        search
            .findBetween(
                PRIMARY_REPOSITORY,
                DELETE_COMMITTER_TIME,
                BRANCH_COMMITTER_TIME,
                10)
            .stream()
            .map(GitCommitIndex::getObjectId)
            .toList());

    CommitHistoryQuery compound =
        CommitHistoryQuery.forRepository(PRIMARY_REPOSITORY)
            .matchingText("Merge")
            .authoredBy(AUTHOR_EMAIL)
            .committedBy(COMMITTER_EMAIL)
            .touchingPath("branch.txt")
            .committedBetween(MERGE_COMMITTER_TIME, MERGE_COMMITTER_TIME)
            .limit(10)
            .build();
    assertEquals(
        List.of(history.merge().name()),
        search.findChanges(compound).stream().map(GitCommitIndex::getObjectId).toList());

    CommitHistoryQuery page =
        CommitHistoryQuery.forRepository(PRIMARY_REPOSITORY).offset(1).limit(2).build();
    assertEquals(
        List.of(history.branch().name(), history.deleted().name()),
        search.findChanges(page).stream().map(GitCommitIndex::getObjectId).toList());

    assertEquals(
        List.of(UNICODE_PATH), projection(provider, history.root()).getChangedPathValues());
    assertEquals(
        List.of(UNICODE_PATH), projection(provider, history.modified()).getChangedPathValues());
    assertEquals(
        List.of(UNICODE_PATH), projection(provider, history.deleted()).getChangedPathValues());
    assertEquals(
        List.of("branch.txt"), projection(provider, history.branch()).getChangedPathValues());
    assertEquals(
        Set.of(UNICODE_PATH, "branch.txt"),
        Set.copyOf(projection(provider, history.merge()).getChangedPathValues()));

    GitCommitIndex root = projection(provider, history.root());
    assertEquals("Älice Prüferin", root.getAuthorName());
    assertEquals(AUTHOR_EMAIL, root.getAuthorEmail());
    assertEquals("Build Böt", root.getCommitterName());
    assertEquals(COMMITTER_EMAIL, root.getCommitterEmail());
    assertEquals(ROOT_AUTHOR_TIME, root.getAuthorTime());
    assertEquals(ROOT_COMMITTER_TIME, root.getCommitterTime());
  }

  private static GitCommitIndex projection(
      HibernateSessionFactoryProvider provider, ObjectId objectId) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "FROM GitCommitIndex c WHERE c.repositoryName = :repo AND c.objectId = :objectId",
              GitCommitIndex.class)
          .setParameter("repo", PRIMARY_REPOSITORY)
          .setParameter("objectId", objectId.name())
          .getSingleResult();
    }
  }

  private static long countRows(
      HibernateSessionFactoryProvider provider, String repositoryName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT COUNT(c) FROM GitCommitIndex c WHERE c.repositoryName = :repo", Long.class)
          .setParameter("repo", repositoryName)
          .getSingleResult();
    }
  }

  private static ObjectId createCommit(
      HibernateRepository repository,
      String message,
      Map<String, String> files,
      List<ObjectId> parents,
      Instant authorTime,
      Instant committerTime)
      throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      TreeFormatter tree = new TreeFormatter();
      List<String> paths = new ArrayList<>(files.keySet());
      paths.sort(
          (left, right) ->
              Arrays.compareUnsigned(left.getBytes(UTF_8), right.getBytes(UTF_8)));
      for (String path : paths) {
        ObjectId blob = inserter.insert(Constants.OBJ_BLOB, files.get(path).getBytes(UTF_8));
        tree.append(path, FileMode.REGULAR_FILE, blob);
      }

      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(tree));
      if (!parents.isEmpty()) {
        commit.setParentIds(parents);
      }
      commit.setAuthor(person("Älice Prüferin", AUTHOR_EMAIL, authorTime));
      commit.setCommitter(person("Build Böt", COMMITTER_EMAIL, committerTime));
      commit.setMessage(message);
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      return commitId;
    }
  }

  private static PersonIdent person(String name, String email, Instant instant) {
    return new PersonIdent(name, email, Date.from(instant), TimeZone.getTimeZone("UTC"));
  }

  private static void updateRef(
      HibernateRepository repository, String refName, ObjectId commitId) throws Exception {
    RefUpdate update = repository.updateRef(refName);
    update.setExpectedOldObjectId(ObjectId.zeroId());
    update.setNewObjectId(commitId);
    assertEquals(RefUpdate.Result.NEW, update.update());
  }

  private static SessionFactory closedProjectionSessionFactory() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:closed-search-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-heap");
    HibernateSessionFactoryProvider provider =
        new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses());
    SessionFactory sessionFactory = provider.getSessionFactory();
    provider.close();
    return sessionFactory;
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

  private static Properties sqlServerProperties(Path luceneRoot) {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", SQL_SERVER.getJdbcUrl());
    properties.put("hibernate.connection.username", SQL_SERVER.getUsername());
    properties.put("hibernate.connection.password", SQL_SERVER.getPassword());
    properties.put(
        "hibernate.connection.driver_class", "com.microsoft.sqlserver.jdbc.SQLServerDriver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.SQLServerDialect");
    properties.put("hibernate.hbm2ddl.auto", "validate");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-filesystem");
    properties.put("hibernate.search.backend.directory.root", luceneRoot.toAbsolutePath().toString());
    return properties;
  }

  private record History(
      ObjectId root, ObjectId modified, ObjectId deleted, ObjectId branch, ObjectId merge) {}
}
