/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.search;

import static org.junit.jupiter.api.Assertions.*;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitHistoryQuery;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

@Testcontainers(disabledWithoutDocker = true)
class SearchOracleQueryIntegrationTest extends SearchDatabaseQueryContract {
  @Container static final OracleContainer ORACLE = OracleSearchTestSupport.container();

  @Override
  protected void migrateSchemas() {
    OracleSearchTestSupport.core(ORACLE).migrate();
    OracleSearchTestSupport.search(ORACLE).migrate();
  }

  @Override
  protected Properties databaseProperties(Path luceneRoot) {
    return OracleSearchTestSupport.properties(ORACLE, luceneRoot);
  }

  @Test
  void indexesEveryProfileFromRealGitHistoryAndRebuildsEmptyMessages() throws Exception {
    migrateSchemas();
    for (var profile :
        io.github.carstenartur.jgit.storage.hibernate.search.profile.SearchIndexingProfile
            .values()) {
      var settings = databaseProperties(temporaryDirectory.resolve(profile.id()));
      settings.put(
          io.github.carstenartur.jgit.storage.hibernate.search.profile.SearchIndexingProfile
              .PROFILE_PROPERTY,
          profile.id());
      String repositoryName = "oracle-profile-" + profile.id();
      org.eclipse.jgit.lib.ObjectId tip;
      try (var provider =
              new HibernateSessionFactoryProvider(settings, SearchEntities.annotatedClasses());
          var repository =
              io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository.create(
                  provider.getSessionFactory(), repositoryName)) {
        repository.create(true);
        try (var inserter = repository.newObjectInserter()) {
          var tree = new org.eclipse.jgit.lib.TreeFormatter();
          for (int i = 0; i < 130; i++) {
            String path = String.format(java.util.Locale.ROOT, "%03d-%s.txt", i, "path".repeat(8));
            String text = i == 129 ? "Unicode 字\n".repeat(600) + "contenttail\n" : "content\n";
            var blob =
                inserter.insert(
                    org.eclipse.jgit.lib.Constants.OBJ_BLOB,
                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            tree.append(path, org.eclipse.jgit.lib.FileMode.REGULAR_FILE, blob);
          }
          var identity = new org.eclipse.jgit.lib.PersonIdent("Älice", "alice.ü@example.org");
          var first = new org.eclipse.jgit.lib.CommitBuilder();
          first.setTreeId(inserter.insert(tree));
          first.setAuthor(identity);
          first.setCommitter(new org.eclipse.jgit.lib.PersonIdent("Build 東京", "build@example.org"));
          first.setMessage("ü".repeat(2048) + "\n\n" + "語 ".repeat(2500) + " messagetail");
          var firstId = inserter.insert(first);
          var empty = new org.eclipse.jgit.lib.CommitBuilder();
          empty.setTreeId(first.getTreeId());
          empty.setParentId(firstId);
          empty.setAuthor(identity);
          empty.setCommitter(identity);
          empty.setMessage("");
          tip = inserter.insert(empty);
          inserter.flush();
        }
        var ref = repository.updateRef("refs/heads/main");
        ref.setNewObjectId(tip);
        assertEquals(org.eclipse.jgit.lib.RefUpdate.Result.NEW, ref.update());
        var indexer =
            new io.github.carstenartur.jgit.storage.hibernate.search.service.CommitIndexer(
                provider.getSessionFactory(), repositoryName);
        assertEquals(2, indexer.indexCommitsFrom(repository, tip, -1));
        assertEquals(0, indexer.indexCommitsFrom(repository, tip, -1));
        indexer.indexCommit(repository, tip);
        var rebuilt =
            new io.github.carstenartur.jgit.storage.hibernate.search.service
                    .CommitProjectionRebuilder(provider.getSessionFactory())
                .rebuild(
                    repository,
                    new io.github.carstenartur.jgit.storage.hibernate.RepositoryName(
                        repositoryName));
        assertEquals(2, rebuilt.removedProjections());
        assertEquals(2, rebuilt.indexedCommits());
      }
      try (var provider =
          new HibernateSessionFactoryProvider(settings, SearchEntities.annotatedClasses())) {
        var search = new GitHistorySearchService(provider.getSessionFactory());
        assertEquals(2, search.countIndexedCommits(repositoryName));
        var row = search.searchCommitText(repositoryName, "messagetail", 10).getFirst();
        assertNotNull(row.getId());
        assertEquals(2048, row.getShortMessage().length());
        assertTrue(row.getFullMessage().endsWith("messagetail"));
        assertEquals(profile.id(), row.getIndexProfile());
        if (profile.indexesPaths()) assertTrue(row.getChangedPaths().length() > 4000);
        if (profile.indexesContent()) {
          assertTrue(row.getChangedText().length() > 4000);
          assertEquals(1, search.searchCommitText(repositoryName, "contenttail", 10).size());
        }
        assertEquals(
            1,
            search.findChanges(CommitHistoryQuery.forRepository(repositoryName).build()).stream()
                .filter(c -> c.getObjectId().equals(tip.name()))
                .count());
      }
    }
  }

  @Test
  void queriesLobTailEscapedPathsEmptyMetadataAndDetachedSummariesAcrossRestart() {
    migrateSchemas();
    var settings = databaseProperties(temporaryDirectory.resolve("long-lucene"));
    String repository = "oracle-long-東京";
    try (var provider =
            new HibernateSessionFactoryProvider(settings, SearchEntities.annotatedClasses());
        var session = provider.getSessionFactory().openSession()) {
      var transaction = session.beginTransaction();
      for (int i = 0; i < 5; i++) {
        var row = new GitCommitIndex();
        row.setRepositoryName(repository);
        row.setObjectId(Integer.toHexString(i).repeat(40));
        row.setShortMessage(i == 0 ? "" : "ü".repeat(2048));
        row.setFullMessage(i == 0 ? "" : "語 ".repeat(2500) + " messagetail");
        row.setAuthorName("");
        row.setAuthorEmail("");
        row.setCommitterName("Build 東京");
        row.setCommitterEmail("build@example.org");
        row.setAuthorTime(Instant.parse("2026-02-01T00:00:00Z").plusSeconds(i));
        row.setCommitterTime(Instant.parse("2026-02-01T01:00:00Z"));
        row.setChangedPaths(i == 0 ? "" : "a".repeat(5000) + "\nÜber_%!.java");
        row.setChangedText(i == 0 ? "" : "字 ".repeat(2500) + " contenttail");
        session.persist(row);
      }
      transaction.commit();
    }
    try (var provider =
        new HibernateSessionFactoryProvider(settings, SearchEntities.annotatedClasses())) {
      var search = new GitHistorySearchService(provider.getSessionFactory());
      assertEquals(4, search.findByPath(repository, "üBER_%!.JAVA", 10).size());
      assertTrue(search.findByPath(repository, "not_a_literal%", 10).isEmpty());
      assertEquals(4, search.searchCommitText(repository, "messagetail", 10).size());
      assertEquals(4, search.searchCommitText(repository, "contenttail", 10).size());
      var query = CommitHistoryQuery.forRepository(repository).offset(1).limit(2).build();
      assertEquals(
          List.of("1".repeat(40), "2".repeat(40)),
          search.findChanges(query).stream().map(GitCommitIndex::getObjectId).toList());
      assertEquals(
          List.of("2".repeat(40)),
          search
              .findAuthoredBetween(
                  repository,
                  Instant.parse("2026-02-01T00:00:02Z"),
                  Instant.parse("2026-02-01T00:00:02Z"),
                  10)
              .stream()
              .map(GitCommitIndex::getObjectId)
              .toList());
      assertTrue(
          search
              .findBetween(
                  repository,
                  Instant.parse("2026-02-01T00:00:02Z"),
                  Instant.parse("2026-02-01T00:00:02Z"),
                  10)
              .isEmpty());
      var summaries = search.findChangeSummaries(query);
      assertEquals(2048, summaries.getFirst().shortMessage().length());
      var all = CommitHistoryQuery.forRepository(repository).limit(10).build();
      int count = 0;
      try (var cursor = search.scrollChangeSummaries(all, 2)) {
        while (true) {
          var chunk = cursor.nextChunk();
          if (chunk.isEmpty()) break;
          assertTrue(chunk.size() <= 2);
          count += chunk.size();
        }
      }
      assertEquals(5, count);
      var empty = search.findChanges(all).getFirst();
      assertTrue(empty.getShortMessage() == null || empty.getShortMessage().isEmpty());
      assertTrue(empty.getFullMessage() == null || empty.getFullMessage().isEmpty());
      assertTrue(empty.getChangedPathValues().isEmpty());
      assertNull(empty.getAuthorName());
      assertNull(empty.getAuthorEmail());
    }
  }
}
