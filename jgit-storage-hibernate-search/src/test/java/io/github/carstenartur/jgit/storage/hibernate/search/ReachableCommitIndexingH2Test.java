/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.search;

import static org.junit.jupiter.api.Assertions.*;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitIndexer;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReachableCommitIndexingH2Test {
  private HibernateSessionFactoryProvider provider;
  private InMemoryRepository repository;
  private CommitIndexer indexer;

  @BeforeEach
  void setUp() {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", "jdbc:h2:mem:reachable-" + UUID.randomUUID());
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.generate_statistics", "true");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-heap");
    properties.put("hibernate.search.indexing.plan.synchronization.strategy", "sync");
    properties.put(CommitIndexer.INDEX_BATCH_SIZE_PROPERTY, "3");
    provider = new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses());
    repository = new InMemoryRepository(new DfsRepositoryDescription("repo"));
    indexer = new CommitIndexer(provider.getSessionFactory(), "repo");
  }

  @AfterEach
  void close() {
    if (repository != null) { repository.close(); }
    if (provider != null) { provider.close(); }
  }

  @Test
  void indexesMultipleTipsOnceAndOnlyTheDeltaBeyondCertifiedCompleteHistory() throws Exception {
    ObjectId root = commit("root");
    ObjectId shared = commit("shared", root);
    ObjectId left = commit("left", shared);
    ObjectId right = commit("right", shared);
    ObjectId tag;
    ObjectId nestedTag;
    ObjectId blob;
    try (var inserter = repository.newObjectInserter()) {
      TagBuilder annotated = new TagBuilder();
      annotated.setTag("left");
      annotated.setObjectId(left, Constants.OBJ_COMMIT);
      annotated.setTagger(new PersonIdent("Author", "author@example.invalid"));
      annotated.setMessage("Release");
      tag = inserter.insert(annotated);
      annotated.setTag("nested");
      annotated.setObjectId(tag, Constants.OBJ_TAG);
      nestedTag = inserter.insert(annotated);
      blob = inserter.insert(Constants.OBJ_BLOB, new byte[] {1});
      inserter.flush();
    }
    var initial = indexer.indexReachableCommits(repository, List.of(left, right, tag, nestedTag, blob), List.of());
    assertEquals(4, initial.visitedCommits());
    assertEquals(4, initial.indexedCommits());
    assertEquals(Set.of(left.name(), right.name()), Set.copyOf(initial.completedTipIds()));
    ObjectId merge = commit("merge", left, right);
    var next = indexer.indexReachableCommits(repository, List.of(merge), List.of(left, right));
    assertEquals(1, next.visitedCommits());
    assertEquals(1, next.indexedCommits());
    assertEquals(List.of(merge.name()), next.completedTipIds());
    var repeat = indexer.indexReachableCommits(repository, List.of(merge), List.of(merge));
    assertEquals(0, repeat.visitedCommits());
    assertEquals(0, repeat.indexedCommits());
    assertEquals(List.of(merge.name()), repeat.completedTipIds());
    assertEquals(5, count());
  }

  @Test
  void doesNotInferCompleteAncestryFromAnExistingProjectionAndHandlesForcePushes() throws Exception {
    ObjectId root = commit("root");
    ObjectId middle = commit("middle", root);
    ObjectId tip = commit("tip", middle);
    indexer.indexCommit(repository, tip);
    var repaired = indexer.indexReachableCommits(repository, List.of(tip), List.of());
    assertEquals(3, repaired.visitedCommits());
    assertEquals(2, repaired.indexedCommits());
    ObjectId rewritten = commit("rewritten root");
    var forced = indexer.indexReachableCommits(repository, List.of(rewritten), List.of(tip));
    assertEquals(1, forced.visitedCommits());
    assertEquals(1, forced.indexedCommits());
    assertEquals(4, count());
    var staleBoundary = indexer.indexReachableCommits(repository, List.of(rewritten),
        List.of(ObjectId.fromString("2".repeat(40))));
    assertEquals(1, staleBoundary.visitedCommits());
    assertEquals(0, staleBoundary.indexedCommits());
    assertThrows(org.eclipse.jgit.errors.MissingObjectException.class, () -> indexer.indexReachableCommits(
        repository, List.of(ObjectId.fromString("2".repeat(40))), List.of()));
    assertEquals(4, count());
  }

  @Test
  void followsAnUnknownMergeSideEvenWhenTheMainSideIsComplete() throws Exception {
    ObjectId root = commit("root");
    ObjectId left = commit("left", root);
    ObjectId right = commit("previously unindexed side", root);
    indexer.indexReachableCommits(repository, List.of(left), List.of());
    ObjectId merge = commit("merge", left, right);
    var result = indexer.indexReachableCommits(repository, List.of(merge), List.of(left));
    assertEquals(2, result.visitedCommits());
    assertEquals(2, result.indexedCommits());
    assertEquals(4, count());
  }

  @Test
  void returnsNoCertificateAfterPartialBatchFailureAndRetryCompletesHistory() throws Exception {
    ObjectId tip = commit("root");
    for (int i = 1; i < 8; i++) { tip = commit("history " + i, tip); }
    var persisted = new java.util.concurrent.atomic.AtomicInteger();
    var actualFactory = provider.getSessionFactory();
    var failingFactory = (org.hibernate.SessionFactory) java.lang.reflect.Proxy.newProxyInstance(
        org.hibernate.SessionFactory.class.getClassLoader(), new Class<?>[] {org.hibernate.SessionFactory.class},
        (proxy, method, arguments) -> {
          try {
            Object value = method.invoke(actualFactory, arguments);
            if (!method.getName().equals("openSession")) { return value; }
            var actualSession = (org.hibernate.Session) value;
            return java.lang.reflect.Proxy.newProxyInstance(org.hibernate.Session.class.getClassLoader(),
                new Class<?>[] {org.hibernate.Session.class}, (sessionProxy, operation, inputs) -> {
                  if (operation.getName().equals("persist") && persisted.incrementAndGet() == 4) {
                    throw new IllegalStateException("Injected second batch failure");
                  }
                  try { return operation.invoke(actualSession, inputs); }
                  catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
                });
          } catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
        });
    var failing = new CommitIndexer(failingFactory, "repo");
    ObjectId finalTip = tip;
    assertEquals("Injected second batch failure", assertThrows(IllegalStateException.class,
        () -> failing.indexReachableCommits(repository, List.of(finalTip), List.of())).getMessage());
    assertEquals(3, count());
    var retry = indexer.indexReachableCommits(repository, List.of(tip), List.of());
    assertEquals(8, retry.visitedCommits());
    assertEquals(5, retry.indexedCommits());
    assertEquals(List.of(tip.name()), retry.completedTipIds());
    assertEquals(8, count());
  }

  @Test
  void interruptionDoesNotCertifyAnIncompleteGeneration() throws Exception {
    ObjectId tip = commit("root");
    try {
      Thread.currentThread().interrupt();
      assertThrows(java.io.InterruptedIOException.class,
          () -> indexer.indexReachableCommits(repository, List.of(tip), List.of()));
    } finally {
      Thread.interrupted();
    }
    assertEquals(0, count());
  }

  @Test
  void followupSqlWorkDependsOnTheDeltaRatherThanExistingHistoryLength() throws Exception {
    ObjectId tip = commit("root");
    for (int i = 0; i < 60; i++) { tip = commit("history " + i, tip); }
    indexer.indexReachableCommits(repository, List.of(tip), List.of());
    ObjectId next = commit("one added commit", tip);
    var statistics = provider.getSessionFactory().getStatistics();
    statistics.clear();
    var result = indexer.indexReachableCommits(repository, List.of(next), List.of(tip));
    assertEquals(1, result.visitedCommits());
    assertEquals(1, result.indexedCommits());
    assertTrue(statistics.getQueryExecutionCount() <= 2, "Only the delta batch needs an existence lookup");
    assertEquals(62, count());
  }

  private ObjectId commit(String message, ObjectId... parents) throws Exception {
    try (var inserter = repository.newObjectInserter()) {
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(new TreeFormatter()));
      commit.setParentIds(parents);
      PersonIdent author = new PersonIdent("Author", "author@example.invalid");
      commit.setAuthor(author);
      commit.setCommitter(author);
      commit.setMessage(message);
      ObjectId id = inserter.insert(commit);
      inserter.flush();
      return id;
    }
  }

  private long count() {
    try (var session = provider.getSessionFactory().openSession()) {
      return session.createQuery("select count(c) from GitCommitIndex c where c.repositoryName = 'repo'", Long.class).getSingleResult();
    }
  }
}
