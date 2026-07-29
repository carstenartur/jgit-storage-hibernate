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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.PackFileReadMetrics;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationBreakdown;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationKind;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationMetrics;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.dfs.ReadableChannel;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;

class LocalInlinePayloadHandoffH2Test {

  @Test
  void reusesPackAndReftableWhileTheyRemainWithinBudget() throws Exception {
    try (Fixture fixture = fixture("inline-handoff")) {
      byte[] pack = deterministicBytes(257, 11);
      byte[] reftable = deterministicBytes(193, 13);
      DfsPackDescription description = fixture.database.newPack(PackSource.RECEIVE);
      write(fixture.database, description, PackExt.PACK, pack);
      write(fixture.database, description, PackExt.REFTABLE, reftable);

      fixture.database.commitPackImpl(List.of(description), null);
      int retainedBytes = pack.length + reftable.length;
      assertEquals(retainedBytes, fixture.database.localInlinePayloadBytes());

      Statistics statistics = fixture.provider.getSessionFactory().getStatistics();
      statistics.clear();
      StorageOperationBreakdown before = fixture.repository.getStorageOperationBreakdown();
      PackFileReadMetrics readBefore = fixture.repository.getPackFileReadMetrics();

      for (int repetition = 0; repetition < 2; repetition++) {
        assertArrayEquals(pack, open(fixture.database, description, PackExt.PACK));
        assertArrayEquals(reftable, open(fixture.database, description, PackExt.REFTABLE));
      }

      assertEquals(retainedBytes, fixture.database.localInlinePayloadBytes());
      assertEquals(0, statistics.getQueryExecutionCount());
      assertEquals(
          StorageOperationMetrics.ZERO,
          fixture.repository
              .getStorageOperationBreakdown()
              .minus(before)
              .metrics(StorageOperationKind.PACK_FILE_READ));
      assertEquals(
          PackFileReadMetrics.ZERO,
          fixture.repository.getPackFileReadMetrics().minus(readBefore));
    }
  }

  @Test
  void indexIsNeverCaptured() throws Exception {
    try (Fixture fixture = fixture("inline-index")) {
      byte[] index = deterministicBytes(127, 17);
      DfsPackDescription description = fixture.database.newPack(PackSource.INSERT);
      write(fixture.database, description, PackExt.INDEX, index);

      fixture.database.commitPackImpl(List.of(description), null);

      assertEquals(0, fixture.database.localInlinePayloadBytes());
      PackFileReadMetrics before = fixture.repository.getPackFileReadMetrics();
      assertArrayEquals(index, open(fixture.database, description, PackExt.INDEX));
      assertEquals(
          new PackFileReadMetrics(0, 0, 1, 0, 0, 0, 0, 0, 0),
          fixture.repository.getPackFileReadMetrics().minus(before));
    }
  }

  @Test
  void capsOnePublicationAtTwoInlineThresholds() throws Exception {
    try (Fixture fixture = fixture("inline-budget")) {
      int payloadSize = 200 * 1024;
      List<DfsPackDescription> descriptions = new ArrayList<>();
      List<byte[]> payloads = new ArrayList<>();
      for (int index = 0; index < 3; index++) {
        DfsPackDescription description = fixture.database.newPack(PackSource.INSERT);
        byte[] payload = deterministicBytes(payloadSize, 23 + index);
        write(fixture.database, description, PackExt.REFTABLE, payload);
        descriptions.add(description);
        payloads.add(payload);
      }

      fixture.database.commitPackImpl(descriptions, null);

      assertEquals(2 * payloadSize, fixture.database.localInlinePayloadBytes());
      assertTrue(
          fixture.database.localInlinePayloadBytes()
              <= StagedPackExtensionStore.LOCAL_INLINE_HANDOFF_BUDGET_BYTES);
      PackFileReadMetrics before = fixture.repository.getPackFileReadMetrics();
      for (int index = 0; index < descriptions.size(); index++) {
        assertArrayEquals(
            payloads.get(index),
            open(fixture.database, descriptions.get(index), PackExt.REFTABLE));
      }
      assertEquals(
          new PackFileReadMetrics(0, 0, 0, 0, 1, 0, 0, 0, 0),
          fixture.repository.getPackFileReadMetrics().minus(before));
    }
  }

