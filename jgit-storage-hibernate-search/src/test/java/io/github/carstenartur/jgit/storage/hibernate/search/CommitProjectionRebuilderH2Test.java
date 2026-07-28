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

import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitIndexer;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder.RebuildResult;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
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
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CommitProjectionRebuilderH2Test {

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();

  private HibernateSessionFactoryProvider provider;
  private HibernateRepository repository;
  private String repositoryName;

  @BeforeEach
  void setUp() throws Exception {
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    repositoryName = "rebuild-h2-" + TEST_COUNTER.incrementAndGet();
    provider =
        new HibernateSessionFactoryProvider(
            h2Properties(repositoryName), SearchEntities.annotatedClasses());
    repository = HibernateRepository.create(provider.getSessionFactory(), repositoryName);
    repository.create(true);
  }

  @AfterEach
  void tearDown() {
    if (repository != null) {
      repository.close();
    }
    if (provider != null) {
      provider.close();
    }
  }

  @Test
  void removesStaleProjectionAndRebuildsReachableHistoryIdempotently() throws Exception {
    ObjectId first = createCommit("Initial workflow", "workflow.txt", "version one", null);
    ObjectId second = createCommit("Update workflow", "workflow.txt", "version two", first);
    ObjectId stale = createCommit("Stale projection", "stale.txt", "obsolete", null);
    updateRef("refs/heads/main", second);
    updateRef("refs/heads/old", first);

    new CommitIndexer(provider.getSessionFactory(), repositoryName)
        .indexCommit(repository, stale);
    assertEquals(List.of(stale.name()), projectedObjectIds());

    CommitProjectionRebuilder rebuilder =
        new CommitProjectionRebuilder(provider.getSessionFactory());
    RebuildResult firstResult =
        rebuilder.rebuild(repository, new RepositoryName(repositoryName));

    assertEquals(repositoryName, firstResult.repositoryName());
    assertEquals(2, firstResult.refTips());
    assertEquals(2, firstResult.visitedCommits());
    assertEquals(2, firstResult.indexedCommits());
    assertEquals(1, firstResult.removedProjections());
    assertEquals(List.of(first.name(), second.name()).stream().sorted().toList(), projectedObjectIds());

    GitHistorySearchService search =
        new GitHistorySearchService(provider.getSessionFactory());
    assertEquals(2, search.searchCommitText(repositoryName, "workflow", 10).size());
    assertEquals(0, search.searchCommitText(repositoryName, "stale", 10).size());

    RebuildResult secondResult =
        rebuilder.rebuild(repository, new RepositoryName(repositoryName));
    assertEquals(2, secondResult.removedProjections());
    assertEquals(2, secondResult.indexedCommits());
    assertEquals(List.of(first.name(), second.name()).stream().sorted().toList(), projectedObjectIds());
  }

  private ObjectId createCommit(
      String message, String path, String content, ObjectId parent) throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId blob =
          inserter.insert(Constants.OBJ_BLOB, content.getBytes(StandardCharsets.UTF_8));
      TreeFormatter tree = new TreeFormatter();
      tree.append(path, FileMode.REGULAR_FILE, blob);
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(tree));
      if (parent != null) {
        commit.setParentId(parent);
      }
      PersonIdent actor = new PersonIdent("Projection Rebuild", "rebuild@example.invalid");
      commit.setAuthor(actor);
      commit.setCommitter(actor);
      commit.setMessage(message);
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      return commitId;
    }
  }

  private void updateRef(String refName, ObjectId objectId) throws Exception {
    RefUpdate update = repository.updateRef(refName);
    update.setExpectedOldObjectId(ObjectId.zeroId());
    update.setNewObjectId(objectId);
    assertEquals(RefUpdate.Result.NEW, update.update());
  }

  private List<String> projectedObjectIds() {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT c.objectId FROM GitCommitIndex c WHERE c.repositoryName = :repo",
              String.class)
          .setParameter("repo", repositoryName)
          .getResultList()
          .stream()
          .sorted()
          .toList();
    }
  }

  private static Properties h2Properties(String name) {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-heap");
    return properties;
  }
}
