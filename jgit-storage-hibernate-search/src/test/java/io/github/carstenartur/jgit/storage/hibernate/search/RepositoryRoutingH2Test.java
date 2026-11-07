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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitHistoryQuery;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitIndexer;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.FSDirectory;
import org.eclipse.jgit.api.Git;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.search.mapper.orm.Search;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoryRoutingH2Test {

  private static final String ROUTED_INDEX = "GitCommitIndexByRepository";
  private static final String ROUTING_CONFIGURER =
      "class:io.github.carstenartur.jgit.storage.hibernate.search.RepositoryRoutingMappingConfigurer";
  // More names than shards, so some repositories necessarily share a physical shard.
  private static final List<String> REPOSITORIES =
      List.of("team/alpha", "team/beta", "Team/Beta", "toolkit", "Grüße-東京");
  private static final String OBJECT_ID = "1".repeat(40);

  @TempDir Path temporaryDirectory;

  @Test
  void caseInsensitiveDatabaseDoesNotMergeExactRepositoryRoutes() throws Exception {
    String lowerCaseRepository = "team/beta";
    String mixedCaseRepository = "Team/Beta";
    // Enough projections to cross a bounded scalar-read page. Object IDs differ because a
    // case-insensitive database also applies that collation to the repository/object unique key.
    try (var provider = provider("create", true, 2, "indexes", true)) {
      var factory = provider.getSessionFactory();
      try (Session session = factory.openSession()) {
        session.beginTransaction();
        for (int i = 0; i < 260; i++) {
          var row = new GitCommitIndex();
          row.setRepositoryName(i < 257 ? lowerCaseRepository : mixedCaseRepository);
          row.setObjectId(String.format("%040x", i + 1));
          row.setShortMessage("routing case-sensitive identity");
          row.setFullMessage("routing case-sensitive identity");
          session.persist(row);
        }
        session.getTransaction().commit();
        assertEquals(
            1L,
            session
                .createSelectionQuery(
                    "SELECT COUNT(DISTINCT c.repositoryName) FROM GitCommitIndex c", Long.class)
                .getSingleResult(),
            "the fixture must use a case-insensitive SQL comparison");
      }
      var search = new GitHistorySearchService(factory);
      assertEquals(257, search.searchCommitTextSummaries(lowerCaseRepository, "routing", 300).size());
      assertEquals(3, search.searchCommitTextSummaries(mixedCaseRepository, "routing", 300).size());
    }
    try (var provider = provider("validate", true, 2, "indexes", true)) {
      var search = new GitHistorySearchService(provider.getSessionFactory());
      assertEquals(257, search.searchCommitTextSummaries(lowerCaseRepository, "routing", 300).size());
      assertEquals(3, search.searchCommitTextSummaries(mixedCaseRepository, "routing", 300).size());
    }
  }

  @Test
  void enablingOneShardRoutingRequiresReindexingAndLeavesTheDefaultIndexIntact() throws Exception {
    try (var provider = provider("create", false, 1)) {
      persist(provider.getSessionFactory());
      assertEquals(5, count(provider.getSessionFactory(), null));
      assertEquals(0, count(provider.getSessionFactory(), REPOSITORIES.getFirst()));
    }

    try (var provider = provider("validate", true, 1)) {
      var factory = provider.getSessionFactory();
      assertEquals(0, count(factory, null), "the routed index must have its own namespace");
      var failure =
          assertThrows(IllegalStateException.class, () -> new GitHistorySearchService(factory));
      assertTrue(failure.getMessage().contains("MassIndexer"));
      Search.mapping(factory).scope(GitCommitIndex.class).massIndexer().startAndWait();
      assertAllRepositoryHits(factory);
    }

    try (var provider = provider("validate", true, 1)) {
      assertAllRepositoryHits(provider.getSessionFactory());
    }
    try (var provider = provider("validate", false, 1)) {
      var factory = provider.getSessionFactory();
      var search = new GitHistorySearchService(factory);
      for (String repository : REPOSITORIES) {
        assertEquals(1, search.searchCommitText(repository, "routing", 10).size());
      }
    }
  }

  @Test
  void changedShardLayoutWithTheSameTotalCannotServePartialRoutedResults() throws Exception {
    try (var provider = provider("create", true, 2)) {
      persist(provider.getSessionFactory());
    }
    try (var provider = provider("validate", true, 4)) {
      var factory = provider.getSessionFactory();
      assertEquals(
          REPOSITORIES.size(), count(factory, null), "an unrouted count cannot detect this");
      var failure =
          assertThrows(IllegalStateException.class, () -> new GitHistorySearchService(factory));
      assertTrue(failure.getMessage().contains("expected routed documents"));
    }
    try (var provider = provider("validate", true, 4, "rebuilt-indexes")) {
      var factory = provider.getSessionFactory();
      Search.mapping(factory).scope(GitCommitIndex.class).massIndexer().startAndWait();
      assertAllRepositoryHits(factory);
    }
  }

  @Test
  void twoShardsPreserveIsolationThroughRollbackDeletionRebuildAndRestart() throws Exception {
    String deleted = REPOSITORIES.getFirst();
    String rebuilt;
    String newId;
    try (var provider = provider("create", true, 2)) {
      var factory = provider.getSessionFactory();
      persist(factory);
      assertAllRepositoryHits(factory);
      rebuilt = repositorySharingShardWithAnotherSurvivor(deleted);

      SearchRepositoryDeletionParticipant participant = new SearchRepositoryDeletionParticipant();
      try (Session session = factory.openSession()) {
        session.beginTransaction();
        assertEquals(1, participant.deleteRepository(session, new RepositoryName(deleted)));
        session.flush();
        session.getTransaction().rollback();
      }
      assertEquals(1, count(factory, deleted));
      try (Session session = factory.openSession()) {
        session.beginTransaction();
        assertEquals(1, participant.deleteRepository(session, new RepositoryName(deleted)));
        session.getTransaction().commit();
      }
      assertEquals(0, count(factory, deleted));
      for (String remaining : REPOSITORIES.subList(1, REPOSITORIES.size())) {
        assertEquals(1, count(factory, remaining));
      }

      // Model a failure window: the authoritative projection is deleted, but the index retains a
      // document. A rebuild must purge by repository even when it cannot enumerate that row.
      try (Session session = factory.openSession()) {
        session.beginTransaction();
        assertEquals(
            1,
            session
                .createMutationQuery("DELETE FROM GitCommitIndex c WHERE c.repositoryName = :name")
                .setParameter("name", rebuilt)
                .executeUpdate());
        session.getTransaction().commit();
      }
      assertEquals(1, count(factory, rebuilt));
      try (Git git = Git.init().setDirectory(temporaryDirectory.resolve("git").toFile()).call()) {
        Files.writeString(
            git.getRepository().getWorkTree().toPath().resolve("code.txt"), "routing");
        git.add().addFilepattern("code.txt").call();
        newId =
            git.commit()
                .setAuthor("Routing test", "routing@example.invalid")
                .setCommitter("Routing test", "routing@example.invalid")
                .setMessage("routing rebuilt")
                .call()
                .name();
        var result =
            new CommitProjectionRebuilder(factory)
                .rebuild(git.getRepository(), new RepositoryName(rebuilt));
        assertEquals(0, result.removedProjections());
        assertEquals(1, result.indexedCommits());
      }
      assertEquals(1, count(factory, rebuilt));
      assertEquals(4, count(factory, null));
    }

    try (var provider = provider("validate", true, 2)) {
      var factory = provider.getSessionFactory();
      var search = new GitHistorySearchService(factory);
      assertEquals(0, count(factory, deleted));
      assertEquals(4, count(factory, null));
      assertEquals(
          newId, search.searchCommitTextSummaries(rebuilt, "routing", 10).getFirst().objectId());
      for (String untouched : REPOSITORIES) {
        if (untouched.equals(deleted) || untouched.equals(rebuilt)) {
          continue;
        }
        assertEquals(1, count(factory, untouched));
        assertEquals(
            OBJECT_ID,
            search.searchCommitTextSummaries(untouched, "routing", 10).getFirst().objectId());
      }
    }
  }

  private String repositorySharingShardWithAnotherSurvivor(String deleting) throws IOException {
    try (var directories =
        Files.list(temporaryDirectory.resolve("indexes").resolve(ROUTED_INDEX))) {
      for (Path shard : directories.filter(Files::isDirectory).toList()) {
        try (var directory = FSDirectory.open(shard);
            var reader = DirectoryReader.open(directory)) {
          var search = new IndexSearcher(reader);
          List<String> colocated = new ArrayList<>();
          for (String repository : REPOSITORIES) {
            if (!repository.equals(deleting)
                && search.count(new TermQuery(new Term("repositoryName", repository))) == 1) {
              colocated.add(repository);
            }
          }
          if (colocated.size() > 1) {
            return colocated.getFirst();
          }
        }
      }
    }
    throw new AssertionError("Fixture must contain two surviving repositories sharing one shard");
  }

  @Test
  void anInterruptedRepositoryReplayClearsItsCommittedBatchAfterRestart() throws Exception {
    String repository = REPOSITORIES.getFirst();
    List<String> objectIds = new ArrayList<>();
    try (Git git = Git.init().setDirectory(temporaryDirectory.resolve("replay").toFile()).call()) {
      for (int i = 0; i < 3; i++) {
        Files.writeString(
            git.getRepository().getWorkTree().toPath().resolve("code.txt"), "routing " + i);
        git.add().addFilepattern("code.txt").call();
        objectIds.add(
            git.commit()
                .setAuthor("Routing test", "routing@example.invalid")
                .setCommitter("Routing test", "routing@example.invalid")
                .setMessage("routing replay " + i)
                .call()
                .name());
      }
      try (var provider = provider("create", true, 2)) {
        var factory = provider.getSessionFactory();
        persist(factory);
        var rebuilder = new CommitProjectionRebuilder(factory);
        assertThrows(
            InterruptedIOException.class,
            () ->
                rebuilder.rebuild(
                    git.getRepository(),
                    new RepositoryName(repository),
                    progress -> {
                      if (progress.state() == CommitProjectionRebuilder.RebuildState.INDEXING
                          && progress.indexedCommits() == 1) {
                        Thread.currentThread().interrupt();
                      }
                    }));
        assertTrue(Thread.interrupted());
        assertEquals(2, count(factory, repository));
      }
      try (var provider = provider("validate", true, 2)) {
        var factory = provider.getSessionFactory();
        var result =
            new CommitProjectionRebuilder(factory)
                .rebuild(git.getRepository(), new RepositoryName(repository));
        assertEquals(2, result.removedProjections());
        assertEquals(3, result.indexedCommits());
        assertEquals(7, count(factory, null));
        var search = new GitHistorySearchService(factory);
        assertEquals(
            objectIds.stream().sorted().toList(),
            search.searchCommitTextSummaries(repository, "routing", 10).stream()
                .map(hit -> hit.objectId())
                .sorted()
                .toList());
        for (String untouched : REPOSITORIES.subList(1, REPOSITORIES.size())) {
          assertEquals(1, count(factory, untouched));
        }
      }
    }
  }

  private static void assertAllRepositoryHits(SessionFactory factory) {
    var search = new GitHistorySearchService(factory);
    assertEquals(REPOSITORIES.size(), count(factory, null));
    assertEquals(0, count(factory, "unregistered/repository"));
    for (String repository : REPOSITORIES) {
      assertEquals(1, count(factory, repository));
      assertEquals(
          repository,
          search.searchCommitText(repository, "routing", 10).getFirst().getRepositoryName());
      assertEquals(1, search.searchCommitTextSummaries(repository, "routing", 10).size());
      try (var cursor =
          search.scrollChangeSummaries(
              CommitHistoryQuery.forRepository(repository)
                  .matchingText("routing")
                  .unbounded()
                  .build())) {
        assertEquals(OBJECT_ID, cursor.nextChunk().getFirst().objectId());
        assertTrue(cursor.nextChunk().isEmpty());
      }
    }
  }

  private static long count(SessionFactory factory, String routingKey) {
    try (Session session = factory.openSession()) {
      var query = Search.session(session).search(GitCommitIndex.class).where(f -> f.matchAll());
      if (routingKey != null) {
        query.routing(routingKey);
      }
      return query.fetchTotalHitCount();
    }
  }

  private static void persist(SessionFactory factory) {
    try (Session session = factory.openSession()) {
      session.beginTransaction();
      for (String repository : REPOSITORIES) {
        var row = new GitCommitIndex();
        row.setRepositoryName(repository);
        row.setObjectId(OBJECT_ID);
        row.setShortMessage("routing test document");
        row.setFullMessage("routing test document");
        row.setAuthorTime(Instant.EPOCH);
        row.setCommitterTime(Instant.EPOCH);
        row.setChangedPaths("code.txt");
        row.setChangedText("sample code routing");
        session.persist(row);
      }
      session.getTransaction().commit();
    }
  }

  private HibernateSessionFactoryProvider provider(
      String schemaAction, boolean routing, int shards) {
    return provider(schemaAction, routing, shards, "indexes");
  }

  private HibernateSessionFactoryProvider provider(
      String schemaAction, boolean routing, int shards, String indexRoot) {
    return provider(schemaAction, routing, shards, indexRoot, false);
  }

  private HibernateSessionFactoryProvider provider(
      String schemaAction, boolean routing, int shards, String indexRoot, boolean ignoreCase) {
    Properties settings = new Properties();
    settings.put(
        "hibernate.connection.url",
        "jdbc:h2:file:" + temporaryDirectory.resolve("database/search")
            + (ignoreCase ? ";IGNORECASE=TRUE" : ""));
    settings.put("hibernate.connection.driver_class", "org.h2.Driver");
    settings.put("hibernate.hbm2ddl.auto", schemaAction);
    settings.put("hibernate.search.backend.type", "lucene");
    settings.put("hibernate.search.backend.directory.type", "local-filesystem");
    settings.put(
        "hibernate.search.backend.directory.root",
        temporaryDirectory.resolve(indexRoot).toString());
    settings.put("hibernate.search.indexing.plan.synchronization.strategy", "sync");
    settings.put(CommitIndexer.INDEX_BATCH_SIZE_PROPERTY, 2);
    if (routing) {
      settings.put("hibernate.search.mapping.configurer", ROUTING_CONFIGURER);
      if (shards > 1) {
        String prefix = "hibernate.search.backend.indexes." + ROUTED_INDEX + ".sharding.";
        settings.put(prefix + "strategy", "hash");
        settings.put(prefix + "number_of_shards", shards);
      }
    }
    return new HibernateSessionFactoryProvider(settings, SearchEntities.annotatedClasses());
  }
}