  @Test
  void retainsConsecutivePackAndReftableWithinBudget() throws Exception {
    try (Fixture fixture = fixture("inline-consecutive-mutations")) {
      DfsPackDescription packDescription = fixture.database.newPack(PackSource.INSERT);
      byte[] pack = deterministicBytes(128 * 1024, 31);
      write(fixture.database, packDescription, PackExt.PACK, pack);
      fixture.database.commitPackImpl(List.of(packDescription), null);

      DfsPackDescription refDescription = fixture.database.newPack(PackSource.INSERT);
      byte[] reftable = deterministicBytes(1024, 37);
      write(fixture.database, refDescription, PackExt.REFTABLE, reftable);
      fixture.database.commitPackImpl(List.of(refDescription), null);

      assertEquals(pack.length + reftable.length, fixture.database.localInlinePayloadBytes());
      PackFileReadMetrics before = fixture.repository.getPackFileReadMetrics();
      for (int repetition = 0; repetition < 2; repetition++) {
        assertArrayEquals(pack, open(fixture.database, packDescription, PackExt.PACK));
        assertArrayEquals(reftable, open(fixture.database, refDescription, PackExt.REFTABLE));
      }
      assertEquals(
          PackFileReadMetrics.ZERO,
          fixture.repository.getPackFileReadMetrics().minus(before));
    }
  }

  @Test
  void evictsOldestPayloadAcrossMutationsWhenBudgetIsExceeded() throws Exception {
    try (Fixture fixture = fixture("inline-cross-mutation-eviction")) {
      int payloadSize = 200 * 1024;
      List<DfsPackDescription> descriptions = new ArrayList<>();
      List<byte[]> payloads = new ArrayList<>();
      for (int index = 0; index < 3; index++) {
        DfsPackDescription description = fixture.database.newPack(PackSource.INSERT);
        byte[] payload = deterministicBytes(payloadSize, 43 + index);
        write(fixture.database, description, PackExt.REFTABLE, payload);
        fixture.database.commitPackImpl(List.of(description), null);
        descriptions.add(description);
        payloads.add(payload);
      }

      assertEquals(2 * payloadSize, fixture.database.localInlinePayloadBytes());
      PackFileReadMetrics before = fixture.repository.getPackFileReadMetrics();
      for (int index = 0; index < descriptions.size(); index++) {
        assertArrayEquals(
            payloads.get(index),
            open(fixture.database, descriptions.get(index), PackExt.REFTABLE));
      }
      assertEquals(
          new PackFileReadMetrics(0, 0, 0, 0, 1, 0, 0, 0, 0),
          fixture.repository.getPackFileReadMetrics().minus(before));
    }
  }

  @Test
  void authoritativeCatalogScanDropsRetainedPayloads() throws Exception {
    try (Fixture fixture = fixture("inline-authoritative-scan")) {
      DfsPackDescription description = fixture.database.newPack(PackSource.INSERT);
      byte[] payload = deterministicBytes(89, 41);
      write(fixture.database, description, PackExt.REFTABLE, payload);
      fixture.database.commitPackImpl(List.of(description), null);
      assertEquals(payload.length, fixture.database.localInlinePayloadBytes());

      fixture.database.listPacks();
      assertEquals(payload.length, fixture.database.localInlinePayloadBytes());
      fixture.database.listPacks();

      assertEquals(0, fixture.database.localInlinePayloadBytes());
      PackFileReadMetrics before = fixture.repository.getPackFileReadMetrics();
      assertArrayEquals(payload, open(fixture.database, description, PackExt.REFTABLE));
      assertEquals(
          new PackFileReadMetrics(0, 0, 0, 0, 1, 0, 0, 0, 0),
          fixture.repository.getPackFileReadMetrics().minus(before));
    }
  }

  private static Fixture fixture(String repositoryName) throws Exception {
    HibernateSessionFactoryProvider provider = provider();
    HibernateRepository repository =
        HibernateRepository.create(provider.getSessionFactory(), repositoryName);
    repository.create(true);
    ReadAheadHibernateObjDatabase database =
        (ReadAheadHibernateObjDatabase) repository.getObjectDatabase();
    database.listPacks();
    return new Fixture(provider, repository, database);
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

  private static byte[] open(
      ReadAheadHibernateObjDatabase database,
      DfsPackDescription description,
      PackExt extension)
      throws IOException {
    try (ReadableChannel channel = database.openFile(description, extension)) {
      ByteBuffer destination = ByteBuffer.allocate(Math.toIntExact(channel.size()));
      while (destination.hasRemaining()) {
        int count = channel.read(destination);
        if (count < 0) {
          break;
        }
      }
      return destination.array();
    }
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
        "jdbc:h2:mem:local-inline-handoff-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.generate_statistics", "true");
    properties.put("hibernate.connection.pool_size", "2");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    return new HibernateSessionFactoryProvider(properties);
  }

  private record Fixture(
      HibernateSessionFactoryProvider provider,
      HibernateRepository repository,
      ReadAheadHibernateObjDatabase database)
      implements AutoCloseable {
    @Override
    public void close() {
      repository.close();
      provider.close();
    }
  }
}
