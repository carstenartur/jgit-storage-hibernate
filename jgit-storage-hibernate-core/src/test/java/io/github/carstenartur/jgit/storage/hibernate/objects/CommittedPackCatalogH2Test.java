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
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackChunkEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationBreakdown;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationKind;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationMetrics;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.dfs.ReadableChannel;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;

class CommittedPackCatalogH2Test {

  @Test
  void opensChunkedExtensionsFromCatalogWithoutRepeatingMetadataQuery() throws Exception {
    String repositoryName = "catalog-hit";
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      PersistedPack persisted = persistCommittedPack(provider, repositoryName, "pack-catalog");
      ReadAheadHibernateObjDatabase database = objectDatabase(repository);
      Statistics statistics = provider.getSessionFactory().getStatistics();
      statistics.clear();
      StorageOperationBreakdown before = repository.getStorageOperationBreakdown();

      List<DfsPackDescription> descriptions = database.listPacks();
      DfsPackDescription description = description(descriptions, persisted.packName());

      assertEquals(2, database.committedExtensionCatalogSize());
      assertEquals(1L, statistics.getQueryExecutionCount());
      StorageOperationBreakdown afterCatalog =
          repository.getStorageOperationBreakdown().minus(before);
      assertEquals(
          new StorageOperationMetrics(1, 1, 0, 0, 0),
          withoutTiming(afterCatalog.metrics(StorageOperationKind.PACK_METADATA_READ)));
      assertEquals(
          StorageOperationMetrics.ZERO,
          afterCatalog.metrics(StorageOperationKind.PACK_FILE_READ));

      try (ReadableChannel channel = database.openFile(description, PackExt.PACK)) {
        assertEquals(
            1L,
            statistics.getQueryExecutionCount(),
            "Opening a catalogued chunked extension must not repeat its metadata query");
        assertArrayEquals(persisted.packData(), readFully(channel));
      }
      assertEquals(
          2L,
          statistics.getQueryExecutionCount(),
          "Reading chunk data still performs the channel-local ordered chunk query");
      assertEquals(
          StorageOperationMetrics.ZERO,
          repository
              .getStorageOperationBreakdown()
              .minus(before)
              .metrics(StorageOperationKind.PACK_FILE_READ));

      try (ReadableChannel channel = database.openFile(description, PackExt.INDEX)) {
        assertArrayEquals(persisted.indexData(), readFully(channel));
      }
      assertEquals(
          3L,
          statistics.getQueryExecutionCount(),
          "Inline payloads deliberately retain the bounded database fallback");
      assertEquals(
          1L,
          repository
              .getStorageOperationBreakdown()
              .minus(before)
              .metrics(StorageOperationKind.PACK_FILE_READ)
              .transactionsCommitted());
    }
  }

  @Test
  void successfulPublicationInvalidatesAndRebuildsCatalog() throws Exception {
    String repositoryName = "catalog-invalidation";
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      persistCommittedPack(provider, repositoryName, "pack-existing");
      ReadAheadHibernateObjDatabase database = objectDatabase(repository);

      database.listPacks();
      assertEquals(2, database.committedExtensionCatalogSize());

      DfsPackDescription staged = database.newPack(PackSource.RECEIVE);
      write(database, staged, PackExt.PACK, new byte[] {9, 8, 7, 6});
      database.commitPackImpl(List.of(staged), null);

      assertEquals(
          0,
          database.committedExtensionCatalogSize(),
          "A successful publication must invalidate metadata and DFS caches together");

      Statistics statistics = provider.getSessionFactory().getStatistics();
      statistics.clear();
      List<DfsPackDescription> rebuilt = database.listPacks();
      assertEquals(1L, statistics.getQueryExecutionCount());
      assertEquals(3, database.committedExtensionCatalogSize());
      assertEquals(2, rebuilt.size());
    }
  }

  @Test
  void failedPublicationKeepsLastCompleteCatalogGeneration() throws Exception {
    String repositoryName = "catalog-failure";
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      PersistedPack persisted = persistCommittedPack(provider, repositoryName, "pack-stable");
      ReadAheadHibernateObjDatabase database = objectDatabase(repository);
      DfsPackDescription stable = description(database.listPacks(), persisted.packName());
      assertEquals(2, database.committedExtensionCatalogSize());

      DfsPackDescription failing = database.newPack(PackSource.RECEIVE);
      write(database, failing, PackExt.PACK, new byte[] {1, 2, 3});
      write(database, failing, PackExt.INDEX, new byte[] {4, 5, 6});
      persistInlineExtension(
          provider, repositoryName, baseName(failing), PackExt.INDEX.getExtension(), new byte[] {0});

      assertThrows(IOException.class, () -> database.commitPackImpl(List.of(failing), null));
      assertEquals(
          2,
          database.committedExtensionCatalogSize(),
          "A rolled-back publication must not replace the last complete catalog snapshot");

      Statistics statistics = provider.getSessionFactory().getStatistics();
      statistics.clear();
      try (ReadableChannel channel = database.openFile(stable, PackExt.PACK)) {
        assertEquals(0L, statistics.getQueryExecutionCount());
        assertArrayEquals(persisted.packData(), readFully(channel));
      }
      assertEquals(1L, statistics.getQueryExecutionCount());
    }
  }

  private static ReadAheadHibernateObjDatabase objectDatabase(HibernateRepository repository) {
    return (ReadAheadHibernateObjDatabase) repository.getObjectDatabase();
  }

  private static PersistedPack persistCommittedPack(
      HibernateSessionFactoryProvider provider, String repositoryName, String packName) {
    byte[] packData = deterministicBytes(4096, 17);
    byte[] indexData = deterministicBytes(31, 29);
    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      GitPackEntity pack =
          persistPackEntity(session, repositoryName, packName, "pack", null, packData.length);
      session.flush();

      GitPackChunkEntity chunk = new GitPackChunkEntity();
      chunk.setPackId(pack.getId());
      chunk.setChunkIndex(0);
      chunk.setChunkSize(packData.length);
      chunk.setData(packData);
      session.persist(chunk);

      persistPackEntity(
          session, repositoryName, packName, PackExt.INDEX.getExtension(), indexData, indexData.length);
      transaction.commit();
    }
    return new PersistedPack(packName, packData, indexData);
  }

  private static void persistInlineExtension(
      HibernateSessionFactoryProvider provider,
      String repositoryName,
      String packName,
      String extension,
      byte[] data) {
    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      persistPackEntity(session, repositoryName, packName, extension, data, data.length);
      transaction.commit();
    }
  }

  private static GitPackEntity persistPackEntity(
      Session session,
      String repositoryName,
      String packName,
      String extension,
      byte[] data,
      long fileSize) {
    GitPackEntity entity = new GitPackEntity();
    entity.setRepositoryName(repositoryName);
    entity.setPackName(packName);
    entity.setPackExtension(extension);
    entity.setData(data);
    entity.setFileSize(fileSize);
    entity.setCommitted(true);
    entity.setCreatedAt(Instant.now());
    entity.setCommittedAt(Instant.now());
    session.persist(entity);
    return entity;
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

  private static DfsPackDescription description(
      List<DfsPackDescription> descriptions, String packName) {
    return descriptions.stream()
        .filter(candidate -> baseName(candidate).equals(packName))
        .findFirst()
        .orElseThrow();
  }

  private static String baseName(DfsPackDescription description) {
    String fileName = description.getFileName(PackExt.PACK);
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  private static byte[] readFully(ReadableChannel channel) throws IOException {
    ByteBuffer destination = ByteBuffer.allocate(Math.toIntExact(channel.size()));
    while (destination.hasRemaining()) {
      int count = channel.read(destination);
      if (count < 0) {
        break;
      }
    }
    return destination.array();
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

  private static StorageOperationMetrics withoutTiming(StorageOperationMetrics metrics) {
    return new StorageOperationMetrics(
        metrics.transactionsStarted(),
        metrics.transactionsCommitted(),
        metrics.transactionsRolledBack(),
        metrics.repositoryLocksAcquired(),
        0);
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:pack-catalog-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.generate_statistics", "true");
    properties.put("hibernate.connection.pool_size", "2");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    return new HibernateSessionFactoryProvider(properties);
  }

  private record PersistedPack(String packName, byte[] packData, byte[] indexData) {}
}
