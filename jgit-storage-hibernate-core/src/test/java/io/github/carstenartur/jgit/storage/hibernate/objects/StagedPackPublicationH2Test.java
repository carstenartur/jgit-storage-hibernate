/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationBreakdown;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationKind;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;

class StagedPackPublicationH2Test {

  @Test
  void rollsBackAllRowsAndReleasesStagingWhenOneExtensionIsMissing() throws Exception {
    String repositoryName = "staged-failure-" + UUID.randomUUID();
    try (HibernateSessionFactoryProvider provider = provider(repositoryName);
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      HibernateObjDatabase objectDatabase = repository.getObjectDatabase();
      DfsPackDescription description =
          new DfsPackDescription(repository.getDescription(), "pack-incomplete", PackSource.INSERT);
      byte[] pack = bytes(1024, 11);

      writeExtension(objectDatabase, description, PackExt.PACK, pack);
      description.addFileExt(PackExt.PACK);
      description.setFileSize(PackExt.PACK, pack.length);
      description.addFileExt(PackExt.INDEX);
      description.setFileSize(PackExt.INDEX, 128);

      IOException failure =
          assertThrows(
              IOException.class,
              () -> objectDatabase.commitPackImpl(List.of(description), null));
      assertTrue(failure.getMessage().contains("Missing staged extension"));
      assertEquals(0L, countPackRows(provider, repositoryName, "pack-incomplete"));

      DfsPackDescription retry =
          new DfsPackDescription(repository.getDescription(), "pack-incomplete", PackSource.INSERT);
      writeExtension(objectDatabase, retry, PackExt.PACK, pack);
      retry.addFileExt(PackExt.PACK);
      retry.setFileSize(PackExt.PACK, pack.length);
      objectDatabase.rollbackPack(List.of(retry));
      assertEquals(0L, countPackRows(provider, repositoryName, "pack-incomplete"));
    }
  }

  @Test
  void publishesPackAndIndexTogetherWithOneTransactionAndLock() throws Exception {
    String repositoryName = "staged-multiple-" + UUID.randomUUID();
    try (HibernateSessionFactoryProvider provider = provider(repositoryName);
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      HibernateObjDatabase objectDatabase = repository.getObjectDatabase();
      DfsPackDescription description =
          new DfsPackDescription(repository.getDescription(), "pack-complete", PackSource.INSERT);
      byte[] pack = bytes(512 * 1024, 17);
      byte[] index = bytes(32 * 1024, 29);

      writeExtension(objectDatabase, description, PackExt.PACK, pack);
      writeExtension(objectDatabase, description, PackExt.INDEX, index);
      description.addFileExt(PackExt.PACK);
      description.setFileSize(PackExt.PACK, pack.length);
      description.addFileExt(PackExt.INDEX);
      description.setFileSize(PackExt.INDEX, index.length);
      StorageOperationBreakdown before = repository.getStorageOperationBreakdown();

      objectDatabase.commitPackImpl(List.of(description), null);

      assertEquals(2L, countPackRows(provider, repositoryName, "pack-complete"));
      assertEquals(2L, countCommittedRows(provider, repositoryName, "pack-complete"));
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

  private static void writeExtension(
      HibernateObjDatabase objectDatabase,
      DfsPackDescription description,
      PackExt extension,
      byte[] payload)
      throws Exception {
    try (DfsOutputStream stream = objectDatabase.writeFile(description, extension)) {
      stream.write(payload, 0, payload.length);
    }
  }

  private static long countPackRows(
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

  private static long countCommittedRows(
      HibernateSessionFactoryProvider provider, String repositoryName, String packName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT COUNT(p) FROM GitPackEntity p WHERE p.repositoryName = :repo "
                  + "AND p.packName = :name AND p.committed = true",
              Long.class)
          .setParameter("repo", repositoryName)
          .setParameter("name", packName)
          .getSingleResult();
    }
  }

  private static byte[] bytes(int size, int seed) {
    byte[] data = new byte[size];
    int value = seed;
    for (int index = 0; index < data.length; index++) {
      value = value * 1664525 + 1013904223;
      data[index] = (byte) (value >>> 24);
    }
    return data;
  }

  private static HibernateSessionFactoryProvider provider(String name) {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    return new HibernateSessionFactoryProvider(properties);
  }
}
