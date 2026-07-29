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
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.junit.jupiter.api.Test;

class StagedPackExtensionStoreH2Test {

  @Test
  void publishesTwoExtensionsInOneLockedTransaction() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), "atomic-publication")) {
      repository.create(true);
      ReadAheadHibernateObjDatabase database =
          (ReadAheadHibernateObjDatabase) repository.getObjectDatabase();
      DfsPackDescription description = database.newPack(PackSource.RECEIVE);
      byte[] packBytes = deterministicBytes(257, 11);
      byte[] indexBytes = deterministicBytes(73, 29);

      write(database, description, PackExt.PACK, packBytes);
      write(database, description, PackExt.INDEX, indexBytes);
      assertEquals(2, database.stagedExtensionCount());
      assertEquals(0L, rowCount(provider, "atomic-publication"));

      StorageOperationMetrics aggregateBefore = repository.getStorageOperationMetrics();
      StorageOperationBreakdown breakdownBefore = repository.getStorageOperationBreakdown();
      database.commitPackImpl(List.of(description), null);

      StorageOperationMetrics aggregate =
          repository.getStorageOperationMetrics().minus(aggregateBefore);
      StorageOperationBreakdown breakdown =
          repository.getStorageOperationBreakdown().minus(breakdownBefore);
      assertEquals(
          new StorageOperationMetrics(
              1, 1, 0, 1, aggregate.repositoryLockAcquisitionNanos()),
          aggregate);
      assertEquals(aggregate, breakdown.total());
      assertEquals(aggregate, breakdown.metrics(StorageOperationKind.PACK_PUBLICATION));
      assertEquals(
          StorageOperationMetrics.ZERO,
          breakdown.metrics(StorageOperationKind.PACK_EXTENSION_WRITE));
      assertEquals(0, database.stagedExtensionCount());
      assertEquals(2L, rowCount(provider, "atomic-publication"));
      assertArrayEquals(packBytes, inlineData(provider, "atomic-publication", "pack"));
      assertArrayEquals(indexBytes, inlineData(provider, "atomic-publication", "idx"));
    }
  }

  @Test
  void rollsBackStagingWithoutStartingADatabaseTransaction() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), "staging-rollback")) {
      repository.create(true);
      ReadAheadHibernateObjDatabase database =
          (ReadAheadHibernateObjDatabase) repository.getObjectDatabase();
      DfsPackDescription description = database.newPack(PackSource.RECEIVE);
      write(database, description, PackExt.PACK, deterministicBytes(64, 7));
      assertEquals(1, database.stagedExtensionCount());

      StorageOperationMetrics before = repository.getStorageOperationMetrics();
      database.rollbackPack(List.of(description));

      assertEquals(before, repository.getStorageOperationMetrics());
      assertEquals(0, database.stagedExtensionCount());
      assertEquals(0L, rowCount(provider, "staging-rollback"));
    }
  }

  @Test
  void publicationFailureRollsBackEveryNewExtension() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), "atomic-failure")) {
      repository.create(true);
      ReadAheadHibernateObjDatabase database =
          (ReadAheadHibernateObjDatabase) repository.getObjectDatabase();
      DfsPackDescription description = database.newPack(PackSource.RECEIVE);
      write(database, description, PackExt.PACK, deterministicBytes(80, 3));
      write(database, description, PackExt.INDEX, deterministicBytes(40, 5));
      persistConflictingIndex(provider, "atomic-failure", baseName(description));

      assertThrows(IOException.class, () -> database.commitPackImpl(List.of(description), null));
      assertEquals(
          1L,
          rowCount(provider, "atomic-failure"),
          "The pre-existing conflicting row is the only row after transaction rollback");
      assertEquals(2, database.stagedExtensionCount());

      database.rollbackPack(List.of(description));
      assertEquals(0, database.stagedExtensionCount());
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
      stream.flush();
    }
    description.addFileExt(extension);
    description.setFileSize(extension, data.length);
  }

  private static void persistConflictingIndex(
      HibernateSessionFactoryProvider provider, String repositoryName, String packName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      GitPackEntity entity = new GitPackEntity();
      entity.setRepositoryName(repositoryName);
      entity.setPackName(packName);
      entity.setPackExtension("idx");
      entity.setData(new byte[] {1});
      entity.setFileSize(1);
      entity.setCommitted(true);
      entity.setCreatedAt(Instant.now());
      entity.setCommittedAt(Instant.now());
      session.persist(entity);
      transaction.commit();
    }
  }

  private static long rowCount(
      HibernateSessionFactoryProvider provider, String repositoryName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT COUNT(p) FROM GitPackEntity p WHERE p.repositoryName = :repo", Long.class)
          .setParameter("repo", repositoryName)
          .getSingleResult();
    }
  }

  private static byte[] inlineData(
      HibernateSessionFactoryProvider provider, String repositoryName, String extension) {
    try (Session session = provider.getSessionFactory().openSession()) {
      GitPackEntity entity =
          session
              .createQuery(
                  "FROM GitPackEntity p WHERE p.repositoryName = :repo "
                      + "AND p.packExtension = :ext",
                  GitPackEntity.class)
              .setParameter("repo", repositoryName)
              .setParameter("ext", extension)
              .getSingleResult();
      assertTrue(entity.isCommitted());
      assertNull(entity.getWriteToken());
      assertNull(entity.getWriteLeaseUntil());
      return entity.getData();
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
        "jdbc:h2:mem:staged-publication-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.connection.pool_size", "2");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    return new HibernateSessionFactoryProvider(properties);
  }
}
