/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.lib.TreeFormatter;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

class RepositoryStorageStatisticsH2Test {

  @Test
  void countsDistinctStoredObjectsIncludingUnreachableObjectsWithoutChangingStorage() throws Exception {
    try (var provider = provider()) {
      SessionFactory sessions = provider.getSessionFactory();
      var factory = new DefaultHibernateRepositoryFactory(sessions);
      var name = new RepositoryName("one");
      var other = new RepositoryName("two");
      var statistics = new RepositoryStorageStatistics(sessions);
      ObjectId tip;
      try (var storage = factory.open(name); var second = factory.open(other)) {
        tip = writeHistory(storage.repository());
        assertEquals(new StoredObjectStatistics(0, 0, 0, 0), statistics.objects(other));
      }
      var packsBefore = statistics.packs(name);
      long rowsBefore = totalRows(sessions);
      assertTrue(packsBefore.packCount() > 0);
      assertTrue(packsBefore.totalSizeBytes() > 0);
      assertEquals(new StoredObjectStatistics(1, 1, 2, 1), statistics.objects(name));
      assertEquals(new StoredObjectStatistics(1, 1, 2, 1), statistics.objects(name));
      assertEquals(packsBefore, statistics.packs(name));
      assertEquals(rowsBefore, totalRows(sessions));
      try (var storage = factory.open(name)) {
        assertEquals(tip, storage.repository().resolve("refs/heads/main"));
      }
      var missing = new RepositoryName("missing");
      assertEquals(new PackStorageStatistics(0, 0), statistics.packs(missing));
      assertThrows(HibernateStorageException.class, () -> statistics.objects(missing));
      assertEquals(rowsBefore, totalRows(sessions), "reading a missing repository must not create it");
    }
  }

  @Test
  void packInventoryIncludesExtensionsReftablesAndUnpublishedBytesWithRepositoryIsolation() {
    try (var provider = provider()) {
      SessionFactory sessions = provider.getSessionFactory();
      var factory = new DefaultHibernateRepositoryFactory(sessions);
      var one = new RepositoryName("one");
      var two = new RepositoryName("two");
      try (var first = factory.open(one); var second = factory.open(two)) {
        var statistics = new RepositoryStorageStatistics(sessions);
        var before = statistics.packs(one);
        var otherBefore = statistics.packs(two);
        sessions.inTransaction(session -> {
          session.persist(pack("one", "alpha", "pack", 4, true));
          session.persist(pack("one", "alpha", "idx", 2, true));
          session.persist(pack("one", "beta", "ref", 3, false));
          session.persist(pack("two", "alpha", "pack", 99, true));
        });
        assertEquals(new PackStorageStatistics(before.packCount() + 2, before.totalSizeBytes() + 9),
            statistics.packs(one));
        assertEquals(new PackStorageStatistics(otherBefore.packCount() + 1, otherBefore.totalSizeBytes() + 99),
            statistics.packs(two));
      }
    }
  }

  private static GitPackEntity pack(String repository, String name, String extension, int size, boolean committed) {
    var pack = new GitPackEntity();
    pack.setRepositoryName(repository);
    pack.setPackName(name);
    pack.setPackExtension(extension);
    pack.setFileSize(size);
    pack.setData(new byte[size]);
    pack.setCommitted(committed);
    pack.setCreatedAt(Instant.now());
    return pack;
  }

  private static ObjectId writeHistory(Repository repository) throws Exception {
    try (var inserter = repository.newObjectInserter()) {
      var blob = inserter.insert(Constants.OBJ_BLOB, "reachable".getBytes(StandardCharsets.UTF_8));
      inserter.insert(Constants.OBJ_BLOB, "unreachable".getBytes(StandardCharsets.UTF_8));
      var tree = new TreeFormatter();
      tree.append("data.txt", FileMode.REGULAR_FILE, blob);
      var actor = new PersonIdent("Test", "test@example.invalid");
      var commit = new CommitBuilder();
      commit.setTreeId(inserter.insert(tree));
      commit.setAuthor(actor);
      commit.setCommitter(actor);
      commit.setMessage("Initial");
      var tip = inserter.insert(commit);
      var tag = new TagBuilder();
      tag.setObjectId(tip, Constants.OBJ_COMMIT);
      tag.setTag("orphan-tag");
      tag.setTagger(actor);
      tag.setMessage("unreferenced annotated tag");
      inserter.insert(tag);
      inserter.flush();
      var update = repository.updateRef("refs/heads/main");
      update.setNewObjectId(tip);
      assertEquals(RefUpdate.Result.NEW, update.update());
      return tip;
    }
  }

  private static long totalRows(SessionFactory sessions) {
    return sessions.fromSession(session ->
        session.createQuery("SELECT COUNT(p) FROM GitPackEntity p", Long.class).getSingleResult()
        + session.createQuery("SELECT COUNT(r) FROM GitRepositoryLifecycleEntity r", Long.class).getSingleResult()
        + session.createQuery("SELECT COUNT(r) FROM GitRepositoryLockEntity r", Long.class).getSingleResult());
  }

  private static HibernateSessionFactoryProvider provider() {
    var properties = new Properties();
    properties.put("hibernate.connection.url", "jdbc:h2:mem:statistics-" + UUID.randomUUID());
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    return new HibernateSessionFactoryProvider(properties);
  }
}
