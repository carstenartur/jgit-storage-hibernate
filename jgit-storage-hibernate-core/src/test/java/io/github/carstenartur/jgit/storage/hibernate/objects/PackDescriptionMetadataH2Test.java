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
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
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

class PackDescriptionMetadataH2Test {

  @Test
  void retainsCompleteMetadataThroughLocalHandoffAndRepositoryReopen() throws Exception {
    String repositoryName = "pack-metadata";
    String packName;
    try (HibernateSessionFactoryProvider provider = provider()) {
      try (HibernateRepository repository =
          HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
        repository.create(true);
        ReadAheadHibernateObjDatabase database =
            (ReadAheadHibernateObjDatabase) repository.getObjectDatabase();
        DfsPackDescription description =
            database
                .newPack(PackSource.GC)
                .setLastModified(1_776_000_123_456L)
                .setObjectCount(321)
                .setDeltaCount(87)
                .setIndexVersion(2)
                .setMinUpdateIndex(42)
                .setMaxUpdateIndex(99);
        packName = baseName(description);
        write(database, description, PackExt.PACK, new byte[] {1, 2, 3, 4, 5});
        write(database, description, PackExt.INDEX, new byte[] {6, 7, 8});

        database.commitPackImpl(List.of(description), null);

        DfsPackDescription local = find(database.listPacks(), packName);
        assertMetadata(local);
      }

      assertEveryExtensionRowContainsMetadata(provider, repositoryName, packName);

      try (HibernateRepository reopened =
          HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
        ReadAheadHibernateObjDatabase database =
            (ReadAheadHibernateObjDatabase) reopened.getObjectDatabase();
        DfsPackDescription restored = find(database.listPacks(), packName);
        assertMetadata(restored);
        assertEquals(5, restored.getFileSize(PackExt.PACK));
        assertEquals(3, restored.getFileSize(PackExt.INDEX));
      }
    }
  }

  @Test
  void legacyRowsUseInsertSourceAndCommittedTimestampFallback() throws Exception {
    String repositoryName = "legacy-pack-metadata";
    String packName = "pack-legacy";
    Instant committedAt = Instant.parse("2025-06-07T08:09:10Z");
    try (HibernateSessionFactoryProvider provider = provider()) {
      try (HibernateRepository repository =
          HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
        repository.create(true);
      }
      persistLegacyRow(provider, repositoryName, packName, committedAt);

      try (HibernateRepository reopened =
          HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
        ReadAheadHibernateObjDatabase database =
            (ReadAheadHibernateObjDatabase) reopened.getObjectDatabase();
        DfsPackDescription restored = find(database.listPacks(), packName);
        assertEquals(PackSource.INSERT, restored.getPackSource());
        assertEquals(committedAt.toEpochMilli(), restored.getLastModified());
        assertEquals(0, restored.getObjectCount());
        assertEquals(0, restored.getDeltaCount());
        assertEquals(0, restored.getIndexVersion());
        assertEquals(0, restored.getMinUpdateIndex());
        assertEquals(0, restored.getMaxUpdateIndex());
      }
    }
  }

  private static void assertMetadata(DfsPackDescription description) {
    assertEquals(PackSource.GC, description.getPackSource());
    assertEquals(1_776_000_123_456L, description.getLastModified());
    assertEquals(321, description.getObjectCount());
    assertEquals(87, description.getDeltaCount());
    assertEquals(2, description.getIndexVersion());
    assertEquals(42, description.getMinUpdateIndex());
    assertEquals(99, description.getMaxUpdateIndex());
  }

  private static void assertEveryExtensionRowContainsMetadata(
      HibernateSessionFactoryProvider provider, String repositoryName, String packName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      List<GitPackEntity> entities =
          session
              .createQuery(
                  "FROM GitPackEntity p WHERE p.repositoryName = :repo AND p.packName = :name",
                  GitPackEntity.class)
              .setParameter("repo", repositoryName)
              .setParameter("name", packName)
              .getResultList();
      assertEquals(2, entities.size());
      for (GitPackEntity entity : entities) {
        assertEquals(PackSource.GC.name(), entity.getPackSource());
        assertEquals(1_776_000_123_456L, entity.getLastModified());
        assertEquals(321L, entity.getObjectCount());
        assertEquals(87L, entity.getDeltaCount());
        assertEquals(2, entity.getIndexVersion());
        assertEquals(42L, entity.getMinUpdateIndex());
        assertEquals(99L, entity.getMaxUpdateIndex());
      }
    }
  }

  private static void persistLegacyRow(
      HibernateSessionFactoryProvider provider,
      String repositoryName,
      String packName,
      Instant committedAt) {
    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      GitPackEntity entity = new GitPackEntity();
      entity.setRepositoryName(repositoryName);
      entity.setPackName(packName);
      entity.setPackExtension(PackExt.PACK.getExtension());
      entity.setData(new byte[] {11});
      entity.setFileSize(1);
      entity.setCommitted(true);
      entity.setCreatedAt(committedAt.minusSeconds(1));
      entity.setCommittedAt(committedAt);
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

  private static DfsPackDescription find(
      List<DfsPackDescription> descriptions, String packName) {
    DfsPackDescription result =
        descriptions.stream()
            .filter(description -> baseName(description).equals(packName))
            .findFirst()
            .orElse(null);
    assertNotNull(result);
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
        "jdbc:h2:mem:pack-description-metadata-"
            + UUID.randomUUID()
            + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.connection.pool_size", "2");
    return new HibernateSessionFactoryProvider(properties);
  }
}
