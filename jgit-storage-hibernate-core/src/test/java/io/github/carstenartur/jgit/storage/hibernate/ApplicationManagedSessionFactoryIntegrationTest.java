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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.util.RawParseUtils;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.junit.jupiter.api.Test;

class ApplicationManagedSessionFactoryIntegrationTest {

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();

  @Test
  void applicationEntityAndRepositorySurviveSessionFactoryRestart() throws Exception {
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    String databaseName = "consumer-context-" + TEST_COUNTER.incrementAndGet();
    String repositoryName = "audio-analyzer-workflows";
    Properties properties = h2Properties(databaseName);
    ObjectId commitId;

    try (HibernateSessionFactoryProvider provider = provider(properties)) {
      persistApplicationEntity(provider.getSessionFactory(), "session-1", "ACTIVE");
      try (HibernateGitStorage storage = open(provider.getSessionFactory(), repositoryName)) {
        commitId = commitWorkflow(storage.repository());
      }
    }

    try (HibernateSessionFactoryProvider provider = provider(properties)) {
      ConsumerApplicationEntity applicationEntity =
          loadApplicationEntity(provider.getSessionFactory(), "session-1");
      assertNotNull(applicationEntity);
      assertEquals("ACTIVE", applicationEntity.getStatus());

      try (HibernateGitStorage storage = open(provider.getSessionFactory(), repositoryName)) {
        Repository repository = storage.repository();
        Ref main = repository.exactRef("refs/heads/main");
        assertNotNull(main);
        assertEquals(commitId, main.getObjectId());
        try (RevWalk walk = new RevWalk(repository)) {
          RevCommit commit = walk.parseCommit(commitId);
          assertEquals("Persist workflow snapshot", commit.getFullMessage());
          assertEquals("workflow.session", readFile(repository, commit, "workflow.id"));
          assertEquals("workflow workflow.session\n", readFile(repository, commit, "workflow.dsl"));
        }
      }
    }
  }

  private static HibernateSessionFactoryProvider provider(Properties properties) {
    return new HibernateSessionFactoryProvider(
        properties, List.of(ConsumerApplicationEntity.class));
  }

  private static HibernateGitStorage open(SessionFactory sessionFactory, String repositoryName) {
    return new DefaultHibernateRepositoryFactory(sessionFactory)
        .open(new RepositoryName(repositoryName));
  }

  private static void persistApplicationEntity(
      SessionFactory sessionFactory, String id, String status) {
    try (Session session = sessionFactory.openSession()) {
      Transaction transaction = session.beginTransaction();
      try {
        session.persist(new ConsumerApplicationEntity(id, status));
        transaction.commit();
      } catch (RuntimeException exception) {
        if (transaction.isActive()) {
          transaction.rollback();
        }
        throw exception;
      }
    }
  }

  private static ConsumerApplicationEntity loadApplicationEntity(
      SessionFactory sessionFactory, String id) {
    try (Session session = sessionFactory.openSession()) {
      return session.find(ConsumerApplicationEntity.class, id);
    }
  }

  private static ObjectId commitWorkflow(Repository repository) throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId dsl =
          inserter.insert(
              Constants.OBJ_BLOB, "workflow workflow.session\n".getBytes(StandardCharsets.UTF_8));
      ObjectId workflowId =
          inserter.insert(Constants.OBJ_BLOB, "workflow.session".getBytes(StandardCharsets.UTF_8));
      TreeFormatter tree = new TreeFormatter();
      tree.append("workflow.dsl", FileMode.REGULAR_FILE, dsl);
      tree.append("workflow.id", FileMode.REGULAR_FILE, workflowId);

      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(tree));
      PersonIdent author = new PersonIdent("Audio Analyzer", "audio-analyzer@example.invalid");
      commit.setAuthor(author);
      commit.setCommitter(author);
      commit.setMessage("Persist workflow snapshot");
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();

      RefUpdate update = repository.updateRef("refs/heads/main");
      update.setExpectedOldObjectId(ObjectId.zeroId());
      update.setNewObjectId(commitId);
      RefUpdate.Result result = update.update();
      assertTrue(
          result == RefUpdate.Result.NEW || result == RefUpdate.Result.FAST_FORWARD,
          () -> "unexpected ref update result " + result);
      return commitId;
    }
  }

  private static String readFile(Repository repository, RevCommit commit, String path)
      throws Exception {
    try (TreeWalk walk = TreeWalk.forPath(repository, path, commit.getTree())) {
      assertNotNull(walk);
      return RawParseUtils.decode(repository.open(walk.getObjectId(0)).getBytes());
    }
  }

  private static Properties h2Properties(String databaseName) {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url", "jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "update");
    properties.put("hibernate.show_sql", "false");
    return properties;
  }
}
