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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
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
import org.eclipse.jgit.lib.ReflogEntry;
import org.eclipse.jgit.lib.TreeFormatter;
import org.junit.jupiter.api.Test;

class HibernateRefUpdateReflogH2Test {

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();

  @Test
  void normalRefUpdatesWriteDurableQueryableReflogs() throws Exception {
    DfsBlockCache.reconfigure(new DfsBlockCacheConfig());
    String databaseName = "ref-update-reflog-" + TEST_COUNTER.incrementAndGet();
    String repositoryName = "workflow-history";
    Properties properties = h2Properties(databaseName);
    PersonIdent actor = new PersonIdent("Ref User", "ref-user@example.invalid");
    ObjectId first;
    ObjectId second;
    ObjectId forced;

    try (HibernateSessionFactoryProvider provider =
            new HibernateSessionFactoryProvider(properties);
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      first = createCommit(repository, null, "first");
      assertEquals(
          RefUpdate.Result.NEW,
          update(repository, ObjectId.zeroId(), first, actor, "commit: first", false));

      second = createCommit(repository, first, "second");
      assertEquals(
          RefUpdate.Result.FAST_FORWARD,
          update(repository, first, second, actor, "commit: second", false));

      forced = createCommit(repository, first, "forced branch");
      assertEquals(
          RefUpdate.Result.FORCED,
          update(repository, second, forced, actor, "reset: workflow", true));

      assertEquals(
          RefUpdate.Result.LOCK_FAILURE,
          update(repository, first, second, actor, "must not be logged", true));

      RefUpdate delete = repository.updateRef("refs/heads/main");
      delete.setExpectedOldObjectId(forced);
      delete.setForceUpdate(true);
      delete.setRefLogIdent(actor);
      delete.setRefLogMessage("branch: deleted", true);
      assertEquals(RefUpdate.Result.FORCED, delete.delete());
    }

    try (HibernateSessionFactoryProvider provider =
            new HibernateSessionFactoryProvider(properties);
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      assertNull(repository.exactRef("refs/heads/main"));
      List<ReflogEntry> entries =
          repository.getReflogReader("refs/heads/main").getReverseEntries();
      assertEquals(4, entries.size());
      assertEquals(ObjectId.zeroId(), entries.get(0).getNewId());
      assertEquals(forced, entries.get(0).getOldId());
      assertTrue(entries.get(0).getComment().startsWith("branch: deleted"));
      assertEquals(forced, entries.get(1).getNewId());
      assertEquals(second, entries.get(1).getOldId());
      assertEquals("reset: workflow: forced-update", entries.get(1).getComment());
      assertEquals(second, entries.get(2).getNewId());
      assertEquals(first, entries.get(2).getOldId());
      assertEquals("commit: second: fast-forward", entries.get(2).getComment());
      assertEquals(first, entries.get(3).getNewId());
      assertEquals(ObjectId.zeroId(), entries.get(3).getOldId());
      assertEquals("commit: first: created", entries.get(3).getComment());
      assertTrue(
          entries.stream().noneMatch(entry -> entry.getComment().contains("must not be logged")));
    }
  }

  private static RefUpdate.Result update(
      HibernateRepository repository,
      ObjectId expectedOldId,
      ObjectId newId,
      PersonIdent actor,
      String message,
      boolean force)
      throws Exception {
    RefUpdate update = repository.updateRef("refs/heads/main");
    update.setExpectedOldObjectId(expectedOldId);
    update.setNewObjectId(newId);
    update.setForceUpdate(force);
    update.setRefLogIdent(actor);
    update.setRefLogMessage(message, true);
    return update.update();
  }

  private static ObjectId createCommit(
      HibernateRepository repository, ObjectId parent, String message) throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId blob =
          inserter.insert(Constants.OBJ_BLOB, message.getBytes(StandardCharsets.UTF_8));
      TreeFormatter tree = new TreeFormatter();
      tree.append("workflow.txt", FileMode.REGULAR_FILE, blob);
      CommitBuilder commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(tree));
      if (parent != null) {
        commit.setParentId(parent);
      }
      PersonIdent author = new PersonIdent("Ref User", "ref-user@example.invalid");
      commit.setAuthor(author);
      commit.setCommitter(author);
      commit.setMessage(message);
      ObjectId commitId = inserter.insert(commit);
      inserter.flush();
      return commitId;
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
