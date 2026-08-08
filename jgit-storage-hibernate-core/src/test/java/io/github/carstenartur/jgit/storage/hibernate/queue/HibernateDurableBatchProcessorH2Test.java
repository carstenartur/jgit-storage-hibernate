/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitRepositoryLifecycleEntity;
import io.github.carstenartur.jgit.storage.hibernate.queue.HibernateDurableBatchProcessor.Locking;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationKind;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.Session;
import org.hibernate.SessionEventListener;
import org.junit.jupiter.api.Test;

class HibernateDurableBatchProcessorH2Test {

  private static final Duration EXACT_BATCH_WAIT = Duration.ofMinutes(1);

  @Test
  void oneQueueBatchCommitsThroughTheHibernateAdapterBeforeAcknowledgement() throws Exception {
    try (HibernateSessionFactoryProvider provider =
        new HibernateSessionFactoryProvider(h2Properties())) {
      AtomicInteger observedJdbcBatchSize = new AtomicInteger();
      HibernateDurableBatchProcessor<String, String> processor =
          new HibernateDurableBatchProcessor<>(
              provider.getSessionFactory(),
              StorageOperationKind.OTHER,
              Locking.NONE,
              (session, repositoryName, names) -> {
                observedJdbcBatchSize.set(session.getJdbcBatchSize());
                persistLifecycleRows(session, names);
                return names;
              });
      DurableStripedWriteQueue.Limits limits =
          new DurableStripedWriteQueue.Limits(
              1,
              10,
              1024,
              3,
              1024,
              EXACT_BATCH_WAIT,
              Duration.ofSeconds(1));

      try (DurableStripedWriteQueue<String, String> queue =
          new DurableStripedWriteQueue<>(limits, processor)) {
        List<DurableStripedWriteQueue.Submission<String>> submissions =
            List.of(
                queue.submit("logical-repository", 1, "adapter-1"),
                queue.submit("logical-repository", 1, "adapter-2"),
                queue.submit("logical-repository", 1, "adapter-3"));

        for (int index = 0; index < submissions.size(); index++) {
          assertEquals(
              "adapter-" + (index + 1),
              submissions.get(index).completion().get(5, TimeUnit.SECONDS));
          assertEquals(3, submissions.get(index).batchSize());
        }
        assertEquals(3, observedJdbcBatchSize.get());
        assertEquals(3L, lifecycleCount(provider));
      }
    }
  }

  @Test
  void fiftyCompatibleReceiverRecordsExecuteAsOneJdbcBatch() throws Exception {
    try (HibernateSessionFactoryProvider provider =
        new HibernateSessionFactoryProvider(h2Properties())) {
      AtomicInteger observedJdbcBatchSize = new AtomicInteger();
      HibernateDurableBatchProcessor<String, String> processor =
          new HibernateDurableBatchProcessor<>(
              provider.getSessionFactory(),
              StorageOperationKind.OTHER,
              Locking.NONE,
              (session, repositoryName, names) -> {
                observedJdbcBatchSize.set(session.getJdbcBatchSize());
                persistLifecycleRows(session, names);
                return names;
              });
      DurableStripedWriteQueue.Limits limits =
          new DurableStripedWriteQueue.Limits(
              1,
              100,
              1024,
              50,
              1024,
              EXACT_BATCH_WAIT,
              Duration.ofSeconds(1));

      BatchCountingSessionEventListener.reset();
      try (DurableStripedWriteQueue<String, String> queue =
          new DurableStripedWriteQueue<>(limits, processor)) {
        List<DurableStripedWriteQueue.Submission<String>> submissions = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
          submissions.add(
              queue.submit("logical-repository", 1, "jdbc-batch-" + index));
        }

        for (int index = 0; index < submissions.size(); index++) {
          assertEquals(
              "jdbc-batch-" + index,
              submissions.get(index).completion().get(5, TimeUnit.SECONDS));
          assertEquals(50, submissions.get(index).batchSize());
        }
        assertEquals(50, observedJdbcBatchSize.get());
        assertEquals(1, BatchCountingSessionEventListener.batchExecutions());
        assertEquals(50L, lifecycleCount(provider));
      }
    }
  }

  @Test
  void invalidResultCountRollsBackTheWholeHibernateBatch() throws Exception {
    try (HibernateSessionFactoryProvider provider =
        new HibernateSessionFactoryProvider(h2Properties())) {
      HibernateDurableBatchProcessor<String, String> processor =
          new HibernateDurableBatchProcessor<>(
              provider.getSessionFactory(),
              StorageOperationKind.OTHER,
              Locking.NONE,
              (session, repositoryName, names) -> {
                persistLifecycleRows(session, names);
                return List.of(names.getFirst());
              });
      DurableStripedWriteQueue.Limits limits =
          new DurableStripedWriteQueue.Limits(
              1,
              10,
              1024,
              3,
              1024,
              EXACT_BATCH_WAIT,
              Duration.ofSeconds(1));

      try (DurableStripedWriteQueue<String, String> queue =
          new DurableStripedWriteQueue<>(limits, processor)) {
        List<DurableStripedWriteQueue.Submission<String>> submissions =
            List.of(
                queue.submit("logical-repository", 1, "rollback-1"),
                queue.submit("logical-repository", 1, "rollback-2"),
                queue.submit("logical-repository", 1, "rollback-3"));

        for (DurableStripedWriteQueue.Submission<String> submission : submissions) {
          CompletionException failure =
              assertThrows(CompletionException.class, submission.completion()::join);
          assertEquals(IOException.class, failure.getCause().getClass());
        }
        assertEquals(0L, lifecycleCount(provider));
        assertEquals(3L, queue.metrics().failed());
      }
    }
  }

  private static void persistLifecycleRows(Session session, List<String> names) {
    for (String name : names) {
      GitRepositoryLifecycleEntity entity = new GitRepositoryLifecycleEntity();
      entity.setRepositoryName(name);
      entity.setCreatedAt(Instant.now());
      session.persist(entity);
    }
  }

  private static Properties h2Properties() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:hibernate-queue-adapter-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put(
        "hibernate.session.events.auto", BatchCountingSessionEventListener.class.getName());
    return properties;
  }

  private static long lifecycleCount(HibernateSessionFactoryProvider provider) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery("SELECT COUNT(l) FROM GitRepositoryLifecycleEntity l", Long.class)
          .getSingleResult();
    }
  }

  public static final class BatchCountingSessionEventListener implements SessionEventListener {
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
