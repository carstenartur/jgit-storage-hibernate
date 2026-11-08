/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class ConsumerPublicationWorkersTest {
  @Test
  void fourWorkersStartTogetherAndReturnResultsInWorkspaceOrder() throws Exception {
    try (var workers = new ConsumerPublicationWorkers(4)) {
      var entered = new CountDownLatch(4);
      Set<Long> threads = ConcurrentHashMap.newKeySet();
      List<Callable<Integer>> tasks = new ArrayList<>();
      for (int workspace = 0; workspace < 4; workspace++) {
        int value = workspace;
        tasks.add(() -> {
          threads.add(Thread.currentThread().threadId());
          entered.countDown();
          assertTrue(entered.await(5, TimeUnit.SECONDS), "all four workers must overlap");
          return value;
        });
      }
      assertEquals(List.of(0, 1, 2, 3), assertDoesNotThrow(() -> workers.execute(tasks)));
      assertEquals(4, threads.size());
    }
  }

  @Test
  void oneWorkerExecutesTheSameFourTasksWithoutAnAllTasksBarrierDeadlock() throws Exception {
    try (var workers = new ConsumerPublicationWorkers(1)) {
      List<Callable<Integer>> tasks = List.of(() -> 0, () -> 1, () -> 2, () -> 3);
      assertEquals(List.of(0, 1, 2, 3), assertDoesNotThrow(() -> workers.execute(tasks)));
      assertEquals(List.of(0, 1, 2, 3), workers.execute(tasks));
    }
  }

  @Test
  void interruptedOwnerWaitsForCancelledWorkersAndRestoresItsInterrupt() throws Exception {
    checkJoinedFailure(true);
  }

  @Test
  void workerFailureWaitsForOtherCancelledWorkersBeforeReturning() throws Exception {
    checkJoinedFailure(false);
  }

  private static void checkJoinedFailure(boolean interruptOwner) throws Exception {
    var workers = new ConsumerPublicationWorkers(4);
    var entered = new CountDownLatch(4);
    var cancellationObserved = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var finished = new CountDownLatch(4);
    var failure = new AtomicReference<Throwable>();
    var restoredInterrupt = new AtomicBoolean();
    List<Callable<Integer>> tasks = new ArrayList<>();
    for (int workspace = 0; workspace < 4; workspace++) {
      int value = workspace;
      tasks.add(() -> {
        try {
          entered.countDown();
          assertTrue(entered.await(5, TimeUnit.SECONDS));
          if (!interruptOwner && value == 0) {
            throw new IOException("publication failed");
          }
          // Model a JDBC operation that observes cancellation but finishes its cleanup later.
          boolean interrupted = false;
          while (release.getCount() != 0) {
            try {
              release.await();
            } catch (InterruptedException expected) {
              interrupted = true;
              cancellationObserved.countDown();
            }
          }
          if (interrupted) Thread.currentThread().interrupt();
          return value;
        } finally {
          finished.countDown();
        }
      });
    }
    Thread owner = Thread.ofPlatform().start(() -> {
      try {
        workers.execute(tasks);
      } catch (Throwable caught) {
        failure.set(caught);
      } finally {
        restoredInterrupt.set(Thread.currentThread().isInterrupted());
      }
    });
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS), "the real worker tasks must begin");
      if (interruptOwner) owner.interrupt();
      assertTrue(cancellationObserved.await(5, TimeUnit.SECONDS), "cancellation must reach workers");
      assertTrue(owner.isAlive(), "owner must join before repository resources can be closed");
      release.countDown();
      owner.join(5_000);
      assertFalse(owner.isAlive());
      assertEquals(0, finished.getCount());
      if (interruptOwner) assertInstanceOf(InterruptedIOException.class, failure.get());
      else assertInstanceOf(IOException.class, failure.get());
      assertEquals(interruptOwner, restoredInterrupt.get());
    } finally {
      release.countDown();
      owner.interrupt();
      owner.join(5_000);
      workers.close();
    }
  }
}
