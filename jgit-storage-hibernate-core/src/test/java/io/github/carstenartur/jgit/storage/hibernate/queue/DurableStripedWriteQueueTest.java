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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitRepositoryLifecycleEntity;
import io.github.carstenartur.jgit.storage.hibernate.queue.DurableStripedWriteQueue.Submission;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationKind;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;

class DurableStripedWriteQueueTest {

  private static final Duration EXACT_BATCH_WAIT = Duration.ofMinutes(1);

  @Test
  void persistsFiftyRecordsInOneTransactionAndAcknowledgesOnlyAfterCommit() throws Exception {
    Properties properties = h2Properties();
    try (HibernateSessionFactoryProvider provider =
        new HibernateSessionFactoryProvider(properties)) {
      HibernateTransactionContext transactionContext =
          new HibernateTransactionContext(provider.getSessionFactory());
      CountDownLatch flushed = new CountDownLatch(1);
      CountDownLatch allowCommit = new CountDownLatch(1);
      List<Integer> observedBatchSizes = Collections.synchronizedList(new ArrayList<>());

      DurableStripedWriteQueue.Limits limits =
          new DurableStripedWriteQueue.Limits(
              1,
              100,
              1024,
              50,
              1024,
              EXACT_BATCH_WAIT,
              Duration.ofSeconds(1));
      try (DurableStripedWriteQueue<Record, String> queue =
          new DurableStripedWriteQueue<>(
              limits,
              (repositoryName, records) ->
                  transactionContext.execute(
                      StorageOperationKind.OTHER,
                      session -> {
                        observedBatchSizes.add(records.size());
                        for (Record record : records) {
                          GitRepositoryLifecycleEntity entity =
                              new GitRepositoryLifecycleEntity();
                          entity.setRepositoryName(record.name());
                          entity.setCreatedAt(Instant.now());
                          session.persist(entity);
                        }
                        session.flush();
                        flushed.countDown();
                        await(allowCommit, "Timed out waiting to commit test batch");
                        return records.stream().map(Record::name).toList();
                      }))) {
        List<Submission<String>> submissions = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
          Record record = new Record("record-" + index);
          submissions.add(queue.submit("repository", 1, record));
        }

        assertTrue(flushed.await(5, TimeUnit.SECONDS));
        assertTrue(
            submissions.stream().noneMatch(submission -> submission.completion().isDone()),
            "No command may be acknowledged before the transaction commits");

        allowCommit.countDown();
        for (int index = 0; index < submissions.size(); index++) {
          assertEquals(
              "record-" + index,
              submissions.get(index).completion().get(5, TimeUnit.SECONDS));
          assertEquals(50, submissions.get(index).batchSize());
          assertTrue(
              queue.metrics().completed() >= index + 1L,
              "A successful future must not become observable before its completion metric");
        }

        assertEquals(List.of(50), observedBatchSizes);
        assertEquals(50L, lifecycleCount(provider));
        assertEquals(1L, queue.metrics().batches());
        assertEquals(50L, queue.metrics().completed());
        assertEquals(50L, queue.metrics().maximumBatchSize());
      }
    }
  }

  @Test
  void flushesAllAvailableRecordsWhenTheConfiguredWaitExpires() throws Exception {
    CountDownLatch processed = new CountDownLatch(1);
    List<List<Integer>> batches = Collections.synchronizedList(new ArrayList<>());
    DurableStripedWriteQueue.Limits limits =
        new DurableStripedWriteQueue.Limits(
            1,
            100,
            1024,
            50,
            1024,
            Duration.ofMillis(25),
            Duration.ofSeconds(1));

    try (DurableStripedWriteQueue<Integer, Integer> queue =
        new DurableStripedWriteQueue<>(
            limits,
            (repositoryName, records) -> {
              batches.add(records);
              processed.countDown();
              return records;
            })) {
      List<Submission<Integer>> submissions =
          List.of(
              queue.submit("repository", 1, 1),
              queue.submit("repository", 1, 2),
              queue.submit("repository", 1, 3));

      assertTrue(processed.await(5, TimeUnit.SECONDS));
      for (int index = 0; index < submissions.size(); index++) {
        assertEquals(index + 1, submissions.get(index).completion().get(5, TimeUnit.SECONDS));
        assertEquals(3, submissions.get(index).batchSize());
      }
      assertEquals(List.of(List.of(1, 2, 3)), batches);
    }
  }

  @Test
  void neverMixesRepositoriesInOneAtomicBatch() throws Exception {
    List<String> batchRepositories = Collections.synchronizedList(new ArrayList<>());
    List<List<String>> batches = Collections.synchronizedList(new ArrayList<>());
    DurableStripedWriteQueue.Limits limits =
        new DurableStripedWriteQueue.Limits(
            1,
            100,
            1024,
            4,
            1024,
            Duration.ofMillis(10),
            Duration.ofSeconds(1));

    try (DurableStripedWriteQueue<String, String> queue =
        new DurableStripedWriteQueue<>(
            limits,
            (repositoryName, records) -> {
              batchRepositories.add(repositoryName);
              batches.add(records);
              return records;
            })) {
      List<Submission<String>> submissions =
          List.of(
              queue.submit("repo-a", 1, "a1"),
              queue.submit("repo-b", 1, "b1"),
              queue.submit("repo-a", 1, "a2"),
              queue.submit("repo-b", 1, "b2"));

      for (Submission<String> submission : submissions) {
        submission.completion().get(5, TimeUnit.SECONDS);
      }

      assertEquals(List.of("repo-a", "repo-b"), batchRepositories);
      assertEquals(List.of(List.of("a1", "a2"), List.of("b1", "b2")), batches);
    }
  }

  @Test
  void transactionFailureFailsEveryCommandInTheAtomicBatch() throws Exception {
    DurableStripedWriteQueue.Limits limits =
        new DurableStripedWriteQueue.Limits(
            1,
            10,
            1024,
            3,
            1024,
            EXACT_BATCH_WAIT,
            Duration.ofSeconds(1));

    try (DurableStripedWriteQueue<Integer, Integer> queue =
        new DurableStripedWriteQueue<>(
            limits,
            (repositoryName, records) -> {
              throw new IOException("rollback");
            })) {
      List<Submission<Integer>> submissions =
          List.of(
              queue.submit("repository", 1, 1),
              queue.submit("repository", 1, 2),
              queue.submit("repository", 1, 3));

      for (int index = 0; index < submissions.size(); index++) {
        assertThrows(CompletionException.class, submissions.get(index).completion()::join);
        assertTrue(
            queue.metrics().failed() >= index + 1L,
            "An exceptional future must not become observable before its failed metric");
      }
      assertEquals(3L, queue.metrics().failed());
      assertEquals(1L, queue.metrics().failedBatches());
      assertEquals(0L, queue.metrics().completed());
    }
  }

  @Test
  void productionDefaultsUseFiftyRecordsAndABoundedWait() {
    DurableStripedWriteQueue.Limits limits =
        DurableStripedWriteQueue.Limits.productionDefaults(4);

    assertEquals(4, limits.stripes());
    assertEquals(50, limits.maxBatchCommands());
    assertEquals(Duration.ofMillis(2), limits.maxBatchWait());
    assertTrue(limits.maxBatchBytes() <= limits.maxQueuedBytesPerStripe());
    assertFalse(limits.enqueueTimeout().isNegative());
  }

  private static Properties h2Properties() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:durable-batch-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return properties;
  }

  private static long lifecycleCount(HibernateSessionFactoryProvider provider) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery("SELECT COUNT(l) FROM GitRepositoryLifecycleEntity l", Long.class)
          .getSingleResult();
    }
  }

  private static void await(CountDownLatch latch, String message) throws IOException {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IOException(message);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting for test batch", exception);
    }
  }

  private record Record(String name) {}
}
