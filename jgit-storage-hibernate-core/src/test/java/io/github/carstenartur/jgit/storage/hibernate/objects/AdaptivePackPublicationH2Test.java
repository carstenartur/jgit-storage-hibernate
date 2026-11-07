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

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateStorageSettings;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.PackPublicationPolicySnapshot.Reason;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationBreakdown;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationKind;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationMetrics;
import java.io.IOException;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.junit.jupiter.api.Test;

class AdaptivePackPublicationH2Test {

  @Test
  void keepsSmallChunkedLogicalPackOnSingleTransactionPath() throws Exception {
    verifyPublicationMode(
        "adaptive-direct",
        HibernateObjDatabase.INLINE_PAYLOAD_THRESHOLD + 257,
        1,
        StorageOperationMetrics.ZERO);
  }

  @Test
  void prePersistsLogicalPackAtDefaultOneMiBThreshold() throws Exception {
    int indexBytes = 97;
    int packBytes =
        Math.toIntExact(StagedPackExtensionStore.DEFAULT_PREPERSIST_MIN_PAYLOAD_BYTES - indexBytes);
    verifyPublicationMode(
        "adaptive-pre-persisted", packBytes, 2, new StorageOperationMetrics(1, 1, 0, 0, 0));
  }

  @Test
  void aHotRepositoryPublishesAnEligiblePackBelowTheSizeThreshold() throws Exception {
    String repositoryName = "contention-promotion";
    try (HibernateSessionFactoryProvider provider = provider(true);
        HibernateRepository writer =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName);
        HibernateRepository reader =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      writer.create(true);
      PackPublicationSelector policy =
          PackPublicationSelector.configured(provider.getSessionFactory(), repositoryName, true);
      byte[] payload = new byte[768 * 1024];
      new Random(17).nextBytes(payload);

      // Supply completed timing samples directly so the path regression does not depend on host
      // scheduling. The actual publication below uses both real Hibernate transactions.
      long start = System.nanoTime() - TimeUnit.SECONDS.toNanos(1);
      for (int sample = 0; sample < 3; sample++) {
        long now = start + TimeUnit.MILLISECONDS.toNanos(100L * sample);
        try (var holding = policy.begin(payload.length, now);
            var waiting = policy.begin(payload.length, now)) {
          holding.beforeLock();
          waiting.beforeLock();
          waiting.lockAcquired(TimeUnit.MILLISECONDS.toNanos(20));
          holding.afterLock();
          waiting.afterLock();
          waiting.committed(now + TimeUnit.MILLISECONDS.toNanos(40));
        }
      }

      StorageOperationBreakdown before = writer.getStorageOperationBreakdown();
      ObjectId blob;
      try (ObjectInserter inserter = writer.newObjectInserter()) {
        blob = inserter.insert(Constants.OBJ_BLOB, payload);
        inserter.flush();
      }

      assertEquals(
          Reason.SUSTAINED_CONTENTION, writer.getPackPublicationPolicySnapshot().lastReason());
      StorageOperationBreakdown written = writer.getStorageOperationBreakdown().minus(before);
      assertEquals(
          1, written.metrics(StorageOperationKind.PACK_EXTENSION_WRITE).transactionsCommitted());
      assertEquals(
          1, written.metrics(StorageOperationKind.PACK_PUBLICATION).transactionsCommitted());
      assertEquals(1, writer.getPackPublicationSelectionMetrics().prePersistedSelections());
      assertArrayEquals(payload, reader.open(blob, Constants.OBJ_BLOB).getBytes());
    }
  }

  private static void verifyPublicationMode(
      String repositoryName,
      int packLength,
      long expectedTransactions,
      StorageOperationMetrics expectedExtensionWriteShape)
      throws Exception {
    byte[] packBytes = deterministicBytes(packLength, 17);
    byte[] indexBytes = deterministicBytes(97, 29);

    try (HibernateSessionFactoryProvider provider = provider(false);
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      HibernateTransactionContext context =
          new HibernateTransactionContext(provider.getSessionFactory());
      ReadAheadHibernateObjDatabase database =
          (ReadAheadHibernateObjDatabase) repository.getObjectDatabase();
      DfsPackDescription description = database.newPack(PackSource.RECEIVE);
      StagedPackExtensionStore store = new StagedPackExtensionStore(repositoryName, context);
      write(store, description, PackExt.PACK, packBytes);
      write(store, description, PackExt.INDEX, indexBytes);

      StorageOperationMetrics before = context.metricsSnapshot();
      StorageOperationBreakdown breakdownBefore = context.operationBreakdownSnapshot();
      store.commit(List.of(description), null);

      StorageOperationMetrics aggregate = context.metricsSnapshot().minus(before);
      StorageOperationBreakdown breakdown =
          context.operationBreakdownSnapshot().minus(breakdownBefore);
      assertEquals(expectedTransactions, aggregate.transactionsStarted());
      assertEquals(expectedTransactions, aggregate.transactionsCommitted());
      assertEquals(0, aggregate.transactionsRolledBack());
      assertEquals(1, aggregate.repositoryLocksAcquired());
      assertEquals(aggregate, breakdown.total());

      StorageOperationMetrics extensionWrite =
          breakdown.metrics(StorageOperationKind.PACK_EXTENSION_WRITE);
      assertEquals(
          expectedExtensionWriteShape.transactionsStarted(), extensionWrite.transactionsStarted());
      assertEquals(
          expectedExtensionWriteShape.transactionsCommitted(),
          extensionWrite.transactionsCommitted());
      assertEquals(0, extensionWrite.transactionsRolledBack());
      assertEquals(0, extensionWrite.repositoryLocksAcquired());
      assertEquals(0, extensionWrite.repositoryLockHeldNanos());

      StorageOperationMetrics publication =
          breakdown.metrics(StorageOperationKind.PACK_PUBLICATION);
      assertEquals(1, publication.transactionsStarted());
      assertEquals(1, publication.transactionsCommitted());
      assertEquals(1, publication.repositoryLocksAcquired());
      assertEquals(0, store.stagedExtensionCount());
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

  private static byte[] deterministicBytes(int length, int seed) {
    byte[] result = new byte[length];
    int value = seed;
    for (int index = 0; index < result.length; index++) {
      value = value * 1103515245 + 12345;
      result[index] = (byte) (value >>> 16);
    }
    return result;
  }

  private static HibernateSessionFactoryProvider provider(boolean adaptive) {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:adaptive-publication-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.connection.pool_size", "4");
    properties.put("hibernate.search.enabled", "false");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    if (adaptive) {
      properties.put(HibernateStorageSettings.PACK_PUBLICATION_SELECTOR, "contention-aware");
    }
    return new HibernateSessionFactoryProvider(properties);
  }
}
