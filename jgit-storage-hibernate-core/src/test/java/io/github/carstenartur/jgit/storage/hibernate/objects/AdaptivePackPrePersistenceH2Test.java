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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitRepositoryLockEntity;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationBreakdown;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationKind;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationMetrics;
import jakarta.persistence.LockModeType;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.dfs.ReadableChannel;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.junit.jupiter.api.Test;

class AdaptivePackPrePersistenceH2Test {

  @Test
  void prePersistsChunkedPayloadWhileRepositoryPublicationLockIsContended() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), "adaptive-visibility")) {
      repository.create(true);
      ReadAheadHibernateObjDatabase database =
          (ReadAheadHibernateObjDatabase) repository.getObjectDatabase();
      DfsPackDescription description = database.newPack(PackSource.RECEIVE);
      byte[] packBytes =
          deterministicBytes(HibernateObjDatabase.INLINE_PAYLOAD_THRESHOLD + 257, 41);
      write(database, description, PackExt.PACK, packBytes);
      String packName = baseName(description);

      StorageOperationMetrics aggregateBefore = repository.getStorageOperationMetrics();
      StorageOperationBreakdown breakdownBefore = repository.getStorageOperationBreakdown();
      CountDownLatch lockAcquired = new CountDownLatch(1);
      CountDownLatch releaseLock = new CountDownLatch(1);
      try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
        Future<?> lockHolder =
            executor.submit(
                () -> holdRepositoryLock(provider, "adaptive-visibility", lockAcquired, releaseLock));
        assertTrue(lockAcquired.await(10, TimeUnit.SECONDS));

        Future<?> publication =
            executor.submit(
                () -> {
                  database.commitPackImpl(List.of(description), null);
                  return null;
                });
        try {
          waitForRowState(provider, "adaptive-visibility", packName, false, 1L);
          assertEquals(0L, rowCount(provider, "adaptive-visibility", packName, true));
          assertThrows(
              FileNotFoundException.class,
              () -> database.openFile(description, PackExt.PACK),
              "Readers must not see a pre-persisted committed=false extension");
        } finally {
          releaseLock.countDown();
        }

        publication.get(20, TimeUnit.SECONDS);
        lockHolder.get(20, TimeUnit.SECONDS);
      }

      assertEquals(0L, rowCount(provider, "adaptive-visibility", packName, false));
      assertEquals(1L, rowCount(provider, "adaptive-visibility", packName, true));
      try (ReadableChannel channel = database.openFile(description, PackExt.PACK)) {
        ByteBuffer destination = ByteBuffer.allocate(packBytes.length);
        while (destination.hasRemaining()) {
          assertTrue(channel.read(destination) > 0);
        }
        assertArrayEquals(packBytes, destination.array());
      }

      StorageOperationMetrics aggregate =
          repository.getStorageOperationMetrics().minus(aggregateBefore);
      StorageOperationBreakdown breakdown =
          repository.getStorageOperationBreakdown().minus(breakdownBefore);
      assertEquals(2, aggregate.transactionsStarted());
      assertEquals(2, aggregate.transactionsCommitted());
      assertEquals(0, aggregate.transactionsRolledBack());
      assertEquals(1, aggregate.repositoryLocksAcquired());
      assertEquals(aggregate, breakdown.total());

      StorageOperationMetrics prePersistence =
          breakdown.metrics(StorageOperationKind.PACK_EXTENSION_WRITE);
      assertEquals(1, prePersistence.transactionsStarted());
      assertEquals(1, prePersistence.transactionsCommitted());
      assertEquals(0, prePersistence.repositoryLocksAcquired());
      assertEquals(0, prePersistence.repositoryLockHeldNanos());

      StorageOperationMetrics publication =
          breakdown.metrics(StorageOperationKind.PACK_PUBLICATION);
      assertEquals(1, publication.transactionsStarted());
      assertEquals(1, publication.transactionsCommitted());
      assertEquals(1, publication.repositoryLocksAcquired());
      assertTrue(publication.repositoryLockHeldNanos() > 0);
      assertEquals(StorageOperationMetrics.ZERO, breakdown.metrics(StorageOperationKind.PACK_ROLLBACK));
    }
  }

  @Test
  void failedFinalPublicationDeletesPreparedRowsAndPayloadChunks() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), "adaptive-rollback")) {
      repository.create(true);
      ReadAheadHibernateObjDatabase database =
          (ReadAheadHibernateObjDatabase) repository.getObjectDatabase();
      long baselineRows = repositoryRowCount(provider, "adaptive-rollback");
      DfsPackDescription description = database.newPack(PackSource.RECEIVE);
      byte[] packBytes =
          deterministicBytes(HibernateObjDatabase.INLINE_PAYLOAD_THRESHOLD + 257, 71);
      write(database, description, PackExt.PACK, packBytes);
      write(database, description, PackExt.INDEX, deterministicBytes(83, 97));
      persistConflictingIndex(provider, "adaptive-rollback", baseName(description));

      StorageOperationMetrics aggregateBefore = repository.getStorageOperationMetrics();
      StorageOperationBreakdown breakdownBefore = repository.getStorageOperationBreakdown();
      IOException failure =
          assertThrows(
              IOException.class, () -> database.commitPackImpl(List.of(description), null));
      assertTrue(failure.getMessage().contains("Pack extension already exists:"));
      assertTrue(failure.getMessage().contains(".idx"));

      assertEquals(0, database.stagedExtensionCount());
      assertEquals(
          baselineRows + 1,
          repositoryRowCount(provider, "adaptive-rollback"),
          "Only the deliberately conflicting committed row may survive");
      assertEquals(0L, uncommittedRowCount(provider, "adaptive-rollback"));
      assertEquals(0L, uncommittedChunkCount(provider, "adaptive-rollback"));

      StorageOperationMetrics aggregate =
          repository.getStorageOperationMetrics().minus(aggregateBefore);
      StorageOperationBreakdown breakdown =
          repository.getStorageOperationBreakdown().minus(breakdownBefore);
      assertEquals(3, aggregate.transactionsStarted());
      assertEquals(2, aggregate.transactionsCommitted());
      assertEquals(1, aggregate.transactionsRolledBack());
      assertEquals(1, aggregate.repositoryLocksAcquired());
      assertEquals(aggregate, breakdown.total());

      assertEquals(
          new StorageOperationMetrics(1, 1, 0, 0, 0, 0, 0)
              .transactionsStarted(),
          breakdown.metrics(StorageOperationKind.PACK_EXTENSION_WRITE).transactionsStarted());
      assertEquals(
          1,
          breakdown.metrics(StorageOperationKind.PACK_PUBLICATION).transactionsRolledBack());
      assertEquals(1, breakdown.metrics(StorageOperationKind.PACK_ROLLBACK).transactionsCommitted());
    }
  }

  private static void holdRepositoryLock(
      HibernateSessionFactoryProvider provider,
      String repositoryName,
      CountDownLatch lockAcquired,
      CountDownLatch releaseLock)
      throws Exception {
    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      GitRepositoryLockEntity lock =
          session.find(
              GitRepositoryLockEntity.class, repositoryName, LockModeType.PESSIMISTIC_WRITE);
      assertNotNull(lock);
      lockAcquired.countDown();
      assertTrue(releaseLock.await(20, TimeUnit.SECONDS));
      transaction.commit();
    }
  }

  private static void waitForRowState(
      HibernateSessionFactoryProvider provider,
      String repositoryName,
      String packName,
      boolean committed,
      long expected)
      throws Exception {
    Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
    while (Instant.now().isBefore(deadline)) {
      if (rowCount(provider, repositoryName, packName, committed) == expected) {
        return;
      }
      Thread.sleep(10);
    }
    assertEquals(expected, rowCount(provider, repositoryName, packName, committed));
  }

  private static long rowCount(
      HibernateSessionFactoryProvider provider,
      String repositoryName,
      String packName,
      boolean committed) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT COUNT(p) FROM GitPackEntity p WHERE p.repositoryName = :repo "
                  + "AND p.packName = :name AND p.committed = :committed",
              Long.class)
          .setParameter("repo", repositoryName)
          .setParameter("name", packName)
          .setParameter("committed", committed)
          .getSingleResult();
    }
  }

  private static long repositoryRowCount(
      HibernateSessionFactoryProvider provider, String repositoryName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT COUNT(p) FROM GitPackEntity p WHERE p.repositoryName = :repo", Long.class)
          .setParameter("repo", repositoryName)
          .getSingleResult();
    }
  }

  private static long uncommittedRowCount(
      HibernateSessionFactoryProvider provider, String repositoryName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT COUNT(p) FROM GitPackEntity p WHERE p.repositoryName = :repo "
                  + "AND p.committed = false",
              Long.class)
          .setParameter("repo", repositoryName)
          .getSingleResult();
    }
  }

  private static long uncommittedChunkCount(
      HibernateSessionFactoryProvider provider, String repositoryName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT COUNT(c) FROM GitPackChunkEntity c WHERE c.packId IN "
                  + "(SELECT p.id FROM GitPackEntity p WHERE p.repositoryName = :repo "
                  + "AND p.committed = false)",
              Long.class)
          .setParameter("repo", repositoryName)
          .getSingleResult();
    }
  }

  private static void persistConflictingIndex(
      HibernateSessionFactoryProvider provider, String repositoryName, String packName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      GitPackEntity entity = new GitPackEntity();
      entity.setRepositoryName(repositoryName);
      entity.setPackName(packName);
      entity.setPackExtension(PackExt.INDEX.getExtension());
      entity.setData(new byte[] {1});
      entity.setFileSize(1);
      entity.setCommitted(true);
      entity.setCreatedAt(Instant.now());
      entity.setCommittedAt(Instant.now());
      session.persist(entity);
      transaction.commit();
    }
  }

  private static void write(
      ReadAheadHibernateObjDatabase database,
      DfsPackDescription description,
      PackExt extension,
      byte[] data)
      throws IOException {
    try (DfsOutputStream stream = database.writeFile(description, extension)) {
      stream.write(data, 0, data.length);
    }
    description.addFileExt(extension);
    description.setFileSize(extension, data.length);
  }

  private static byte[] deterministicBytes(int length, int seed) {
    byte[] result = new byte[length];
    int value = seed;
    for (int index = 0; index < result.length; index++) {
      value = value * 1103515245 + 12345;
      result[index] = (byte) (value >>> 16);
    }
    return result;
  }

  private static String baseName(DfsPackDescription description) {
    String fileName = description.getFileName(PackExt.PACK);
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:adaptive-prepersist-"
            + UUID.randomUUID()
            + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.connection.pool_size", "6");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    return new HibernateSessionFactoryProvider(properties);
  }
}
