/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class DurableStripedWriteQueueTest {

  @Test
  void preservesRepositoryFifoAndAcknowledgesOnlyAfterExecution() throws Exception {
    DurableStripedWriteQueue.Limits limits =
        new DurableStripedWriteQueue.Limits(
            1, 8, 1024, 8, 1024, Duration.ofMillis(2), Duration.ofSeconds(1));
    List<Integer> order = Collections.synchronizedList(new ArrayList<>());
    CountDownLatch firstStarted = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);

    try (DurableStripedWriteQueue queue = new DurableStripedWriteQueue(limits)) {
      DurableStripedWriteQueue.Submission<Integer> first =
          queue.submit(
              "repo",
              1,
              () -> {
                firstStarted.countDown();
                releaseFirst.await();
                order.add(1);
                return 1;
              });
      assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
      DurableStripedWriteQueue.Submission<Integer> second =
          queue.submit(
              "repo",
              1,
              () -> {
                order.add(2);
                return 2;
              });
      DurableStripedWriteQueue.Submission<Integer> third =
          queue.submit(
              "repo",
              1,
              () -> {
                order.add(3);
                return 3;
              });

      assertFalse(first.completion().isDone());
      assertFalse(second.completion().isDone());
      releaseFirst.countDown();

      assertEquals(1, first.completion().get(5, TimeUnit.SECONDS));
      assertEquals(2, second.completion().get(5, TimeUnit.SECONDS));
      assertEquals(3, third.completion().get(5, TimeUnit.SECONDS));
      assertEquals(List.of(1, 2, 3), order);
      awaitCompleted(queue, 3);
      assertEquals(0, queue.metrics().failed());
    }
  }

  @Test
  void independentRepositoriesMappedToDifferentStripesProgressTogether() throws Exception {
    DurableStripedWriteQueue.Limits limits =
        new DurableStripedWriteQueue.Limits(
            2, 8, 1024, 1, 1024, Duration.ZERO, Duration.ofSeconds(1));
    try (DurableStripedWriteQueue queue = new DurableStripedWriteQueue(limits)) {
      String firstRepository = "repo-0";
      String secondRepository = "repo-1";
      while (queue.stripeIndex(firstRepository) == queue.stripeIndex(secondRepository)) {
        secondRepository += "x";
      }

      CountDownLatch bothStarted = new CountDownLatch(2);
      CountDownLatch release = new CountDownLatch(1);
      DurableStripedWriteQueue.Submission<String> first =
          queue.submit(
              firstRepository,
              1,
              () -> {
                bothStarted.countDown();
                release.await();
                return firstRepository;
              });
      String resolvedSecondRepository = secondRepository;
      DurableStripedWriteQueue.Submission<String> second =
          queue.submit(
              resolvedSecondRepository,
              1,
              () -> {
                bothStarted.countDown();
                release.await();
                return resolvedSecondRepository;
              });

      assertTrue(bothStarted.await(5, TimeUnit.SECONDS));
      release.countDown();
      assertEquals(firstRepository, first.completion().get(5, TimeUnit.SECONDS));
      assertEquals(secondRepository, second.completion().get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void commandAndByteBoundsRejectInsteadOfGrowingWithoutLimit() throws Exception {
    DurableStripedWriteQueue.Limits limits =
        new DurableStripedWriteQueue.Limits(
            1, 1, 10, 1, 10, Duration.ZERO, Duration.ofMillis(25));
    CountDownLatch executing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    try (DurableStripedWriteQueue queue = new DurableStripedWriteQueue(limits)) {
      DurableStripedWriteQueue.Submission<Integer> first =
          queue.submit(
              "repo",
              10,
              () -> {
                executing.countDown();
                release.await();
                return 1;
              });
      assertTrue(executing.await(5, TimeUnit.SECONDS));
      DurableStripedWriteQueue.Submission<Integer> second =
          queue.submit("repo", 10, () -> 2);

      assertThrows(
          RejectedExecutionException.class, () -> queue.submit("repo", 1, () -> 3));
      assertEquals(1, queue.metrics().rejected());
      assertTrue(queue.metrics().currentQueueDepth() <= 1);
      assertTrue(queue.metrics().currentQueuedBytes() <= 10);

      release.countDown();
      assertEquals(1, first.completion().get(5, TimeUnit.SECONDS));
      assertEquals(2, second.completion().get(5, TimeUnit.SECONDS));
    }
  }

  @Test
  void shutdownNowFailsQueuedCommandsButDoesNotInterruptTheExecutingTransaction()
      throws Exception {
    DurableStripedWriteQueue.Limits limits =
        new DurableStripedWriteQueue.Limits(
            1, 4, 1024, 1, 1024, Duration.ZERO, Duration.ofSeconds(1));
    CountDownLatch executing = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);

    try (DurableStripedWriteQueue queue = new DurableStripedWriteQueue(limits)) {
      DurableStripedWriteQueue.Submission<Integer> first =
          queue.submit(
              "repo",
              1,
              () -> {
                executing.countDown();
                release.await();
                return 1;
              });
      assertTrue(executing.await(5, TimeUnit.SECONDS));
      DurableStripedWriteQueue.Submission<Integer> queued =
          queue.submit("repo", 1, () -> 2);

      assertEquals(1, queue.shutdownNow());
      release.countDown();
      assertEquals(1, first.completion().get(5, TimeUnit.SECONDS));
      assertThrows(CompletionException.class, queued.completion()::join);
      awaitCompleted(queue, 1);
      assertEquals(1, queue.metrics().rejected());
    }
  }

  private static void awaitCompleted(DurableStripedWriteQueue queue, long expected)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (queue.metrics().completed() != expected && System.nanoTime() < deadline) {
      Thread.sleep(1);
    }
    assertEquals(expected, queue.metrics().completed());
  }
}
