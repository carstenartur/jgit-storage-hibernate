/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.PackFileReadMetrics;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationKind;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationMetrics;
import java.io.FileNotFoundException;
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
import org.junit.jupiter.api.Test;

class InlinePayloadCacheH2Test {

  @Test
  void localPublicationSurvivesAuthoritativeScanWithoutDatabaseFallback() throws Exception {
    String repositoryName = "inline-handoff-publication";
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      ReadAheadHibernateObjDatabase database = objectDatabase(repository);
      database.listPacks();
      int entriesBefore = database.inlinePayloadCacheEntryCount();
      long bytesBefore = database.inlinePayloadCacheRetainedBytes();

      DfsPackDescription staged = database.newPack(PackSource.RECEIVE);
      byte[] packData = deterministicBytes(73, 11);
      byte[] reftableData = deterministicBytes(47, 17);
      write(database, staged, PackExt.PACK, packData);
      write(database, staged, PackExt.REFTABLE, reftableData);
      database.commitPackImpl(List.of(staged), null);

      assertEquals(entriesBefore + 2, database.inlinePayloadCacheEntryCount());
      assertEquals(
          bytesBefore + packData.length + reftableData.length,
          database.inlinePayloadCacheRetainedBytes());
      DfsPackDescription committed = description(database.listPacks(), baseName(staged));
      // The local one-shot above is followed by a real database catalog scan. Exact committed
      // identities must preserve the locally captured payloads.
      description(database.listPacks(), baseName(staged));
      PackFileReadMetrics readsBefore = repository.getPackFileReadMetrics();
      var operationsBefore = repository.getStorageOperationBreakdown();

      try (ReadableChannel channel = database.openFile(committed, PackExt.PACK)) {
        assertArrayEquals(packData, readFully(channel));
      }
      try (ReadableChannel channel = database.openFile(committed, PackExt.REFTABLE)) {
        assertArrayEquals(reftableData, readFully(channel));
      }

      assertEquals(
          PackFileReadMetrics.ZERO,
          repository.getPackFileReadMetrics().minus(readsBefore));
      assertEquals(
          StorageOperationMetrics.ZERO,
          repository
              .getStorageOperationBreakdown()
              .minus(operationsBefore)
              .metrics(StorageOperationKind.PACK_FILE_READ));
    }
  }

  @Test
  void historicalInlinePayloadIsNeverRetained() throws Exception {
    String repositoryName = "inline-handoff-historical";
    byte[] data = deterministicBytes(59, 23);
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      persistInline(provider, repositoryName, "pack-history", PackExt.INDEX, data);
      ReadAheadHibernateObjDatabase database = objectDatabase(repository);
      DfsPackDescription description = description(database.listPacks(), "pack-history");
      int entriesBefore = database.inlinePayloadCacheEntryCount();
      long bytesBefore = database.inlinePayloadCacheRetainedBytes();
      PackFileReadMetrics before = repository.getPackFileReadMetrics();

      try (ReadableChannel channel = database.openFile(description, PackExt.INDEX)) {
        assertArrayEquals(data, readFully(channel));
      }
      try (ReadableChannel channel = database.openFile(description, PackExt.INDEX)) {
        assertArrayEquals(data, readFully(channel));
      }

      assertEquals(entriesBefore, database.inlinePayloadCacheEntryCount());
      assertEquals(bytesBefore, database.inlinePayloadCacheRetainedBytes());
      assertEquals(
          new PackFileReadMetrics(0, 0, 2, 0, 0, 0, 0, 0, 0),
          repository.getPackFileReadMetrics().minus(before));
    }
  }

  @Test
  void localIdxPayloadIsNotRetained() throws Exception {
    String repositoryName = "inline-handoff-index";
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      ReadAheadHibernateObjDatabase database = objectDatabase(repository);
      database.listPacks();
      int entriesBefore = database.inlinePayloadCacheEntryCount();

      DfsPackDescription staged = database.newPack(PackSource.RECEIVE);
      byte[] indexData = deterministicBytes(37, 29);
      write(database, staged, PackExt.INDEX, indexData);
      database.commitPackImpl(List.of(staged), null);
      DfsPackDescription committed = description(database.listPacks(), baseName(staged));
      PackFileReadMetrics before = repository.getPackFileReadMetrics();

      try (ReadableChannel channel = database.openFile(committed, PackExt.INDEX)) {
        assertArrayEquals(indexData, readFully(channel));
      }

      assertEquals(entriesBefore, database.inlinePayloadCacheEntryCount());
      assertEquals(
          new PackFileReadMetrics(0, 0, 1, 0, 0, 0, 0, 0, 0),
          repository.getPackFileReadMetrics().minus(before));
    }
  }

  @Test
  void successfulReplacementCannotServeRemovedRowBytes() throws Exception {
    String repositoryName = "inline-handoff-replacement";
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      ReadAheadHibernateObjDatabase database = objectDatabase(repository);
      database.listPacks();
      int entriesBefore = database.inlinePayloadCacheEntryCount();

      DfsPackDescription first = database.newPack(PackSource.RECEIVE);
      byte[] firstData = deterministicBytes(41, 31);
      write(database, first, PackExt.PACK, firstData);
      database.commitPackImpl(List.of(first), null);
      DfsPackDescription committedFirst = description(database.listPacks(), baseName(first));
      try (ReadableChannel channel = database.openFile(committedFirst, PackExt.PACK)) {
        assertArrayEquals(firstData, readFully(channel));
      }
      assertEquals(entriesBefore + 1, database.inlinePayloadCacheEntryCount());

      DfsPackDescription replacement = database.newPack(PackSource.COMPACT);
      byte[] replacementData = deterministicBytes(43, 37);
      write(database, replacement, PackExt.PACK, replacementData);
      database.commitPackImpl(List.of(replacement), List.of(committedFirst));

      assertThrows(
          FileNotFoundException.class,
          () -> database.openFile(committedFirst, PackExt.PACK),
          "The removed logical pack must not be served from the old committed identity");
      DfsPackDescription committedReplacement =
          description(database.listPacks(), baseName(replacement));
      try (ReadableChannel channel = database.openFile(committedReplacement, PackExt.PACK)) {
        assertArrayEquals(replacementData, readFully(channel));
      }
      assertEquals(entriesBefore + 1, database.inlinePayloadCacheEntryCount());
    }
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:inline-handoff-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.connection.pool_size", "2");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    return new HibernateSessionFactoryProvider(properties);
  }

  private static ReadAheadHibernateObjDatabase objectDatabase(HibernateRepository repository) {
    return (ReadAheadHibernateObjDatabase) repository.getObjectDatabase();
  }

  private static void write(
      ReadAheadHibernateObjDatabase database,
      DfsPackDescription description,
      PackExt extension,
      byte[] data)
      throws IOException {
    try (DfsOutputStream output = database.writeFile(description, extension)) {
      output.write(data, 0, data.length);
    }
    description.addFileExt(extension);
    description.setFileSize(extension, data.length);
  }

  private static void persistInline(
      HibernateSessionFactoryProvider provider,
      String repositoryName,
      String packName,
      PackExt extension,
      byte[] data) {
    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      GitPackEntity entity = new GitPackEntity();
      entity.setRepositoryName(repositoryName);
      entity.setPackName(packName);
      entity.setPackExtension(extension.getExtension());
      entity.setData(data);
      entity.setFileSize(data.length);
      entity.setCommitted(true);
      entity.setCreatedAt(Instant.now());
      entity.setCommittedAt(Instant.now());
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
}
