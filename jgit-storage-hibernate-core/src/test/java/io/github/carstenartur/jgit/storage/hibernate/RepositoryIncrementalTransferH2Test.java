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
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RepositoryIncrementalTransferH2Test {

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();

  private HibernateSessionFactoryProvider provider;
  private DefaultHibernateRepositoryFactory factory;
  private RepositoryName sourceName;
  private RepositoryName targetName;

  @BeforeEach
  void setUp() {
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    int testId = TEST_COUNTER.incrementAndGet();
    provider =
        new HibernateSessionFactoryProvider(h2Properties("incremental-transfer-h2-" + testId));
    factory = new DefaultHibernateRepositoryFactory(provider.getSessionFactory());
    sourceName = new RepositoryName("incremental-source-" + testId);
    targetName = new RepositoryName("incremental-target-" + testId);
  }

  @AfterEach
  void tearDown() {
    if (provider != null) {
      provider.close();
    }
  }

  @Test
  void fastForwardTransfersOnlyNewReachableObjectsAndRetryIsNoOp() throws Exception {
    ObjectId base = createSourceAndInitialClone();
    ObjectId sourceTip = createSourceChild(base, "source-tip", "source.txt", "source-v2");

    RepositoryTransferRequest request =
        RepositoryTransferRequest.incrementalFetch(
            sourceName,
            targetName,
            List.of(new RefTransferSpec("refs/heads/main", "refs/heads/draft")),
            TargetRefPolicy.FAST_FORWARD_ONLY);

    RepositoryTransferResult first = factory.transfer(request);
    assertFalse(first.targetCreated());
    assertFalse(first.noOp());
    assertEquals(3, first.objectsVisited());
    assertEquals(3, first.objectsTransferred());
    assertTrue(first.bytesTransferred() > 0);
    assertTargetRef("refs/heads/draft", sourceTip);

    RepositoryTransferResult retry = factory.transfer(request);
    assertTrue(retry.noOp());
    assertEquals(0, retry.objectsVisited());
    assertEquals(0, retry.objectsTransferred());
    assertEquals(0, retry.bytesTransferred());
    assertTargetRef("refs/heads/draft", sourceTip);
  }

  @Test
  void fastForwardRejectsDivergenceWithoutMovingTargetRef() throws Exception {
    ObjectId base = createSourceAndInitialClone();
    ObjectId sourceTip = createSourceChild(base, "source-tip", "source.txt", "source-v2");
    ObjectId targetTip;
    try (HibernateGitStorage targetStorage = factory.open(targetName)) {
      Repository target = targetStorage.repository();
      targetTip = createCommit(target, "target-tip", "target.txt", "target-v2", List.of(base));
      updateRef(target, "refs/heads/draft", base, targetTip);
    }

    RepositoryTransferRequest request =
        RepositoryTransferRequest.incrementalFetch(
            sourceName,
            targetName,
            List.of(new RefTransferSpec("refs/heads/main", "refs/heads/draft")),
            TargetRefPolicy.FAST_FORWARD_ONLY);

    assertThrows(HibernateStorageException.class, () -> factory.transfer(request));
    assertTargetRef("refs/heads/draft", targetTip);

    try (HibernateGitStorage targetStorage = factory.open(targetName)) {
      assertTrue(
          targetStorage.repository().getObjectDatabase().has(sourceTip),
          "a failed ref precondition may leave unreachable canonical objects for retry reuse");
    }
  }

  @Test
  void compareAndSetRejectsStaleExpectedValueBeforeObjectCopy() throws Exception {
    ObjectId base = createSourceAndInitialClone();
    ObjectId sourceTip = createSourceChild(base, "source-tip", "source.txt", "source-v2");
    ObjectId staleExpected = ObjectId.fromString("1111111111111111111111111111111111111111");

    RepositoryTransferRequest request =
        RepositoryTransferRequest.incrementalFetch(
            sourceName,
            targetName,
            List.of(
                new RefTransferSpec(
                    "refs/heads/main", "refs/heads/draft", staleExpected)),
            TargetRefPolicy.COMPARE_AND_SET);

    assertThrows(HibernateStorageException.class, () -> factory.transfer(request));
    assertTargetRef("refs/heads/draft", base);
    try (HibernateGitStorage targetStorage = factory.open(targetName)) {
      assertFalse(targetStorage.repository().getObjectDatabase().has(sourceTip));
    }
  }

  @Test
  void staleRefInMultiRefCasRequestPreventsEveryChangeAndObjectCopy() throws Exception {
    ObjectId base;
    try (HibernateGitStorage sourceStorage = factory.open(sourceName)) {
      Repository source = sourceStorage.repository();
      base = createCommit(source, "base", "base.txt", "base", List.of());
      createRef(source, "refs/heads/main", base);
      createRef(source, "refs/heads/release", base);
    }
    factory.transfer(
        RepositoryTransferRequest.initialClone(
            sourceName,
            targetName,
            List.of(
                new RefTransferSpec("refs/heads/main", "refs/heads/draft"),
                new RefTransferSpec("refs/heads/release", "refs/heads/release-track"))));

    ObjectId mainTip;
    ObjectId releaseTip;
    try (HibernateGitStorage sourceStorage = factory.open(sourceName)) {
      Repository source = sourceStorage.repository();
      mainTip = createCommit(source, "main-tip", "main.txt", "main-v2", List.of(base));
      releaseTip =
          createCommit(source, "release-tip", "release.txt", "release-v2", List.of(base));
      updateRef(source, "refs/heads/main", base, mainTip);
      updateRef(source, "refs/heads/release", base, releaseTip);
    }

    RepositoryTransferRequest request =
        RepositoryTransferRequest.incrementalFetch(
            sourceName,
            targetName,
            List.of(
                new RefTransferSpec("refs/heads/main", "refs/heads/draft", base),
                new RefTransferSpec(
                    "refs/heads/release",
                    "refs/heads/release-track",
                    ObjectId.fromString("2222222222222222222222222222222222222222"))),
            TargetRefPolicy.COMPARE_AND_SET);

    assertThrows(HibernateStorageException.class, () -> factory.transfer(request));
    assertTargetRef("refs/heads/draft", base);
    assertTargetRef("refs/heads/release-track", base);
    try (HibernateGitStorage targetStorage = factory.open(targetName)) {
      Repository target = targetStorage.repository();
      assertFalse(target.getObjectDatabase().has(mainTip));
      assertFalse(target.getObjectDatabase().has(releaseTip));
    }
  }

  @Test
  void compareAndSetAdvancesFastForwardAndRetryIsIdempotent() throws Exception {
    ObjectId base = createSourceAndInitialClone();
    ObjectId sourceTip = createSourceChild(base, "source-tip", "source.txt", "source-v2");

    RepositoryTransferRequest request =
        RepositoryTransferRequest.incrementalFetch(
            sourceName,
            targetName,
            List.of(
                new RefTransferSpec("refs/heads/main", "refs/heads/draft", base)),
            TargetRefPolicy.COMPARE_AND_SET);

    RepositoryTransferResult result = factory.transfer(request);
    assertFalse(result.noOp());
    assertEquals(sourceTip, result.refs().get("refs/heads/draft").targetObjectId());
    assertTargetRef("refs/heads/draft", sourceTip);

    RepositoryTransferResult retry = factory.transfer(request);
    assertTrue(retry.noOp());
    assertTargetRef("refs/heads/draft", sourceTip);
  }

  @Test
  void forceCanMoveDivergedTargetWhenItsExpectedValueStillMatches() throws Exception {
    ObjectId base = createSourceAndInitialClone();
    ObjectId sourceTip = createSourceChild(base, "source-tip", "source.txt", "source-v2");
    ObjectId targetTip;
    try (HibernateGitStorage targetStorage = factory.open(targetName)) {
      Repository target = targetStorage.repository();
      targetTip = createCommit(target, "target-tip", "target.txt", "target-v2", List.of(base));
      updateRef(target, "refs/heads/draft", base, targetTip);
    }

    RepositoryTransferRequest request =
        RepositoryTransferRequest.incrementalFetch(
            sourceName,
            targetName,
            List.of(
                new RefTransferSpec("refs/heads/main", "refs/heads/draft", targetTip)),
            TargetRefPolicy.FORCE);

    RepositoryTransferResult result = factory.transfer(request);
    assertFalse(result.noOp());
    assertEquals(sourceTip, result.refs().get("refs/heads/draft").targetObjectId());
    assertTargetRef("refs/heads/draft", sourceTip);
  }

  @Test
  void incrementalCreateOnlyAddsARefWithoutRecopyingKnownHistory() throws Exception {
    ObjectId base = createSourceAndInitialClone();
    RepositoryTransferRequest request =
        RepositoryTransferRequest.incrementalFetch(
            sourceName,
            targetName,
            List.of(new RefTransferSpec("refs/heads/main", "refs/heads/upstream")),
            TargetRefPolicy.CREATE_ONLY);

    RepositoryTransferResult result = factory.transfer(request);
    assertFalse(result.noOp());
    assertEquals(0, result.objectsVisited());
    assertEquals(0, result.objectsTransferred());
    assertEquals(base, result.refs().get("refs/heads/upstream").targetObjectId());
    assertTargetRef("refs/heads/draft", base);
    assertTargetRef("refs/heads/upstream", base);

    RepositoryTransferResult retry = factory.transfer(request);
    assertTrue(retry.noOp());
  }

  @Test
  void incrementalTransferRequiresAnExistingTarget() throws Exception {
    try (HibernateGitStorage sourceStorage = factory.open(sourceName)) {
      ObjectId sourceHead =
          createCommit(
              sourceStorage.repository(), "source", "source.txt", "source", List.of());
      createRef(sourceStorage.repository(), "refs/heads/main", sourceHead);
    }

    RepositoryTransferRequest request =
        RepositoryTransferRequest.incrementalFetch(
            sourceName,
            targetName,
            List.of(new RefTransferSpec("refs/heads/main", "refs/heads/draft")),
            TargetRefPolicy.CREATE_ONLY);

    assertThrows(HibernateStorageException.class, () -> factory.transfer(request));
  }

  private ObjectId createSourceAndInitialClone() throws Exception {
    ObjectId base;
    try (HibernateGitStorage sourceStorage = factory.open(sourceName)) {
      Repository source = sourceStorage.repository();
      base = createCommit(source, "base", "base.txt", "base", List.of());
      createRef(source, "refs/heads/main", base);
    }
    factory.transfer(
        RepositoryTransferRequest.initialClone(
            sourceName,
            targetName,
            List.of(new RefTransferSpec("refs/heads/main", "refs/heads/draft"))));
    return base;
  }

  private ObjectId createSourceChild(
      ObjectId parent, String message, String path, String content) throws Exception {
    try (HibernateGitStorage sourceStorage = factory.open(sourceName)) {
      Repository source = sourceStorage.repository();
      ObjectId child = createCommit(source, message, path, content, List.of(parent));
      updateRef(source, "refs/heads/main", parent, child);
      return child;
    }
  }

  private void assertTargetRef(String refName, ObjectId expected) throws Exception {
    try (HibernateGitStorage targetStorage = factory.open(targetName)) {
      assertEquals(expected, targetStorage.repository().exactRef(refName).getObjectId());
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

  private static void updateRef(
      Repository repository, String refName, ObjectId expectedOldId, ObjectId newId)
      throws Exception {
    RefUpdate update = repository.updateRef(refName);
    update.setExpectedOldObjectId(expectedOldId);
    update.setNewObjectId(newId);
    assertEquals(RefUpdate.Result.FAST_FORWARD, update.update());
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
}
