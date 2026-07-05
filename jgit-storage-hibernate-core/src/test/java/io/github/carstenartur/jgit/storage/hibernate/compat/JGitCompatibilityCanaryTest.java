/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.compat;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.ReflogEntry;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Small public-JGit-API canary that is executed for every supported JGit version in CI.
 *
 * <p>The goal is not to test JGit itself. The goal is to fail early when dependency updates create a
 * classpath or binary-compatibility problem around the JGit APIs this storage module relies on.
 */
class JGitCompatibilityCanaryTest {

  @TempDir Path workTree;

  @Test
  void writesReadsUpdatesRefsAndWalksCommitUsingPublicJGitApis() throws Exception {
    try (Git git = Git.init().setDirectory(workTree.toFile()).call();
        Repository repository = git.getRepository()) {
      String content = "hello JGit compatibility\n";
      ObjectId blobId;
      ObjectId treeId;
      ObjectId commitId;

      try (ObjectInserter inserter = repository.newObjectInserter()) {
        blobId = inserter.insert(Constants.OBJ_BLOB, content.getBytes(UTF_8));
        treeId = writeSingleFileTree(inserter, "README.md", blobId);
        commitId = writeCommit(inserter, treeId);
        inserter.flush();
      }

      updateMainBranch(repository, commitId);

      assertEquals(commitId, repository.resolve(Constants.R_HEADS + "main"));
      assertBlobContent(repository, blobId, content);
      assertTree(repository, treeId, blobId);
      assertCommit(repository, commitId, treeId);
      assertReflog(repository, commitId);
    }
  }

  private static ObjectId writeSingleFileTree(
      ObjectInserter inserter, String fileName, ObjectId blobId) throws Exception {
    TreeFormatter formatter = new TreeFormatter();
    formatter.append(fileName, FileMode.REGULAR_FILE, blobId);
    return inserter.insert(formatter);
  }

  private static ObjectId writeCommit(ObjectInserter inserter, ObjectId treeId) throws Exception {
    PersonIdent ident = new PersonIdent("JGit Compatibility", "compatibility@example.invalid");
    CommitBuilder builder = new CommitBuilder();
    builder.setTreeId(treeId);
    builder.setAuthor(ident);
    builder.setCommitter(ident);
    builder.setMessage("Initial compatibility canary");
    return inserter.insert(builder);
  }

  private static void updateMainBranch(Repository repository, ObjectId commitId) throws Exception {
    RefUpdate update = repository.updateRef(Constants.R_HEADS + "main");
    update.setExpectedOldObjectId(ObjectId.zeroId());
    update.setNewObjectId(commitId);
    update.setRefLogMessage("compatibility canary", false);
    assertEquals(RefUpdate.Result.NEW, update.update());
  }

  private static void assertBlobContent(Repository repository, ObjectId blobId, String expected)
      throws Exception {
    try (ObjectReader reader = repository.newObjectReader()) {
      ObjectLoader loader = reader.open(blobId, Constants.OBJ_BLOB);
      assertEquals(expected, new String(loader.getBytes(), UTF_8));
    }
  }

  private static void assertTree(Repository repository, ObjectId treeId, ObjectId blobId)
      throws Exception {
    try (TreeWalk treeWalk = new TreeWalk(repository)) {
      treeWalk.addTree(treeId);
      treeWalk.setRecursive(true);
      assertTrue(treeWalk.next());
      assertEquals("README.md", treeWalk.getPathString());
      assertEquals(blobId, treeWalk.getObjectId(0));
      assertFalse(treeWalk.next());
    }
  }

  private static void assertCommit(Repository repository, ObjectId commitId, ObjectId treeId)
      throws Exception {
    try (RevWalk revWalk = new RevWalk(repository)) {
      RevCommit commit = revWalk.parseCommit(commitId);
      assertEquals("Initial compatibility canary", commit.getFullMessage());
      assertEquals(treeId, commit.getTree().getId());
    }
  }

  private static void assertReflog(Repository repository, ObjectId commitId) throws Exception {
    ReflogEntry entry = repository.getReflogReader(Constants.R_HEADS + "main").getLastEntry();
    assertNotNull(entry);
    assertEquals(commitId, entry.getNewId());
  }
}
