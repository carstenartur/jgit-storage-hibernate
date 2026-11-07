/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;

/** Shared real-history assertions executed on each supported database driver. */
final class ExactRepositoryQueryContract {

  private ExactRepositoryQueryContract() {}

  static void verify(HibernateSessionFactoryProvider provider, Path directory) throws Exception {
    String prefix = "exact-" + UUID.randomUUID() + "/";
    List<String> names = List.of(prefix + "team/beta", prefix + "Team/Beta",
        prefix + "team/beta ", prefix + "Grüße-東京\\path-😀", prefix + "grüße-東京\\path-😀");
    List<List<String>> expected = new ArrayList<>();
    for (int repository = 0; repository < names.size(); repository++) {
      String name = names.get(repository);
      Path workTree = directory.resolve("git-" + repository);
      List<String> ids = new ArrayList<>();
      try (var git = Git.init().setDirectory(workTree.toFile()).call()) {
        var indexer = new CommitIndexer(provider.getSessionFactory(), name);
        for (int commit = 0; commit < 3; commit++) {
          Files.writeString(workTree.resolve("change.txt"), name + "\n" + commit);
          git.add().addFilepattern("change.txt").call();
          Instant when = Instant.parse("2026-11-07T00:00:00Z").plusSeconds(10L * commit + repository);
          var person = new PersonIdent("Exact identity", "exact@example.invalid", when, ZoneOffset.UTC);
          var id = git.commit().setAuthor(person).setCommitter(person)
              .setMessage(name + " change " + commit).call();
          indexer.indexCommit(git.getRepository(), id);
          ids.add(id.name());
        }
      }
      Collections.reverse(ids);
      expected.add(ids);
    }

    var search = new GitHistorySearchService(provider.getSessionFactory());
    for (int repository : List.of(0, 1, 3, 4)) {
      String name = names.get(repository);
      List<String> ids = expected.get(repository);
      var page = CommitHistoryQuery.forRepository(name).offset(1).limit(2).build();
      var entities = search.findChanges(page);
      assertEquals(ids.subList(1, 3), entities.stream().map(GitCommitIndex::getObjectId).toList());
      assertTrue(entities.stream().allMatch(row -> row.getRepositoryName().equals(name)));

      var statistics = provider.getSessionFactory().getStatistics();
      statistics.clear();
      assertEquals(ids.subList(1, 3), hitIds(search.findChangeSummaries(page)));
      assertEquals(0L, statistics.getEntityLoadCount(), "summary pages must not load entity payloads");
      assertEquals(1L, statistics.getQueryExecutionCount(), "offset remains one SQL page query");

      var query = CommitHistoryQuery.forRepository(name).limit(3).build();
      try (var cursor = search.scrollChangeSummaries(query, 2)) {
        assertEquals(ids.subList(0, 2), hitIds(cursor.nextChunk()));
        assertEquals(ids.subList(2, 3), hitIds(cursor.nextChunk()));
        assertTrue(cursor.nextChunk().isEmpty());
        assertTrue(cursor.isClosed());
      }
      assertEquals(0L, statistics.getEntityLoadCount(), "summary cursors must not load entity payloads");
      assertEquals(3, search.countIndexedCommits(name));
    }
    // CommitHistoryQuery already trims its repository argument. That existing contract is
    // unchanged, but the stored trailing-space identity must not contaminate the base query.
    assertEquals(expected.getFirst(), hitIds(search.findChangeSummaries(
        CommitHistoryQuery.forRepository(names.get(2)).build())));
    assertEquals(3, search.countIndexedCommits(names.get(2)), "the raw count API preserves spaces");
    var absentCase = CommitHistoryQuery.forRepository(prefix + "TEAM/BETA").build();
    assertTrue(search.findChangeSummaries(absentCase).isEmpty());
    assertTrue(search.findChanges(absentCase).isEmpty());
    assertEquals(0, search.countIndexedCommits(prefix + "TEAM/BETA"));
  }

  private static List<String> hitIds(List<CommitSearchHit> hits) {
    return hits.stream().map(CommitSearchHit::objectId).toList();
  }
}
