/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCache;
import org.eclipse.jgit.internal.storage.dfs.DfsBlockCacheConfig;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RepositoryTransferH2Test {

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();

  private HibernateSessionFactoryProvider provider;
  private DefaultHibernateRepositoryFactory factory;
  private RepositoryName sourceName;
  private RepositoryName targetName;

  @BeforeEach
  void setUp() {
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    int testId = TEST_COUNTER.incrementAndGet();
    provider = new HibernateSessionFactoryProvider(h2Properties("transfer-h2-" + testId));
    factory = new DefaultHibernateRepositoryFactory(provider.getSessionFactory());
    sourceName = new RepositoryName("source-" + testId);
    targetName = new RepositoryName("target-" + testId);
  }

  @AfterEach
  void tearDown() {
    if (provider != null) {
      provider.close();
    }
  }

  @Test
  void initialClonePreservesMergeAncestryTagAndIndependentTargetStorage() throws Exception {
    SourceFixture fixture;
    try (HibernateGitStorage sourceStorage = factory.open(sourceName)) {
      fixture = createSourceFixture(sourceStorage.repository());
    }

    RepositoryTransferResult result =
        factory.transfer(
            RepositoryTransferRequest.initialClone(
                sourceName,
                targetName,
                List.of(
                    new RefTransferSpec("refs/heads/main", "refs/heads/draft"),
                    new RefTransferSpec("refs/tags/v1", "refs/tags/source-v1"))));

    assertEquals(sourceName, result.source());
    assertEquals(targetName, result.target());
    assertTrue(result.targetCreated());
    assertFalse(result.noOp());
    assertTrue(result.objectsVisited() > 0);
    assertEquals(result.objectsVisited(), result.objectsTransferred());
    assertTrue(result.bytesTransferred() > 0);
    assertEquals(fixture.merge(), result.refs().get("refs/heads/draft").targetObjectId());
    assertEquals(fixture.tag(), result.refs().get("refs/tags/source-v1").targetObjectId());

    assertTargetGraph(fixture);

    RepositoryDeletionResult deletion = factory.deleteRepository(sourceName);
    assertTrue(deletion.deletedAnything());

    assertTargetGraph(fixture);
  }

  @Test
  void initialCloneRejectsTargetWithUnrelatedRefsWithoutOverwritingThem() throws Exception {
    ObjectId sourceHead;
    try (HibernateGitStorage sourceStorage = factory.open(sourceName)) {
      Repository source = sourceStorage.repository();
      sourceHead = createCommit(source, "source", "source.txt", "source", List.of());
      createRef(source, "refs/heads/main", sourceHead);
    }

    ObjectId unrelated;
    try (HibernateGitStorage targetStorage = factory.open(targetName)) {
      Repository target = targetStorage.repository();
      unrelated = createCommit(target, "unrelated", "target.txt", "target", List.of());
      createRef(target, "refs/heads/existing", unrelated);
    }

    RepositoryTransferRequest request =
        RepositoryTransferRequest.initialClone(
            sourceName,
            targetName,
            List.of(new RefTransferSpec("refs/heads/main", "refs/heads/draft")));
    assertThrows(HibernateStorageException.class, () -> factory.transfer(request));

    try (HibernateGitStorage targetStorage = factory.open(targetName)) {
      Repository target = targetStorage.repository();
      assertEquals(unrelated, target.exactRef("refs/heads/existing").getObjectId());
      assertNull(target.exactRef("refs/heads/draft"));
      assertFalse(target.getObjectDatabase().has(sourceHead));
    }
  }

  @Test
  void initialCloneRejectsNonCreateTargetPolicies() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RepositoryTransferRequest(
                sourceName,
                targetName,
                List.of(new RefTransferSpec("refs/heads/main", "refs/heads/draft")),
                RepositoryTransferMode.INITIAL_CLONE,
                TargetRefPolicy.FAST_FORWARD_ONLY,
                true));
  }

  private SourceFixture createSourceFixture(Repository source) throws Exception {
    ObjectId root = createCommit(source, "root", "root.txt", "root", List.of());
    ObjectId main = createCommit(source, "main", "main.txt", "main", List.of(root));
    ObjectId side = createCommit(source, "side", "side.txt", "side", List.of(root));
    ObjectId merge =
        createCommit(source, "merge", "merge.txt", "merged", List.of(main, side));
    createRef(source, "refs/heads/main", merge);

    ObjectId tag;
    try (ObjectInserter inserter = source.newObjectInserter()) {
      TagBuilder builder = new TagBuilder();
      builder.setObjectId(merge, Constants.OBJ_COMMIT);
      builder.setTag("v1");
      builder.setTagger(new PersonIdent("Transfer Test", "transfer@example.com"));
      builder.setMessage("version 1");
      tag = inserter.insert(builder);
      inserter.flush();
    }
    createRef(source, "refs/tags/v1", tag);
    return new SourceFixture(root, main, side, merge, tag);
  }

  private void assertTargetGraph(SourceFixture fixture) throws Exception {
    try (HibernateGitStorage targetStorage = factory.open(targetName)) {
      Repository target = targetStorage.repository();
      assertEquals(fixture.merge(), target.exactRef("refs/heads/draft").getObjectId());
      assertEquals(fixture.tag(), target.exactRef("refs/tags/source-v1").getObjectId());

      try (RevWalk walk = new RevWalk(target)) {
        RevCommit merge = walk.parseCommit(fixture.merge());
        assertEquals(2, merge.getParentCount());
        assertEquals(fixture.main(), merge.getParent(0).getId());
        assertEquals(fixture.side(), merge.getParent(1).getId());
        RevCommit root = walk.parseCommit(fixture.root());
        assertEquals(0, root.getParentCount());

        RevObject parsedTag = walk.parseAny(fixture.tag());
        assertTrue(parsedTag instanceof RevTag);
        RevTag tag = (RevTag) parsedTag;
        walk.parseHeaders(tag);
        assertEquals(fixture.merge(), tag.getObject().getId());
      }

      try (RevWalk walk = new RevWalk(target)) {
        RevCommit merge = walk.parseCommit(fixture.merge());
        try (TreeWalk tree = TreeWalk.forPath(target, "merge.txt", merge.getTree())) {
          assertNotNull(tree);
          ObjectLoader loader = target.open(tree.getObjectId(0));
          assertEquals("merged", new String(loader.getBytes(), StandardCharsets.UTF_8));
        }
      }
    }
  }

  private static ObjectId createCommit(
      Repository repository,
      String message,
      String path,
      String content,
      List<ObjectId> parents)
      throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId blobId =
          inserter.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8));
      TreeFormatter tree = new TreeFormatter();
      tree.append(path, FileMode.REGULAR_FILE, blobId);
      ObjectId treeId = inserter.insert(tree);
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(treeId);
      commit.setParentIds(parents);
      commit.setAuthor(new PersonIdent("Transfer Test", "transfer@example.com"));
      commit.setCommitter(new PersonIdent("Transfer Test", "transfer@example.com"));
      commit.setMessage(message);
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      return commitId;
    }
  }

  private static void createRef(Repository repository, String refName, ObjectId objectId)
      throws Exception {
    RefUpdate update = repository.updateRef(refName);
    update.setExpectedOldObjectId(ObjectId.zeroId());
    update.setNewObjectId(objectId);
    assertEquals(RefUpdate.Result.NEW, update.update());
  }

  private static Properties h2Properties(String name) {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return properties;
  }

  private record SourceFixture(
      ObjectId root, ObjectId main, ObjectId side, ObjectId merge, ObjectId tag) {}
}
