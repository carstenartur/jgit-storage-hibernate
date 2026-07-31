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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationBreakdown;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationKind;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationMetrics;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.junit.jupiter.api.Test;

class TwoPhasePackPublicationH2Test {

  @Test
  void prePersistsChunkedLogicalPackInvisiblyBeforeShortAtomicPublication() throws Exception {
    String repositoryName = "two-phase-success";
    byte[] packBytes =
        deterministicBytes(HibernateObjDatabase.INLINE_PAYLOAD_THRESHOLD + 257, 17);
    byte[] indexBytes = deterministicBytes(97, 29);
    AtomicReference<String> observedWriteToken = new AtomicReference<>();

    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      HibernateTransactionContext context =
          new HibernateTransactionContext(provider.getSessionFactory());
      ReadAheadHibernateObjDatabase database =
          (ReadAheadHibernateObjDatabase) repository.getObjectDatabase();
      DfsPackDescription description = database.newPack(PackSource.RECEIVE);
      description.setLastModified(1_723_456_789L);
      description.setObjectCount(23);
      description.setDeltaCount(7);
      description.setIndexVersion(2);

      StagedPackExtensionStore store =
          new StagedPackExtensionStore(
              repositoryName,
              context,
              writeToken -> {
                observedWriteToken.set(writeToken);
                assertPreparedButInvisible(
                    provider,
                    repositoryName,
                    baseName(description),
                    writeToken,
                    packBytes,
                    indexBytes);
              });
      write(store, description, PackExt.PACK, packBytes);
      write(store, description, PackExt.INDEX, indexBytes);

      StorageOperationMetrics before = context.metricsSnapshot();
      StorageOperationBreakdown breakdownBefore = context.operationBreakdownSnapshot();
      StagedPackExtensionStore.CommitResult result =
          store.commit(List.of(description), null);

      assertNotNull(observedWriteToken.get());
      assertTrue(result.completeMetadata());
      assertEquals(2, result.committedExtensions().size());
      assertEquals(0, store.stagedExtensionCount());
      assertPublished(
          provider, repositoryName, baseName(description), packBytes, indexBytes);

      StorageOperationMetrics aggregate = context.metricsSnapshot().minus(before);
      StorageOperationBreakdown breakdown =
          context.operationBreakdownSnapshot().minus(breakdownBefore);
      assertEquals(3, aggregate.transactionsStarted());
      assertEquals(3, aggregate.transactionsCommitted());
      assertEquals(0, aggregate.transactionsRolledBack());
      assertEquals(2, aggregate.repositoryLocksAcquired());
      assertEquals(aggregate, breakdown.total());

      StorageOperationMetrics writes =
          breakdown.metrics(StorageOperationKind.PACK_EXTENSION_WRITE);
      assertEquals(2, writes.transactionsStarted());
      assertEquals(2, writes.transactionsCommitted());
      assertEquals(0, writes.transactionsRolledBack());
      assertEquals(1, writes.repositoryLocksAcquired());

      StorageOperationMetrics publication =
          breakdown.metrics(StorageOperationKind.PACK_PUBLICATION);
      assertEquals(1, publication.transactionsStarted());
      assertEquals(1, publication.transactionsCommitted());
      assertEquals(0, publication.transactionsRolledBack());
      assertEquals(1, publication.repositoryLocksAcquired());
      assertTrue(publication.repositoryLockHeldNanos() > 0);
      assertTrue(publication.repositoryLockHeldNanos() <= publication.transactionDurationNanos());
      assertEquals(
          StorageOperationMetrics.ZERO,
          breakdown.metrics(StorageOperationKind.PACK_ROLLBACK));
    }
  }

  @Test
  void chunkedReplacementRemainsInOneLockedPublicationTransaction() throws Exception {
    String repositoryName = "two-phase-replacement";
    byte[] replacementBytes =
        deterministicBytes(HibernateObjDatabase.INLINE_PAYLOAD_THRESHOLD + 31, 37);
    AtomicInteger hookCalls = new AtomicInteger();

    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      persistCommittedInlinePack(provider, repositoryName, "pack-replaced");

      HibernateTransactionContext context =
          new HibernateTransactionContext(provider.getSessionFactory());
      ReadAheadHibernateObjDatabase database =
          (ReadAheadHibernateObjDatabase) repository.getObjectDatabase();
      DfsPackDescription replaced = description(database.listPacks(), "pack-replaced");
      DfsPackDescription replacement = database.newPack(PackSource.COMPACT);

      StagedPackExtensionStore store =
          new StagedPackExtensionStore(
              repositoryName, context, ignored -> hookCalls.incrementAndGet());
      write(store, replacement, PackExt.PACK, replacementBytes);

      StorageOperationMetrics before = context.metricsSnapshot();
      StorageOperationBreakdown breakdownBefore = context.operationBreakdownSnapshot();
      StagedPackExtensionStore.CommitResult result =
          store.commit(List.of(replacement), List.of(replaced));

      assertEquals(0, hookCalls.get(), "Replacing packs must not enter the unlocked payload phase");
      assertTrue(result.completeMetadata());
      assertEquals(1, result.committedExtensions().size());
      assertEquals(0L, packRowCount(provider, repositoryName, "pack-replaced"));
      assertEquals(1L, packRowCount(provider, repositoryName, baseName(replacement)));
      assertChunkedPayload(
          provider, repositoryName, baseName(replacement), replacementBytes);

      StorageOperationMetrics aggregate = context.metricsSnapshot().minus(before);
      StorageOperationBreakdown breakdown =
          context.operationBreakdownSnapshot().minus(breakdownBefore);
      assertEquals(1, aggregate.transactionsStarted());
      assertEquals(1, aggregate.transactionsCommitted());
      assertEquals(0, aggregate.transactionsRolledBack());
      assertEquals(1, aggregate.repositoryLocksAcquired());
      assertEquals(aggregate, breakdown.total());
      assertEquals(
          StorageOperationMetrics.ZERO,
          breakdown.metrics(StorageOperationKind.PACK_EXTENSION_WRITE));
      assertEquals(aggregate, breakdown.metrics(StorageOperationKind.PACK_PUBLICATION));
    }
  }

  @Test
  void publicationMismatchRollsBackVisibilityAndCleansPreparedRows() throws Exception {
    String repositoryName = "two-phase-failure";
    byte[] packBytes =
        deterministicBytes(HibernateObjDatabase.INLINE_PAYLOAD_THRESHOLD + 11, 41);
    byte[] indexBytes = deterministicBytes(53, 43);

    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      HibernateTransactionContext context =
          new HibernateTransactionContext(provider.getSessionFactory());
      ReadAheadHibernateObjDatabase database =
          (ReadAheadHibernateObjDatabase) repository.getObjectDatabase();
      DfsPackDescription description = database.newPack(PackSource.RECEIVE);

      StagedPackExtensionStore store =
          new StagedPackExtensionStore(
              repositoryName,
              context,
              writeToken -> {
                assertPreparedButInvisible(
                    provider,
                    repositoryName,
                    baseName(description),
                    writeToken,
                    packBytes,
                    indexBytes);
                deletePreparedExtension(provider, repositoryName, writeToken, "idx");
              });
      write(store, description, PackExt.PACK, packBytes);
      write(store, description, PackExt.INDEX, indexBytes);

      StorageOperationMetrics before = context.metricsSnapshot();
      StorageOperationBreakdown breakdownBefore = context.operationBreakdownSnapshot();
      IOException exception =
          assertThrows(IOException.class, () -> store.commit(List.of(description), null));
      assertTrue(exception.getMessage().contains("expected 2 extensions but updated 1"));

      store.rollback(List.of(description));
      assertEquals(0, store.stagedExtensionCount());
      assertNoPackRows(provider, repositoryName, baseName(description));

      StorageOperationMetrics aggregate = context.metricsSnapshot().minus(before);
      StorageOperationBreakdown breakdown =
          context.operationBreakdownSnapshot().minus(breakdownBefore);
      assertEquals(4, aggregate.transactionsStarted());
      assertEquals(3, aggregate.transactionsCommitted());
      assertEquals(1, aggregate.transactionsRolledBack());
      assertEquals(2, aggregate.repositoryLocksAcquired());
      assertEquals(aggregate, breakdown.total());

      StorageOperationMetrics publication =
          breakdown.metrics(StorageOperationKind.PACK_PUBLICATION);
      assertEquals(1, publication.transactionsStarted());
      assertEquals(0, publication.transactionsCommitted());
      assertEquals(1, publication.transactionsRolledBack());
      assertEquals(1, publication.repositoryLocksAcquired());

      StorageOperationMetrics rollback =
          breakdown.metrics(StorageOperationKind.PACK_ROLLBACK);
      assertEquals(1, rollback.transactionsStarted());
      assertEquals(1, rollback.transactionsCommitted());
      assertEquals(0, rollback.transactionsRolledBack());
      assertEquals(0, rollback.repositoryLocksAcquired());
    }
  }

  private static void write(
      StagedPackExtensionStore store,
      DfsPackDescription description,
      PackExt extension,
      byte[] data)
      throws IOException {
    try (DfsOutputStream stream = store.open(description, extension)) {
      stream.write(data, 0, data.length);
    }
    description.addFileExt(extension);
    description.setFileSize(extension, data.length);
  }

  private static void assertPreparedButInvisible(
      HibernateSessionFactoryProvider provider,
      String repositoryName,
      String packName,
      String writeToken,
      byte[] expectedPack,
      byte[] expectedIndex) {
    try (Session session = provider.getSessionFactory().openSession()) {
      List<Object[]> rows =
          session
              .createQuery(
                  "SELECT p.id, p.packExtension, p.data, p.committed, p.writeToken, "
                      + "p.writeLeaseUntil, p.packSource, p.fileSize FROM GitPackEntity p "
                      + "WHERE p.repositoryName = :repo AND p.packName = :name "
                      + "ORDER BY p.packExtension",
                  Object[].class)
              .setParameter("repo", repositoryName)
              .setParameter("name", packName)
              .getResultList();
      assertEquals(2, rows.size());
      Map<String, Object[]> byExtension = new HashMap<>();
      for (Object[] row : rows) {
        byExtension.put((String) row[1], row);
        assertFalse((Boolean) row[3]);
        assertEquals(writeToken, row[4]);
        assertNotNull(row[5]);
        assertEquals(PackSource.RECEIVE.name(), row[6]);
      }

      Object[] pack = byExtension.get("pack");
      Object[] index = byExtension.get("idx");
      assertNotNull(pack);
      assertNotNull(index);
      assertNull(pack[2]);
      assertEquals(expectedPack.length, ((Number) pack[7]).longValue());
      assertArrayEquals(expectedIndex, (byte[]) index[2]);
      assertEquals(expectedIndex.length, ((Number) index[7]).longValue());

      long committedRows =
          session
              .createQuery(
                  "SELECT COUNT(p) FROM GitPackEntity p WHERE p.repositoryName = :repo "
                      + "AND p.packName = :name AND p.committed = true",
                  Long.class)
              .setParameter("repo", repositoryName)
              .setParameter("name", packName)
              .getSingleResult();
      assertEquals(0L, committedRows);
      List<byte[]> chunks =
          session
              .createQuery(
                  "SELECT c.data FROM GitPackChunkEntity c WHERE c.packId = :packId "
                      + "ORDER BY c.chunkIndex",
                  byte[].class)
              .setParameter("packId", pack[0])
              .getResultList();
      assertEquals(1, chunks.size());
      assertArrayEquals(expectedPack, chunks.getFirst());
    }
  }

  private static void assertPublished(
      HibernateSessionFactoryProvider provider,
      String repositoryName,
      String packName,
      byte[] expectedPack,
      byte[] expectedIndex) {
    try (Session session = provider.getSessionFactory().openSession()) {
      List<Object[]> rows =
          session
              .createQuery(
                  "SELECT p.id, p.packExtension, p.data, p.committed, p.writeToken, "
                      + "p.writeLeaseUntil, p.committedAt FROM GitPackEntity p "
                      + "WHERE p.repositoryName = :repo AND p.packName = :name "
                      + "ORDER BY p.packExtension",
                  Object[].class)
              .setParameter("repo", repositoryName)
              .setParameter("name", packName)
              .getResultList();
      assertEquals(2, rows.size());
      Map<String, Object[]> byExtension = new HashMap<>();
      for (Object[] row : rows) {
        byExtension.put((String) row[1], row);
        assertTrue((Boolean) row[3]);
        assertNull(row[4]);
        assertNull(row[5]);
        assertNotNull(row[6]);
      }
      Object[] pack = byExtension.get("pack");
      Object[] index = byExtension.get("idx");
      assertNull(pack[2]);
      assertArrayEquals(expectedIndex, (byte[]) index[2]);
      List<byte[]> chunks =
          session
              .createQuery(
                  "SELECT c.data FROM GitPackChunkEntity c WHERE c.packId = :packId "
                      + "ORDER BY c.chunkIndex",
                  byte[].class)
              .setParameter("packId", pack[0])
              .getResultList();
      assertEquals(1, chunks.size());
      assertArrayEquals(expectedPack, chunks.getFirst());
    }
  }

  private static void assertChunkedPayload(
      HibernateSessionFactoryProvider provider,
      String repositoryName,
      String packName,
      byte[] expectedPack) {
    try (Session session = provider.getSessionFactory().openSession()) {
      Object[] row =
          session
              .createQuery(
                  "SELECT p.id, p.data, p.committed, p.writeToken, p.writeLeaseUntil "
                      + "FROM GitPackEntity p WHERE p.repositoryName = :repo "
                      + "AND p.packName = :name AND p.packExtension = 'pack'",
                  Object[].class)
              .setParameter("repo", repositoryName)
              .setParameter("name", packName)
              .getSingleResult();
      assertNull(row[1]);
      assertTrue((Boolean) row[2]);
      assertNull(row[3]);
      assertNull(row[4]);
      List<byte[]> chunks =
          session
              .createQuery(
                  "SELECT c.data FROM GitPackChunkEntity c WHERE c.packId = :packId "
                      + "ORDER BY c.chunkIndex",
                  byte[].class)
              .setParameter("packId", row[0])
              .getResultList();
      assertEquals(1, chunks.size());
      assertArrayEquals(expectedPack, chunks.getFirst());
    }
  }

  private static void deletePreparedExtension(
      HibernateSessionFactoryProvider provider,
      String repositoryName,
      String writeToken,
      String extension) {
    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      int deleted =
          session
              .createMutationQuery(
                  "DELETE FROM GitPackEntity p WHERE p.repositoryName = :repo "
                      + "AND p.writeToken = :writeToken AND p.packExtension = :extension")
              .setParameter("repo", repositoryName)
              .setParameter("writeToken", writeToken)
              .setParameter("extension", extension)
              .executeUpdate();
      assertEquals(1, deleted);
      transaction.commit();
    }
  }

  private static void persistCommittedInlinePack(
      HibernateSessionFactoryProvider provider, String repositoryName, String packName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      GitPackEntity entity = new GitPackEntity();
      entity.setRepositoryName(repositoryName);
      entity.setPackName(packName);
      entity.setPackExtension(PackExt.PACK.getExtension());
      entity.setData(new byte[] {1, 2, 3});
      entity.setFileSize(3);
      entity.setCommitted(true);
      entity.setCreatedAt(Instant.now());
      entity.setCommittedAt(Instant.now());
      entity.setPackSource(PackSource.INSERT.name());
      session.persist(entity);
      transaction.commit();
    }
  }

  private static DfsPackDescription description(
      List<DfsPackDescription> descriptions, String packName) {
    return descriptions.stream()
        .filter(candidate -> baseName(candidate).equals(packName))
        .findFirst()
        .orElseThrow();
  }

  private static long packRowCount(
      HibernateSessionFactoryProvider provider, String repositoryName, String packName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT COUNT(p) FROM GitPackEntity p WHERE p.repositoryName = :repo "
                  + "AND p.packName = :name",
              Long.class)
          .setParameter("repo", repositoryName)
          .setParameter("name", packName)
          .getSingleResult();
    }
  }

  private static void assertNoPackRows(
      HibernateSessionFactoryProvider provider, String repositoryName, String packName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      List<Long> packIds =
          session
              .createQuery(
                  "SELECT p.id FROM GitPackEntity p WHERE p.repositoryName = :repo "
                      + "AND p.packName = :name",
                  Long.class)
              .setParameter("repo", repositoryName)
              .setParameter("name", packName)
              .getResultList();
      assertTrue(packIds.isEmpty());
      long chunkRows =
          session
              .createQuery("SELECT COUNT(c) FROM GitPackChunkEntity c", Long.class)
              .getSingleResult();
      assertEquals(0L, chunkRows);
    }
  }

  private static String baseName(DfsPackDescription description) {
    String fileName = description.getFileName(PackExt.PACK);
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
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

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:two-phase-publication-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.connection.pool_size", "4");
    properties.put("hibernate.generate_statistics", "true");
    properties.put("hibernate.search.enabled", "false");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    return new HibernateSessionFactoryProvider(properties);
  }
}
