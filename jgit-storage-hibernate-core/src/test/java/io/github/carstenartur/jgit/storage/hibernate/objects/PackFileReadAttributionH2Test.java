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
import io.github.carstenartur.jgit.storage.hibernate.transaction.PackFileReadMetrics;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.dfs.ReadableChannel;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.junit.jupiter.api.Test;

class PackFileReadAttributionH2Test {

  @Test
  void classifiesFallbackReadsByExtensionAndStorageMode() throws Exception {
    String repositoryName = "pack-read-attribution";
    try (HibernateSessionFactoryProvider provider = provider(true);
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      PersistedExtensions persisted = persistExtensions(provider, repositoryName, "pack-metrics");
      ReadAheadHibernateObjDatabase database = objectDatabase(repository);
      DfsPackDescription description = description(repository, persisted);
      PackFileReadMetrics before = repository.getPackFileReadMetrics();

      try (ReadableChannel channel = database.openFile(description, PackExt.PACK)) {
        assertArrayEquals(persisted.packData(), readFully(channel));
      }
      try (ReadableChannel channel = database.openFile(description, PackExt.INDEX)) {
        assertArrayEquals(persisted.indexData(), readFully(channel));
      }
      try (ReadableChannel channel = database.openFile(description, PackExt.REFTABLE)) {
        assertArrayEquals(persisted.reftableData(), readFully(channel));
      }
      assertThrows(
          FileNotFoundException.class,
          () -> database.openFile(description, PackExt.BITMAP_INDEX));

      PackFileReadMetrics delta = repository.getPackFileReadMetrics().minus(before);
      assertEquals(
          new PackFileReadMetrics(0, 1, 1, 0, 1, 0, 0, 0, 1),
          delta);
      assertEquals(3, delta.successfulReads());
      assertEquals(4, delta.totalLookups());
    }
  }

  @Test
  void cataloguedChunkedOpenDoesNotCountAsDatabaseFallback() throws Exception {
    String repositoryName = "pack-read-catalog-hit";
    try (HibernateSessionFactoryProvider provider = provider(true);
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      PersistedExtensions persisted = persistExtensions(provider, repositoryName, "pack-catalogued");
      ReadAheadHibernateObjDatabase database = objectDatabase(repository);
      DfsPackDescription description =
          database.listPacks().stream()
              .filter(candidate -> baseName(candidate).equals(persisted.packName()))
              .findFirst()
              .orElseThrow();
      PackFileReadMetrics before = repository.getPackFileReadMetrics();

      try (ReadableChannel channel = database.openFile(description, PackExt.PACK)) {
        assertArrayEquals(persisted.packData(), readFully(channel));
      }

      assertEquals(
          PackFileReadMetrics.ZERO,
          repository.getPackFileReadMetrics().minus(before));
    }
  }

  @Test
  void diagnosticsDisabledReturnZeroSnapshot() throws Exception {
    String repositoryName = "pack-read-disabled";
    try (HibernateSessionFactoryProvider provider = provider(false);
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      byte[] data = new byte[] {3, 1, 4, 1, 5};
      persistInlineExtension(provider, repositoryName, "pack-disabled", PackExt.INDEX, data);
      ReadAheadHibernateObjDatabase database = objectDatabase(repository);
      DfsPackDescription description =
          new DfsPackDescription(
              repository.getDescription(), "pack-disabled", PackSource.INSERT);
      description.addFileExt(PackExt.INDEX);
      description.setFileSize(PackExt.INDEX, data.length);

      try (ReadableChannel channel = database.openFile(description, PackExt.INDEX)) {
        assertArrayEquals(data, readFully(channel));
      }

      assertEquals(PackFileReadMetrics.ZERO, repository.getPackFileReadMetrics());
    }
  }

  private static ReadAheadHibernateObjDatabase objectDatabase(HibernateRepository repository) {
    return (ReadAheadHibernateObjDatabase) repository.getObjectDatabase();
  }

  private static PersistedExtensions persistExtensions(
      HibernateSessionFactoryProvider provider, String repositoryName, String packName) {
    byte[] packData = deterministicBytes(4096, 17);
    byte[] indexData = deterministicBytes(37, 23);
    byte[] reftableData = deterministicBytes(43, 29);
    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      GitPackEntity pack =
          persistEntity(session, repositoryName, packName, PackExt.PACK, null, packData.length);
      session.flush();

      GitPackChunkEntity chunk = new GitPackChunkEntity();
      chunk.setPackId(pack.getId());
      chunk.setChunkIndex(0);
      chunk.setChunkSize(packData.length);
      chunk.setData(packData);
      session.persist(chunk);

      persistEntity(
          session,
          repositoryName,
          packName,
          PackExt.INDEX,
          indexData,
          indexData.length);
      persistEntity(
          session,
          repositoryName,
          packName,
          PackExt.REFTABLE,
          reftableData,
          reftableData.length);
      transaction.commit();
    }
    return new PersistedExtensions(packName, packData, indexData, reftableData);
  }

  private static void persistInlineExtension(
      HibernateSessionFactoryProvider provider,
      String repositoryName,
      String packName,
      PackExt extension,
      byte[] data) {
    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      persistEntity(session, repositoryName, packName, extension, data, data.length);
      transaction.commit();
    }
  }

  private static GitPackEntity persistEntity(
      Session session,
      String repositoryName,
      String packName,
      PackExt extension,
      byte[] data,
      long fileSize) {
    GitPackEntity entity = new GitPackEntity();
    entity.setRepositoryName(repositoryName);
    entity.setPackName(packName);
    entity.setPackExtension(extension.getExtension());
    entity.setData(data);
    entity.setFileSize(fileSize);
    entity.setCommitted(true);
    entity.setCreatedAt(Instant.now());
    entity.setCommittedAt(Instant.now());
    session.persist(entity);
    return entity;
  }

  private static DfsPackDescription description(
      HibernateRepository repository, PersistedExtensions persisted) {
    DfsPackDescription description =
        new DfsPackDescription(
            repository.getDescription(), persisted.packName(), PackSource.INSERT);
    description.addFileExt(PackExt.PACK);
    description.setFileSize(PackExt.PACK, persisted.packData().length);
    description.addFileExt(PackExt.INDEX);
    description.setFileSize(PackExt.INDEX, persisted.indexData().length);
    description.addFileExt(PackExt.REFTABLE);
    description.setFileSize(PackExt.REFTABLE, persisted.reftableData().length);
    return description;
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

  private static HibernateSessionFactoryProvider provider(boolean metricsEnabled) {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:pack-read-metrics-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.connection.pool_size", "2");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, metricsEnabled);
    return new HibernateSessionFactoryProvider(properties);
  }

  private record PersistedExtensions(
      String packName, byte[] packData, byte[] indexData, byte[] reftableData) {}
}
