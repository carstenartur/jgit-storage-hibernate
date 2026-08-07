/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackChunkEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackChunkId;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitRepositoryLifecycleEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitRepositoryLockEntity;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.Session;
import org.hibernate.SessionEventListener;
import org.hibernate.Transaction;
import org.junit.jupiter.api.Test;

class HibernateJdbcBatchingH2Test {

  @Test
  void defaultBootstrapExecutesPackChunkInsertsAsJdbcBatches() {
    try (HibernateSessionFactoryProvider provider = provider(new Properties())) {
      assertEquals(
          HibernateStorageSettings.DEFAULT_JDBC_BATCH_SIZE,
          provider.getSessionFactory().getSessionFactoryOptions().getJdbcBatchSize());

      Long packId = persistPack(provider);
      CountingSessionEventListener.reset();
      int chunkCount = 20;
      persistChunks(provider, packId, chunkCount);

      assertEquals(chunkCount, chunkCount(provider, packId));
      int minimumExpectedBatches =
          Math.ceilDiv(chunkCount, HibernateStorageSettings.DEFAULT_JDBC_BATCH_SIZE);
      assertTrue(
          CountingSessionEventListener.batchExecutions() >= minimumExpectedBatches,
          () ->
              chunkCount
                  + " rows with default batch size "
                  + HibernateStorageSettings.DEFAULT_JDBC_BATCH_SIZE
                  + " must execute at least "
                  + minimumExpectedBatches
                  + " JDBC batches");
      try (Session session = provider.getSessionFactory().openSession()) {
        assertNotNull(session.find(GitPackChunkEntity.class, new GitPackChunkId(packId, 7)));
      }
    }
  }

  @Test
  void explicitConsumerBatchSizeOverridesTheLibraryDefault() {
    Properties overrides = new Properties();
    overrides.put(HibernateStorageSettings.JDBC_BATCH_SIZE, "3");
    overrides.put(HibernateStorageSettings.ORDER_INSERTS, "false");

    try (HibernateSessionFactoryProvider provider = provider(overrides)) {
      assertEquals(3, provider.getSessionFactory().getSessionFactoryOptions().getJdbcBatchSize());

      Long packId = persistPack(provider);
      CountingSessionEventListener.reset();
      persistChunks(provider, packId, 7);

      assertTrue(
          CountingSessionEventListener.batchExecutions() >= 3,
          "Seven rows with an explicit batch size of three must not use the default batch size");
    }
  }

  @Test
  void explicitPackChunkWindowAlsoConfiguresJdbcBatchingWhenNotOverridden() {
    Properties overrides = new Properties();
    overrides.put(HibernateStorageSettings.PACK_CHUNK_BATCH_SIZE, "50");

    try (HibernateSessionFactoryProvider provider = provider(overrides)) {
      assertEquals(50, provider.getSessionFactory().getSessionFactoryOptions().getJdbcBatchSize());
      assertEquals(
          50,
          HibernateStorageSettings.resolvePackChunkBatchSize(
              provider.getSessionFactory().getProperties()));
    }
  }

  private static HibernateSessionFactoryProvider provider(Properties overrides) {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:jdbc-batching-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.session.events.auto", CountingSessionEventListener.class.getName());
    properties.putAll(overrides);
    return new HibernateSessionFactoryProvider(properties);
  }

  private static Long persistPack(HibernateSessionFactoryProvider provider) {
    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      Instant createdAt = Instant.now();

      GitRepositoryLifecycleEntity lifecycle = new GitRepositoryLifecycleEntity();
      lifecycle.setRepositoryName("batching");
      lifecycle.setCreatedAt(createdAt);
      session.persist(lifecycle);

      GitRepositoryLockEntity lock = new GitRepositoryLockEntity();
      lock.setRepositoryName("batching");
      lock.setCreatedAt(createdAt);
      session.persist(lock);

      GitPackEntity pack = new GitPackEntity();
      pack.setRepositoryName("batching");
      pack.setPackName("pack-" + UUID.randomUUID());
      pack.setPackExtension("pack");
      pack.setData(null);
      pack.setFileSize(20L * 1024L * 1024L);
      pack.setCommitted(true);
      pack.setCreatedAt(createdAt);
      pack.setCommittedAt(createdAt);
      session.persist(pack);
      transaction.commit();
      return pack.getId();
    }
  }

  private static void persistChunks(
      HibernateSessionFactoryProvider provider, Long packId, int chunkCount) {
    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      for (int index = 0; index < chunkCount; index++) {
        GitPackChunkEntity chunk = new GitPackChunkEntity();
        chunk.setPackId(packId);
        chunk.setChunkIndex(index);
        chunk.setChunkSize(4);
        chunk.setData(new byte[] {(byte) index, 1, 2, 3});
        session.persist(chunk);
      }
      transaction.commit();
    }
  }

  private static long chunkCount(HibernateSessionFactoryProvider provider, Long packId) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT COUNT(c) FROM GitPackChunkEntity c WHERE c.packId = :packId", Long.class)
          .setParameter("packId", packId)
          .getSingleResult();
    }
  }

  public static final class CountingSessionEventListener implements SessionEventListener {
    private static final AtomicInteger BATCH_EXECUTIONS = new AtomicInteger();

    @Override
    public void jdbcExecuteBatchStart() {
      BATCH_EXECUTIONS.incrementAndGet();
    }

    static void reset() {
      BATCH_EXECUTIONS.set(0);
    }

    static int batchExecutions() {
      return BATCH_EXECUTIONS.get();
    }
  }
}
