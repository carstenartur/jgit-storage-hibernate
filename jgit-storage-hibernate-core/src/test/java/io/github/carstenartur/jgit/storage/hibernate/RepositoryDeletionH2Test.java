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
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

class RepositoryDeletionH2Test {

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();

  @Test
  void deletionIsIsolatedIdempotentAndRequiresClosedHandlesAcrossFactories() throws Exception {
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    try (HibernateSessionFactoryProvider provider = provider("delete")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      DefaultHibernateRepositoryFactory factory =
          new DefaultHibernateRepositoryFactory(sessionFactory);
      DefaultHibernateRepositoryFactory secondFactory =
          new DefaultHibernateRepositoryFactory(sessionFactory);
      RepositoryName firstName = new RepositoryName("first-repository");
      RepositoryName secondName = new RepositoryName("second-repository");
      ObjectId firstCommit;
      ObjectId secondCommit;

      HibernateGitStorage firstStorage = secondFactory.open(firstName);
      firstCommit = commit(firstStorage.repository(), "first");
      try (HibernateGitStorage secondStorage = factory.open(secondName)) {
        secondCommit = commit(secondStorage.repository(), "second");
      }

      assertThrows(HibernateStorageException.class, () -> factory.deleteRepository(firstName));
      assertEquals(firstCommit, firstStorage.repository().exactRef("refs/heads/main").getObjectId());
      firstStorage.close();

      RepositoryDeletionResult deleted = factory.deleteRepository(firstName);
      assertTrue(deleted.packRows() > 0);
      assertTrue(deleted.reflogRows() > 0);
      assertEquals(0, deleted.projectionRows());
      assertTrue(deleted.deletedAnything());

      assertEquals(new RepositoryDeletionResult(0, 0, 0), factory.deleteRepository(firstName));

      try (HibernateGitStorage reopenedFirst = factory.open(firstName)) {
        assertNull(reopenedFirst.repository().exactRef("refs/heads/main"));
      }
      try (HibernateGitStorage reopenedSecond = factory.open(secondName)) {
        Ref main = reopenedSecond.repository().exactRef("refs/heads/main");
        assertNotNull(main);
        assertEquals(secondCommit, main.getObjectId());
      }
    }
  }

  @Test
  void participantFailureRollsBackCoreAndParticipantDeletes() throws Exception {
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    try (HibernateSessionFactoryProvider provider = provider("rollback")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      RepositoryDeletionParticipant failingParticipant =
          (session, repositoryName) -> {
            session
                .createMutationQuery(
                    "DELETE FROM GitReflogEntity r WHERE r.repositoryName = :repo")
                .setParameter("repo", repositoryName.value())
                .executeUpdate();
            throw new IllegalStateException("projection cleanup failed");
          };
      DefaultHibernateRepositoryFactory factory =
          new DefaultHibernateRepositoryFactory(sessionFactory, List.of(failingParticipant));
      RepositoryName repositoryName = new RepositoryName("rollback-repository");
      ObjectId commitId;
      try (HibernateGitStorage storage = factory.open(repositoryName)) {
        commitId = commit(storage.repository(), "must survive");
      }

      assertThrows(HibernateStorageException.class, () -> factory.deleteRepository(repositoryName));

      try (HibernateGitStorage storage =
          new DefaultHibernateRepositoryFactory(sessionFactory).open(repositoryName)) {
        Ref main = storage.repository().exactRef("refs/heads/main");
        assertNotNull(main);
        assertEquals(commitId, main.getObjectId());
        assertEquals(
            1,
            storage
                .repository()
                .getReflogReader("refs/heads/main")
                .getReverseEntries()
                .size());
      }
    }
  }

  private static ObjectId commit(Repository repository, String message) throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId blob =
          inserter.insert(Constants.OBJ_BLOB, message.getBytes(StandardCharsets.UTF_8));
      TreeFormatter tree = new TreeFormatter();
      tree.append("data.txt", FileMode.REGULAR_FILE, blob);
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(tree));
      PersonIdent actor = new PersonIdent("Deletion Test", "deletion@example.invalid");
      commit.setAuthor(actor);
      commit.setCommitter(actor);
      commit.setMessage(message);
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();

      RefUpdate update = repository.updateRef("refs/heads/main");
      update.setExpectedOldObjectId(ObjectId.zeroId());
      update.setNewObjectId(commitId);
      update.setRefLogIdent(actor);
      update.setRefLogMessage("commit: " + message, false);
      assertEquals(RefUpdate.Result.NEW, update.update());
      return commitId;
    }
  }

  private static HibernateSessionFactoryProvider provider(String purpose) {
    String databaseName =
        "repository-deletion-" + purpose + "-" + TEST_COUNTER.incrementAndGet();
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url", "jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return new HibernateSessionFactoryProvider(properties);
  }
}
