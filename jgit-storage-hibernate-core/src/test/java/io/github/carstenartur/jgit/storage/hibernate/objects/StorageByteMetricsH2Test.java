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

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageByteMetrics;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.dfs.ReadableChannel;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.junit.jupiter.api.Test;

class StorageByteMetricsH2Test {

  @Test
  void countsTemporaryFileTrafficAndCommittedInlinePayloadBytes() throws Exception {
    String repositoryName = "byte-metrics-staging";
    byte[] payload = deterministicBytes(193, 17);

    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      ReadAheadHibernateObjDatabase database = database(repository);
      DfsPackDescription description = database.newPack(PackSource.RECEIVE);
      StorageByteMetrics before = repository.getStorageByteMetrics();

      try (DfsOutputStream stream = database.writeFile(description, PackExt.PACK)) {
        stream.write(payload, 0, payload.length);
        ByteBuffer selected = ByteBuffer.allocate(17);
        assertEquals(17, stream.read(31, selected));
      }
      description.addFileExt(PackExt.PACK);
      description.setFileSize(PackExt.PACK, payload.length);
      database.commitPackImpl(List.of(description), null);

      StorageByteMetrics delta = repository.getStorageByteMetrics().minus(before);
      assertEquals(payload.length, delta.temporaryFileBytesWritten());
      assertEquals(payload.length + 17L, delta.temporaryFileBytesRead());
      assertEquals(payload.length, delta.databasePayloadBytesWritten());
      assertEquals(0, delta.databasePayloadBytesRead());
      assertEquals(0, delta.readAheadBytesFetched());
      assertEquals(0, delta.readAheadBytesConsumed());
      assertEquals(0, delta.readAheadOverfetchBytes());
    }
  }

  @Test
  void countsInlinePayloadLoadedByAuthoritativeDatabaseFallback() throws Exception {
    String repositoryName = "byte-metrics-inline-read";
    byte[] payload = deterministicBytes(211, 29);

    try (HibernateSessionFactoryProvider provider = provider()) {
      writePack(provider, repositoryName, payload);

      try (HibernateRepository repository =
          HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
        ReadAheadHibernateObjDatabase database = database(repository);
        DfsPackDescription description = database.listPacks().getFirst();
        StorageByteMetrics before = repository.getStorageByteMetrics();

        try (ReadableChannel channel = database.openFile(description, PackExt.PACK)) {
          ByteBuffer destination = ByteBuffer.allocate(payload.length);
          assertEquals(payload.length, channel.read(destination));
        }

        StorageByteMetrics delta = repository.getStorageByteMetrics().minus(before);
        assertEquals(payload.length, delta.databasePayloadBytesRead());
        assertEquals(0, delta.readAheadBytesFetched());
        assertEquals(0, delta.readAheadBytesConsumed());
        assertEquals(0, delta.readAheadOverfetchBytes());
      }
    }
  }

  @Test
  void attributesFetchedConsumedAndDiscardedReadAheadBytes() throws Exception {
    String repositoryName = "byte-metrics-read-ahead";
    byte[] payload =
        deterministicBytes(HibernateObjDatabase.PACK_CHUNK_SIZE * 2 + 257, 43);

    try (HibernateSessionFactoryProvider provider = provider()) {
      writePack(provider, repositoryName, payload);

      try (HibernateRepository repository =
          HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
        ReadAheadHibernateObjDatabase database = database(repository);
        DfsPackDescription description = database.listPacks().getFirst();
        StorageByteMetrics before = repository.getStorageByteMetrics();

        try (ReadableChannel channel = database.openFile(description, PackExt.PACK)) {
          channel.setReadAheadBytes(payload.length);
          assertEquals(32, channel.read(ByteBuffer.allocate(32)));
        }

        StorageByteMetrics delta = repository.getStorageByteMetrics().minus(before);
        assertEquals(payload.length, delta.databasePayloadBytesRead());
        assertEquals(payload.length, delta.readAheadBytesFetched());
        assertEquals(32, delta.readAheadBytesConsumed());
        assertEquals(payload.length - 32L, delta.readAheadOverfetchBytes());
      }
    }
  }

  @Test
  void returnsZeroByteMetricsWhenDiagnosticsAreDisabled() throws Exception {
    Properties properties = properties(false);
    try (HibernateSessionFactoryProvider provider = new HibernateSessionFactoryProvider(properties);
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), "byte-metrics-disabled")) {
      repository.create(true);
      assertEquals(StorageByteMetrics.ZERO, repository.getStorageByteMetrics());
    }
  }

  private static void writePack(
      HibernateSessionFactoryProvider provider, String repositoryName, byte[] payload)
      throws Exception {
    try (HibernateRepository repository =
        HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      ReadAheadHibernateObjDatabase database = database(repository);
      DfsPackDescription description = database.newPack(PackSource.RECEIVE);
      try (DfsOutputStream stream = database.writeFile(description, PackExt.PACK)) {
        stream.write(payload, 0, payload.length);
      }
      description.addFileExt(PackExt.PACK);
      description.setFileSize(PackExt.PACK, payload.length);
      database.commitPackImpl(List.of(description), null);
    }
  }

  private static ReadAheadHibernateObjDatabase database(HibernateRepository repository) {
    return (ReadAheadHibernateObjDatabase) repository.getObjectDatabase();
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
    return new HibernateSessionFactoryProvider(properties(true));
  }

  private static Properties properties(boolean metricsEnabled) {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:storage-byte-metrics-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.connection.pool_size", "4");
    properties.put("hibernate.search.enabled", "false");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, metricsEnabled);
    return properties;
  }
}
