/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageByteMetrics;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.junit.jupiter.api.Test;

class MemoryFirstStorageByteMetricsH2Test {

  @Test
  void memoryOnlyInlinePublicationAvoidsAllTemporaryFileTraffic() throws Exception {
    byte[] payload = bytes(12_345, 17);
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), "memory-byte-inline")) {
      repository.create(true);
      HibernateTransactionContext context =
          new HibernateTransactionContext(provider.getSessionFactory());
      StorageByteCounters counters = StorageByteCounters.from(provider.getSessionFactory());
      StagedPackExtensionStore store =
          new StagedPackExtensionStore("memory-byte-inline", context, counters);
      DfsPackDescription description =
          ((ReadAheadHibernateObjDatabase) repository.getObjectDatabase())
              .newPack(PackSource.RECEIVE);
      StorageByteMetrics before = store.storageByteMetricsSnapshot();

      write(store, description, payload);
      store.commit(List.of(description), null);

      StorageByteMetrics delta = store.storageByteMetricsSnapshot().minus(before);
      assertEquals(0, delta.temporaryFileBytesWritten());
      assertEquals(0, delta.temporaryFileBytesRead());
      assertEquals(payload.length, delta.databasePayloadBytesWritten());
    }
  }

  @Test
  void spilledPublicationCountsPhysicalFileTrafficExactly() throws Exception {
    byte[] payload = bytes(HibernateObjDatabase.PACK_CHUNK_SIZE + 37, 29);
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), "memory-byte-spill")) {
      repository.create(true);
      HibernateTransactionContext context =
          new HibernateTransactionContext(provider.getSessionFactory());
      StorageByteCounters counters = StorageByteCounters.from(provider.getSessionFactory());
      StagedPackExtensionStore store =
          new StagedPackExtensionStore("memory-byte-spill", context, counters);
      DfsPackDescription description =
          ((ReadAheadHibernateObjDatabase) repository.getObjectDatabase())
              .newPack(PackSource.RECEIVE);
      StorageByteMetrics before = store.storageByteMetricsSnapshot();

      write(store, description, payload);
      store.commit(List.of(description), null);

      StorageByteMetrics delta = store.storageByteMetricsSnapshot().minus(before);
      assertEquals(payload.length, delta.temporaryFileBytesWritten());
      assertEquals(payload.length, delta.temporaryFileBytesRead());
      assertEquals(payload.length, delta.databasePayloadBytesWritten());
    }
  }

  private static void write(
      StagedPackExtensionStore store,
      DfsPackDescription description,
      byte[] payload)
      throws Exception {
    try (DfsOutputStream stream = store.open(description, PackExt.PACK)) {
      stream.write(payload, 0, payload.length);
    }
    description.addFileExt(PackExt.PACK);
    description.setFileSize(PackExt.PACK, payload.length);
  }

  private static byte[] bytes(int length, int seed) {
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
        "jdbc:h2:mem:memory-first-bytes-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.enabled", "false");
    properties.put("hibernate.connection.pool_size", "4");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    return new HibernateSessionFactoryProvider(properties);
  }
}
