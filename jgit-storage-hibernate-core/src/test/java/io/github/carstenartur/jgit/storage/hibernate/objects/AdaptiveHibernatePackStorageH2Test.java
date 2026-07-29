/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationBreakdown;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationKind;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;

class AdaptiveHibernatePackStorageH2Test {

  @Test
  void storesSmallPackExtensionsInlineWithoutChunkRows() throws Exception {
    String repositoryName = "inline-" + UUID.randomUUID();
    try (HibernateSessionFactoryProvider provider = provider(repositoryName);
        Repository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      byte[] payload = "small application commit payload".getBytes(StandardCharsets.UTF_8);
      ObjectId objectId = insertBlob(repository, payload);

      try (ObjectReader reader = repository.newObjectReader()) {
        assertArrayEquals(payload, reader.open(objectId).getBytes());
      }

      assertTrue(
          countInlinePackRows(provider, repositoryName) > 0,
          "At least the small PACK/IDX payload must use the inline column");
      assertEquals(
          0L,
          countChunkRows(provider, repositoryName),
          "Small pack extensions must not create separate chunk rows");
    }
  }

  @Test
  void keepsClosedInlinePayloadLocalUntilPackPublication() throws Exception {
    String repositoryName = "inline-stage-" + UUID.randomUUID();
    try (HibernateSessionFactoryProvider provider = provider(repositoryName);
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      HibernateObjDatabase objectDatabase = repository.getObjectDatabase();
      DfsPackDescription description =
          new DfsPackDescription(repository.getDescription(), "manual-pack", PackSource.INSERT);
      byte[] payload = "inline payload is published atomically".getBytes(StandardCharsets.UTF_8);
      StorageOperationBreakdown before = repository.getStorageOperationBreakdown();

      try (DfsOutputStream stream = objectDatabase.writeFile(description, PackExt.PACK)) {
        stream.write(payload, 0, payload.length);
        stream.flush();
        assertEquals(0L, countPackRows(provider, repositoryName, "manual-pack"));
      }
      assertEquals(
          0L,
          countPackRows(provider, repositoryName, "manual-pack"),
          "Closing an unpublished extension must not create a database row");

      description.addFileExt(PackExt.PACK);
      description.setFileSize(PackExt.PACK, payload.length);
      objectDatabase.commitPackImpl(List.of(description), null);

      assertArrayEquals(payload, inlinePayload(provider, repositoryName, "manual-pack"));
      assertEquals(0L, countChunkRows(provider, repositoryName));
      StorageOperationBreakdown delta =
          repository.getStorageOperationBreakdown().minus(before);
      assertEquals(
          0L,
          delta.metrics(StorageOperationKind.PACK_EXTENSION_WRITE).transactionsStarted());
      assertEquals(
          1L, delta.metrics(StorageOperationKind.PACK_PUBLICATION).transactionsStarted());
      assertEquals(1L, delta.metrics(StorageOperationKind.PACK_PUBLICATION).repositoryLocksAcquired());
    }
  }

  @Test
  void keepsLargePayloadsChunkedAndReadable() throws Exception {
    String repositoryName = "chunked-" + UUID.randomUUID();
    try (HibernateSessionFactoryProvider provider = provider(repositoryName);
        Repository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      byte[] payload = new byte[1024 * 1024];
      new Random(42).nextBytes(payload);
      ObjectId objectId = insertBlob(repository, payload);

      try (ObjectReader reader = repository.newObjectReader()) {
        assertArrayEquals(payload, reader.open(objectId).getBytes());
      }

      assertTrue(
          countChunkRows(provider, repositoryName) > 0,
          "A non-compressible one MiB object must retain bounded chunk storage");
      assertTrue(
          countChunkedPackRows(provider, repositoryName) > 0,
          "At least one large pack extension must reference chunk rows");
    }
  }

  private static ObjectId insertBlob(Repository repository, byte[] payload) throws Exception {
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      ObjectId objectId = inserter.insert(Constants.OBJ_BLOB, payload);
      inserter.flush();
      return objectId;
    }
  }

  private static byte[] inlinePayload(
      HibernateSessionFactoryProvider provider, String repositoryName, String packName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT p.data FROM GitPackEntity p WHERE p.repositoryName = :repo "
                  + "AND p.packName = :name AND p.packExtension = 'pack'",
              byte[].class)
          .setParameter("repo", repositoryName)
          .setParameter("name", packName)
          .getSingleResult();
    }
  }

  private static long countPackRows(
      HibernateSessionFactoryProvider provider, String repositoryName, String packName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT count(p.id) FROM GitPackEntity p WHERE p.repositoryName = :repo "
                  + "AND p.packName = :name",
              Long.class)
          .setParameter("repo", repositoryName)
          .setParameter("name", packName)
          .getSingleResult();
    }
  }

  private static long countInlinePackRows(
      HibernateSessionFactoryProvider provider, String repositoryName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT count(p.id) FROM GitPackEntity p WHERE p.repositoryName = :repo "
                  + "AND p.committed = true AND p.data IS NOT NULL",
              Long.class)
          .setParameter("repo", repositoryName)
          .getSingleResult();
    }
  }

  private static long countChunkedPackRows(
      HibernateSessionFactoryProvider provider, String repositoryName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT count(p.id) FROM GitPackEntity p WHERE p.repositoryName = :repo "
                  + "AND p.committed = true AND p.data IS NULL AND p.fileSize > 0",
              Long.class)
          .setParameter("repo", repositoryName)
          .getSingleResult();
    }
  }

  private static long countChunkRows(
      HibernateSessionFactoryProvider provider, String repositoryName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT count(c.id) FROM GitPackChunkEntity c, GitPackEntity p "
                  + "WHERE c.packId = p.id AND p.repositoryName = :repo",
              Long.class)
          .setParameter("repo", repositoryName)
          .getSingleResult();
    }
  }

  private static HibernateSessionFactoryProvider provider(String name) {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.format_sql", "false");
    properties.put("hibernate.connection.pool_size", "2");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    return new HibernateSessionFactoryProvider(properties);
  }
}
