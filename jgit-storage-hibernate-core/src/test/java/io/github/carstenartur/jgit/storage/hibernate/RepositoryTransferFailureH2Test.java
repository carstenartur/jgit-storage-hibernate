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
import io.github.carstenartur.jgit.storage.hibernate.entity.GitRepositoryLifecycleEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitRepositoryLockEntity;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RepositoryTransferFailureH2Test {

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();
  private static final String SOURCE_REF = "refs/heads/main";
  private static final String TARGET_REF = "refs/heads/draft";

  private HibernateSessionFactoryProvider provider;
  private DefaultHibernateRepositoryFactory factory;
  private RepositoryName sourceName;
  private RepositoryName targetName;

  @BeforeEach
  void setUp() {
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    int testId = TEST_COUNTER.incrementAndGet();
    provider =
        new HibernateSessionFactoryProvider(
            h2Properties("repository-transfer-failure-" + testId));
    factory = new DefaultHibernateRepositoryFactory(provider.getSessionFactory());
    sourceName = new RepositoryName("failure-source-" + testId);
    targetName = new RepositoryName("failure-target-" + testId);
  }

  @AfterEach
  void tearDown() {
    if (provider != null) {
      provider.close();
    }
  }

  @Test
  void failedInitialCloneAfterObjectFlushDeletesTargetAndCanBeRetried() throws Exception {
    ObjectId sourceHead;
    try (HibernateGitStorage sourceStorage = factory.open(sourceName)) {
      Repository source = sourceStorage.repository();
      sourceHead = createCommit(source, "source", "source.txt", "source", List.of());
      createRef(source, SOURCE_REF, sourceHead);
    }

    AtomicBoolean injected = new AtomicBoolean();
    DefaultHibernateRepositoryFactory failingFactory =
        factoryWithHook(
            (phase, source, target, request) -> {
              if (phase == RepositoryTransferCheckpointHook.Phase.OBJECTS_FLUSHED
                  && injected.compareAndSet(false, true)) {
                throw new IOException("injected failure after durable object flush");
              }
            });
    RepositoryTransferRequest request =
        RepositoryTransferRequest.initialClone(
            sourceName,
            targetName,
            List.of(new RefTransferSpec(SOURCE_REF, TARGET_REF)));

    assertThrows(HibernateStorageException.class, () -> failingFactory.transfer(request));
    assertTrue(injected.get());
    assertSourceRef(sourceHead);
    assertRepositoryAbsent(targetName);

    RepositoryTransferResult retry = factory.transfer(request);
    assertTrue(retry.targetCreated());
    assertFalse(retry.noOp());
    assertEquals(sourceHead, retry.refs().get(TARGET_REF).targetObjectId());
    assertTargetRef(sourceHead);
  }

  @Test
  void failedIncrementalTransferKeepsRefAndReusesFlushedObjectsOnRetry() throws Exception {
    ObjectId base = createSourceAndInitialClone();
    ObjectId sourceTip = createSourceChild(base, "source-v2", "source-v2.txt", "source-v2");

    AtomicBoolean injected = new AtomicBoolean();
    DefaultHibernateRepositoryFactory failingFactory =
        factoryWithHook(
            (phase, source, target, request) -> {
              if (phase == RepositoryTransferCheckpointHook.Phase.OBJECTS_FLUSHED
                  && injected.compareAndSet(false, true)) {
                throw new IOException("injected incremental failure after durable object flush");
              }
            });
    RepositoryTransferRequest request =
        RepositoryTransferRequest.incrementalFetch(
            sourceName,
            targetName,
            List.of(new RefTransferSpec(SOURCE_REF, TARGET_REF)),
            TargetRefPolicy.FAST_FORWARD_ONLY);

    assertThrows(HibernateStorageException.class, () -> failingFactory.transfer(request));
    assertTrue(injected.get());
    assertTargetRef(base);
    assertTargetHasObject(sourceTip);

    RepositoryTransferResult retry = factory.transfer(request);
    assertFalse(retry.targetCreated());
    assertFalse(retry.noOp());
    assertEquals(0, retry.objectsTransferred());
    assertEquals(0, retry.bytesTransferred());
    assertEquals(sourceTip, retry.refs().get(TARGET_REF).targetObjectId());
    assertTargetRef(sourceTip);
  }

  @Test
  void concurrentTargetWriterIsNeverOverwrittenAndForceRetryCanProceed() throws Exception {
    ObjectId base = createSourceAndInitialClone();
    ObjectId sourceTip = createSourceChild(base, "source-v2", "source-v2.txt", "source-v2");
    ObjectId concurrentTip;
    try (HibernateGitStorage targetStorage = factory.open(targetName)) {
      concurrentTip =
          createCommit(
              targetStorage.repository(),
              "concurrent",
              "concurrent.txt",
              "concurrent",
              List.of(base));
    }

    AtomicBoolean concurrentWrite = new AtomicBoolean();
    DefaultHibernateRepositoryFactory racingFactory =
        factoryWithHook(
            (phase, source, target, request) -> {
              if (phase == RepositoryTransferCheckpointHook.Phase.BEFORE_REF_PUBLICATION
                  && concurrentWrite.compareAndSet(false, true)) {
                updateRef(target, TARGET_REF, base, concurrentTip);
              }
            });
    RepositoryTransferRequest fastForward =
        RepositoryTransferRequest.incrementalFetch(
            sourceName,
            targetName,
            List.of(new RefTransferSpec(SOURCE_REF, TARGET_REF)),
            TargetRefPolicy.FAST_FORWARD_ONLY);

    assertThrows(HibernateStorageException.class, () -> racingFactory.transfer(fastForward));
    assertTrue(concurrentWrite.get());
    assertSourceRef(sourceTip);
    assertTargetRef(concurrentTip);
    assertTargetHasObject(sourceTip);

    RepositoryTransferRequest forceRetry =
        RepositoryTransferRequest.incrementalFetch(
            sourceName,
            targetName,
            List.of(new RefTransferSpec(SOURCE_REF, TARGET_REF, concurrentTip)),
            TargetRefPolicy.FORCE);
    RepositoryTransferResult retry = factory.transfer(forceRetry);

    assertFalse(retry.noOp());
    assertEquals(0, retry.objectsTransferred());
    assertEquals(sourceTip, retry.refs().get(TARGET_REF).targetObjectId());
    assertTargetRef(sourceTip);
  }

  private DefaultHibernateRepositoryFactory factoryWithHook(
      RepositoryTransferCheckpointHook hook) {
    return new DefaultHibernateRepositoryFactory(
        provider.getSessionFactory(), List.of(), hook);
  }

  private ObjectId createSourceAndInitialClone() throws IOException {
    ObjectId base;
    try (HibernateGitStorage sourceStorage = factory.open(sourceName)) {
      Repository source = sourceStorage.repository();
      base = createCommit(source, "base", "base.txt", "base", List.of());
      createRef(source, SOURCE_REF, base);
    }
    factory.transfer(
        RepositoryTransferRequest.initialClone(
            sourceName,
            targetName,
            List.of(new RefTransferSpec(SOURCE_REF, TARGET_REF))));
    return base;
  }

  private ObjectId createSourceChild(
      ObjectId parent, String message, String path, String content) throws IOException {
    try (HibernateGitStorage sourceStorage = factory.open(sourceName)) {
      Repository source = sourceStorage.repository();
      ObjectId child = createCommit(source, message, path, content, List.of(parent));
      updateRef(source, SOURCE_REF, parent, child);
      return child;
    }
  }

  private void assertSourceRef(ObjectId expected) throws IOException {
    try (HibernateGitStorage sourceStorage = factory.open(sourceName)) {
      assertEquals(expected, sourceStorage.repository().exactRef(SOURCE_REF).getObjectId());
    }
  }

  private void assertTargetRef(ObjectId expected) throws IOException {
    try (HibernateGitStorage targetStorage = factory.open(targetName)) {
      assertEquals(expected, targetStorage.repository().exactRef(TARGET_REF).getObjectId());
    }
  }

  private void assertTargetHasObject(ObjectId objectId) throws IOException {
    try (HibernateGitStorage targetStorage = factory.open(targetName)) {
      assertTrue(targetStorage.repository().getObjectDatabase().has(objectId));
    }
  }

  private void assertRepositoryAbsent(RepositoryName repositoryName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      assertNull(
          session.find(GitRepositoryLifecycleEntity.class, repositoryName.value()));
      assertNull(session.find(GitRepositoryLockEntity.class, repositoryName.value()));
      assertEquals(0L, countRows(session, "GitPackEntity", repositoryName));
      assertEquals(0L, countRows(session, "GitReflogEntity", repositoryName));
      assertNotNull(
          session.find(GitRepositoryLifecycleEntity.class, sourceName.value()),
          "source repository must survive target cleanup");
    }
  }

  private static long countRows(
      Session session, String entityName, RepositoryName repositoryName) {
    return session
        .createSelectionQuery(
            "select count(e) from "
                + entityName
                + " e where e.repositoryName = :repositoryName",
            Long.class)
        .setParameter("repositoryName", repositoryName.value())
        .getSingleResult();
  }

  private static ObjectId createCommit(
      Repository repository,
      String message,
      String path,
      String content,
      List<ObjectId> parents)
      throws IOException {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId blobId =
          inserter.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8));
      TreeFormatter tree = new TreeFormatter();
      tree.append(path, FileMode.REGULAR_FILE, blobId);
      ObjectId treeId = inserter.insert(tree);
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(treeId);
      commit.setParentIds(parents);
      commit.setAuthor(new PersonIdent("Transfer Failure Test", "transfer@example.invalid"));
      commit.setCommitter(new PersonIdent("Transfer Failure Test", "transfer@example.invalid"));
      commit.setMessage(message);
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      return commitId;
    }
  }

  private static void createRef(Repository repository, String refName, ObjectId objectId)
      throws IOException {
    RefUpdate update = repository.updateRef(refName);
    update.setExpectedOldObjectId(ObjectId.zeroId());
    update.setNewObjectId(objectId);
    assertEquals(RefUpdate.Result.NEW, update.update());
  }

  private static void updateRef(
      Repository repository, String refName, ObjectId expectedOldId, ObjectId newId)
      throws IOException {
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
