/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.zip.DeflaterOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ReflogEntry;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConsumerBenchmarkFixturesTest {
  @TempDir Path directory;

  @Test
  void audioPublicationsKeepRepeatablePayloadsReachableApartFromMetadata() throws Exception {
    try (Repository first = bare("audio-a");
        Repository replay = bare("audio-b")) {
      int payloadBytes = 256 * 1024 + 17;
      var initial = ConsumerBenchmarkFixtures.publishAudio(first, 7, 0, payloadBytes);
      var latest = ConsumerBenchmarkFixtures.publishAudio(first, 7, 1, payloadBytes);
      ConsumerBenchmarkFixtures.publishAudio(replay, 7, 0, payloadBytes);
      assertEquals(latest, ConsumerBenchmarkFixtures.publishAudio(replay, 7, 1, payloadBytes));

      assertEquals(latest.metadataCommit(), first.resolve("refs/heads/main"));
      assertEquals(latest.metadataCommit(), first.resolve(Constants.HEAD));
      assertEquals(latest.artifactCommit(), first.resolve("refs/heads/artifacts"));
      assertEquals(initial.metadataCommit(), first.resolve("refs/heads/main^"));
      assertEquals(initial.artifactCommit(), first.resolve("refs/heads/artifacts^"));
      assertEquals(latest.payload(), blob(first, latest.artifactCommit(), "artifact.bin"));
      assertEquals(initial.payload(), blob(first, initial.artifactCommit(), "artifact.bin"));
      assertNotEquals(initial.payload(), latest.payload());
      assertNull(blob(first, latest.metadataCommit(), "artifact.bin"));
      assertEquals(
          "{\"artifact\":\"" + latest.payload().name() + "\",\"bytes\":262161,\"publication\":1}\n",
          text(first, latest.metadataCommit(), "metadata/latest.json"));

      byte[] payload = first.open(latest.payload()).getBytes();
      assertEquals(payloadBytes, payload.length);
      var compressed = new ByteArrayOutputStream();
      try (var deflater = new DeflaterOutputStream(compressed)) {
        deflater.write(payload);
      }
      assertTrue(compressed.size() > payloadBytes * 0.99, "fixture must resist compression");
    }
  }

  @Test
  void rejectedAudioPublicationCanBeRetriedWithoutAdvancingTheArtifactBranch()
      throws Exception {
    try (Repository locked = bare("audio-locked");
        Repository reference = bare("audio-reference")) {
      Path lock = locked.getDirectory().toPath().resolve("refs/heads/main.lock");
      Files.createDirectories(lock.getParent());
      Files.createFile(lock);
      try {
        assertThrows(
            IOException.class, () -> ConsumerBenchmarkFixtures.publishAudio(locked, 7, 0, 4096));
        assertNull(locked.resolve("refs/heads/main"));
        assertNull(locked.resolve("refs/heads/artifacts"));
      } finally {
        Files.delete(lock);
      }
      assertEquals(
          ConsumerBenchmarkFixtures.publishAudio(reference, 7, 0, 4096),
          ConsumerBenchmarkFixtures.publishAudio(locked, 7, 0, 4096));
    }
  }

  @Test
  void taxonomyWorkspacesHaveExactCommitCountsMergesAndReflogs() throws Exception {
    try (Repository first = bare("taxonomy-a");
        Repository replay = bare("taxonomy-b");
        Repository other = bare("taxonomy-c")) {
      ObjectId head = ConsumerBenchmarkFixtures.createTaxonomyHistory(first, 3, 13);
      assertEquals(head, first.resolve(Constants.HEAD));
      assertEquals(head, ConsumerBenchmarkFixtures.createTaxonomyHistory(replay, 3, 13));
      ObjectId otherHead = ConsumerBenchmarkFixtures.createTaxonomyHistory(other, 4, 13);
      assertNotEquals(head, otherHead);
      assertEquals(13, reachableCommits(other, otherHead));
      assertEquals(13, reachableCommits(first, head));

      ObjectId proposal = first.resolve("refs/heads/proposals");
      try (var walk = new RevWalk(first)) {
        RevCommit merge = walk.parseCommit(head);
        assertEquals(2, merge.getParentCount());
        assertEquals(proposal, merge.getParent(1));
        assertEquals("reviewer@example.invalid", merge.getCommitterIdent().getEmailAddress());
        assertTrue(merge.getFullMessage().contains("Merge proposal"));
      }
      var mainLog = reflog(first, "refs/heads/main");
      var replayLog = reflog(replay, "refs/heads/main");
      assertEquals(11, mainLog.size());
      assertEquals(
          mainLog.stream().map(ReflogEntry::getNewId).toList(),
          replayLog.stream().map(ReflogEntry::getNewId).toList());
      assertEquals(head, mainLog.getFirst().getNewId());
      assertEquals("reviewer@example.invalid", mainLog.getFirst().getWho().getEmailAddress());
      assertEquals("Merge proposal into workspace", mainLog.getFirst().getComment());
      assertEquals(2, reflog(first, "refs/heads/proposals").size());
      assertEquals(
          blob(first, proposal, "requirements/proposal.json"),
          blob(first, head, "requirements/proposal.json"));
      assertTrue(
          text(first, head, "requirements/accessibility.json").contains("accessibility baseline"));
    }
  }

  @Test
  void taxonomyHistorySpansMultipleDfsPacksWithoutLeavingATrailingBranchUnmerged()
      throws Exception {
    try (var repository = new InMemoryRepository(new DfsRepositoryDescription("workspace"))) {
      repository.create(true);
      ObjectId head = ConsumerBenchmarkFixtures.createTaxonomyHistory(repository, 1, 67);
      assertEquals(head, repository.resolve("refs/heads/main"));
      assertEquals(67, reachableCommits(repository, head));
      assertTrue(repository.getObjectDatabase().getPacks().length > 1);
      ObjectId mergedProposal = repository.resolve("refs/heads/main~6^2");
      assertNotNull(mergedProposal);
      assertEquals(mergedProposal, repository.resolve("refs/heads/proposals"));
    }
  }

  @Test
  void sandboxHistoryRenamesSymbolsAndPreservesUnchangedSourcesOnTheFinalEdit()
      throws Exception {
    try (Repository first = bare("sandbox-a");
        Repository replay = bare("sandbox-b")) {
      ObjectId head = ConsumerBenchmarkFixtures.createSandboxHistory(first, 4, 3, 5);
      assertEquals(head, first.resolve(Constants.HEAD));
      assertEquals(head, ConsumerBenchmarkFixtures.createSandboxHistory(replay, 4, 3, 5));
      assertEquals(5, reachableCommits(first, head));

      String root = "src/main/java/example/sandbox/";
      ObjectId initial = first.resolve("refs/heads/main~4");
      ObjectId methodRename = first.resolve("refs/heads/main~3");
      ObjectId classRename = first.resolve("refs/heads/main~2");
      ObjectId packageMove = first.resolve("refs/heads/main^");
      assertTrue(text(first, initial, root + "CleanupTask0.java").contains("getValue()"));
      assertTrue(text(first, methodRename, root + "CleanupTask0.java").contains("int value()"));
      assertNull(blob(first, classRename, root + "CleanupTask0.java"));
      assertTrue(
          text(first, classRename, root + "RefactoredTask0.java")
              .contains("class RefactoredTask0"));
      assertNull(blob(first, packageMove, root + "RefactoredTask0.java"));
      String moved = root + "cleaned/RefactoredTask0.java";
      assertTrue(text(first, packageMove, moved).contains("package example.sandbox.cleaned;"));
      assertTrue(
          text(first, head, root + "CleanupClient.java")
              .contains("example.sandbox.cleaned.RefactoredTask0.value()"));
      assertEquals(
          blob(first, packageMove, root + "CleanupClient.java"),
          blob(first, head, root + "CleanupClient.java"));
      assertEquals(
          blob(first, initial, root + "CleanupTask2.java"),
          blob(first, head, root + "CleanupTask2.java"));
      assertNotEquals(blob(first, packageMove, moved), blob(first, head, moved));
    }
  }

  @Test
  void invalidInputsDoNotPublishRefsAndHistoryCannotOverwriteExistingMain() throws Exception {
    try (Repository repository = bare("validation")) {
      assertThrows(
          IllegalArgumentException.class,
          () -> ConsumerBenchmarkFixtures.publishAudio(repository, 7, 0, 0));
      assertThrows(
          IllegalArgumentException.class,
          () -> ConsumerBenchmarkFixtures.createTaxonomyHistory(repository, 1, 0));
      assertThrows(
          IllegalArgumentException.class,
          () -> ConsumerBenchmarkFixtures.createSandboxHistory(repository, 1, 0, 5));
      assertNull(repository.resolve("refs/heads/main"));
      assertNull(repository.resolve("refs/heads/artifacts"));

      ObjectId head = ConsumerBenchmarkFixtures.createSandboxHistory(repository, 1, 2, 5);
      assertThrows(
          IllegalArgumentException.class,
          () -> ConsumerBenchmarkFixtures.createTaxonomyHistory(repository, 1, 13));
      assertEquals(head, repository.resolve("refs/heads/main"));
      assertNull(repository.resolve("refs/heads/proposals"));
    }
  }

  @Test
  void hibernateStorageWithSearchDisabledReplaysTheSameFixtureObjectsAsTheFilesystem()
      throws Exception {
    Properties properties = new Properties();
    properties.setProperty("hibernate.connection.url", "jdbc:hsqldb:mem:consumer-fixtures");
    properties.setProperty("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver");
    properties.setProperty("hibernate.hbm2ddl.auto", "create-drop");
    properties.setProperty("hibernate.search.enabled", "false");
    try (var provider = new HibernateSessionFactoryProvider(properties);
        Repository audio = hibernate(provider, "audio");
        Repository taxonomy = hibernate(provider, "taxonomy");
        Repository sandbox = hibernate(provider, "sandbox");
        Repository audioFile = bare("audio-reference");
        Repository taxonomyFile = bare("taxonomy-reference");
        Repository sandboxFile = bare("sandbox-reference")) {
      long largePayload = 2 * 1024 * 1024 + 17L;
      var audioPublication = ConsumerBenchmarkFixtures.publishAudio(audio, 7, 0, largePayload);
      assertEquals(
          ConsumerBenchmarkFixtures.publishAudio(audioFile, 7, 0, largePayload), audioPublication);
      assertEquals(audioPublication.metadataCommit(), audio.resolve(Constants.HEAD));
      try (var stream = audio.open(audioPublication.payload()).openStream();
          var formatter = new ObjectInserter.Formatter()) {
        assertEquals(largePayload, stream.getSize());
        assertEquals(
            audioPublication.payload(), formatter.idFor(Constants.OBJ_BLOB, largePayload, stream));
      }
      assertEquals(
          ConsumerBenchmarkFixtures.createTaxonomyHistory(taxonomyFile, 4, 9),
          ConsumerBenchmarkFixtures.createTaxonomyHistory(taxonomy, 4, 9));
      assertEquals(8, reflog(taxonomy, "refs/heads/main").size());
      assertEquals(
          ConsumerBenchmarkFixtures.createSandboxHistory(sandboxFile, 4, 3, 5),
          ConsumerBenchmarkFixtures.createSandboxHistory(sandbox, 4, 3, 5));
    }
  }

  private static Repository hibernate(HibernateSessionFactoryProvider provider, String name)
      throws IOException {
    Repository repository = HibernateRepository.create(provider.getSessionFactory(), name);
    repository.create(true);
    return repository;
  }

  // HibernateRepository exposes the durable SQL reflog through this repository method.
  @SuppressWarnings("deprecation")
  private static List<ReflogEntry> reflog(Repository repository, String name) throws IOException {
    return repository.getReflogReader(name).getReverseEntries();
  }

  private Repository bare(String name) throws IOException {
    Repository repository =
        new FileRepositoryBuilder().setGitDir(directory.resolve(name).toFile()).setBare().build();
    repository.create(true);
    return repository;
  }

  private static int reachableCommits(Repository repository, ObjectId head) throws IOException {
    try (var walk = new RevWalk(repository)) {
      walk.markStart(walk.parseCommit(head));
      int count = 0;
      for (RevCommit ignored : walk) {
        count++;
      }
      return count;
    }
  }

  private static ObjectId blob(Repository repository, ObjectId commit, String path)
      throws IOException {
    try (var walk = new RevWalk(repository);
        var tree = TreeWalk.forPath(repository, path, walk.parseCommit(commit).getTree())) {
      return tree == null ? null : tree.getObjectId(0);
    }
  }

  private static String text(Repository repository, ObjectId commit, String path)
      throws IOException {
    ObjectId id = blob(repository, commit, path);
    assertNotNull(id, path);
    return new String(repository.open(id).getBytes(), StandardCharsets.UTF_8);
  }
}
