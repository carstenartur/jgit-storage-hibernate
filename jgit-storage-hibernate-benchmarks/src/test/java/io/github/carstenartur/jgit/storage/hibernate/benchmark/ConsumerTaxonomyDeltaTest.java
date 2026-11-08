/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConsumerTaxonomyDeltaTest {
  @TempDir Path directory;
  @Test
  void appendsTheSameDeltaAndPreservesExistingFilesRefsAndReflog() throws Exception {
    try (var first = repository(); var second = repository()) {
      ObjectId base = ConsumerBenchmarkFixtures.createTaxonomyHistory(first, 0, 32);
      assertEquals(base, ConsumerBenchmarkFixtures.createTaxonomyHistory(second, 0, 32));
      var oldFiles = files(first, base);
      ObjectId proposal = first.resolve("refs/heads/proposals");
      ObjectId delta = ConsumerBenchmarkFixtures.publishTaxonomyDelta(first, 0, 1, base);
      assertNotEquals(base, delta);
      assertEquals(delta, ConsumerBenchmarkFixtures.publishTaxonomyDelta(second, 0, 1, base));
      assertEquals(delta, first.resolve(Constants.HEAD));
      assertEquals(proposal, first.resolve("refs/heads/proposals"));
      try (var walk = new RevWalk(first)) {
        var commit = walk.parseCommit(delta);
        assertEquals(1, commit.getParentCount());
        assertEquals(base, commit.getParent(0));
        assertEquals("Publish workspace terminology delta 1", commit.getFullMessage());
      }
      var after = files(first, delta);
      assertEquals(oldFiles.size() + 1, after.size());
      oldFiles.forEach((path, id) -> assertEquals(id, after.get(path)));
      assertEquals("{\"workspace\":0,\"publication\":1,\"topic\":\"terminology\"}\n",
          new String(first.open(after.get("requirements/concurrent.json")).getBytes(),
              StandardCharsets.UTF_8));
      var reflog = first.getReflogReader("refs/heads/main").getLastEntry();
      assertEquals(base, reflog.getOldId());
      assertEquals(delta, reflog.getNewId());
    }
  }

  @Test
  void staleExpectedRefCannotOverwriteAnotherPublishedDelta() throws Exception {
    try (var repository = repository()) {
      ObjectId base = ConsumerBenchmarkFixtures.createTaxonomyHistory(repository, 0, 32);
      ObjectId current = ConsumerBenchmarkFixtures.publishTaxonomyDelta(repository, 0, 1, base);
      assertThrows(IOException.class,
          () -> ConsumerBenchmarkFixtures.publishTaxonomyDelta(repository, 0, 2, base));
      assertEquals(current, repository.resolve(Constants.HEAD));
    }
  }

  private Repository repository() throws IOException {
    var repository = new FileRepositoryBuilder()
        .setGitDir(directory.resolve(UUID.randomUUID().toString()).toFile()).setBare().build();
    repository.create(true);
    return repository;
  }

  private static Map<String, ObjectId> files(Repository repository, ObjectId commit) throws IOException {
    Map<String, ObjectId> result = new TreeMap<>();
    try (var walk = new RevWalk(repository); var tree = new TreeWalk(repository)) {
      tree.addTree(walk.parseCommit(commit).getTree());
      tree.setRecursive(true);
      while (tree.next()) result.put(tree.getPathString(), tree.getObjectId(0));
    }
    return result;
  }
}
