/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConsumerAudioConcurrencyTest {
  @Test
  void publishesAndVerifiesFourRepositoriesWithIndependentWorkerBatchCounters() throws Exception {
    var fixture = new ConsumerShapeBenchmark.Audio();
    fixture.coordinate = 262144;
    fixture.writer = "stateless";
    fixture.pressure = "bounded";
    fixture.chunkBatch = 8;
    fixture.repositories = 4;
    fixture.analysisMiB = 8;
    fixture.analysisWorkers = 2;
    try {
      fixture.setup();
      fixture.clear();
      var published = new ConsumerShapeBenchmark.Counters();
      new ConsumerShapeBenchmark().audioPublish(fixture, published);
      assertEquals(4 * 262144, published.payloadBytes);
      assertEquals(4, published.repositoriesProcessed);
      assertEquals(4, fixture.distinctWorkerThreads());
      assertEquals(4, published.jdbcBatches);
      assertTrue(published.databaseBytesWritten > published.payloadBytes);
      assertEquals(8L * 1024 * 1024, published.retainedAnalysisBytes);

      fixture.clear();
      var read = new ConsumerShapeBenchmark.Counters();
      new ConsumerShapeBenchmark().audioReadRecent(fixture, read);
      assertEquals(4 * 262144, read.payloadBytes);
      assertEquals(4, read.repositoriesProcessed);
      assertEquals(0, read.jdbcBatches);
    } finally {
      fixture.close();
    }
    assertEquals(0, fixture.activeAnalysisWorkers());
  }

  @Test
  void rejectsAnUnboundedRepositoryCountBeforeStartingWorkers() {
    var fixture = new ConsumerShapeBenchmark.Audio();
    fixture.repositories = 0;
    assertThrows(IllegalArgumentException.class, fixture::setup);
    assertEquals(0, fixture.activeAnalysisWorkers());
  }

  @Test
  void waitsForEveryAnalysisWorkerBeforeDeclaringPressureReady() throws Exception {
    var secondStarted = new CountDownLatch(1);
    var releaseSecond = new CountDownLatch(1);
    var number = new AtomicInteger();
    try (var owner = Executors.newSingleThreadExecutor()) {
      var pending = owner.submit(() -> new ConsumerShapeBenchmark.AudioPressure(8, 2, task -> {
        int worker = number.getAndIncrement();
        return Thread.ofPlatform().daemon().unstarted(() -> {
          if (worker == 1) {
            secondStarted.countDown();
            awaitUninterruptibly(releaseSecond);
          }
          task.run();
        });
      }));
      try {
        assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> pending.get(200, TimeUnit.MILLISECONDS));
      } finally {
        releaseSecond.countDown();
        try (var pressure = pending.get(5, TimeUnit.SECONDS)) {
          assertEquals(2, pressure.activeWorkers());
        }
      }
    }
  }

  @Test
  void joinsStartedPressureWorkersBeforePropagatingSetupFailure() throws Exception {
    var cleanupStarted = new CountDownLatch(1);
    var allowExit = new CountDownLatch(1);
    var number = new AtomicInteger();
    try (var owner = Executors.newSingleThreadExecutor()) {
      var pending = owner.submit(() -> new ConsumerShapeBenchmark.AudioPressure(8, 2, task -> {
        if (number.getAndIncrement() == 1) {
          throw new IllegalStateException("Injected second worker creation failure");
        }
        return Thread.ofPlatform().daemon().unstarted(() -> {
          try {
            task.run();
          } finally {
            cleanupStarted.countDown();
            awaitUninterruptibly(allowExit);
          }
        });
      }));
      try {
        assertTrue(cleanupStarted.await(5, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> pending.get(200, TimeUnit.MILLISECONDS));
      } finally {
        allowExit.countDown();
        assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
      }
    }
  }

  @Test
  void interruptedOwnerJoinsWorkersBeforeClosingTheProviderAndRestoresInterrupt() throws Exception {
    var fixture = new ConsumerShapeBenchmark.Audio();
    fixture.coordinate = 262144;
    fixture.repositories = 2;
    fixture.setup();
    var factory = fixture.sessionFactory();
    var entered = new CountDownLatch(1);
    var stopping = new CountDownLatch(1);
    var allowExit = new CountDownLatch(1);
    var providerOpenAtWorkerExit = new AtomicBoolean();
    fixture.executor().submit(() -> {
      entered.countDown();
      try {
        new CountDownLatch(1).await();
      } catch (InterruptedException expected) {
        stopping.countDown();
        awaitUninterruptibly(allowExit);
        providerOpenAtWorkerExit.set(!factory.isClosed());
      }
    });
    assertTrue(entered.await(5, TimeUnit.SECONDS));
    try (var owner = Executors.newSingleThreadExecutor()) {
      var closed = owner.submit(() -> {
        Thread.currentThread().interrupt();
        fixture.close();
        return Thread.currentThread().isInterrupted();
      });
      try {
        assertTrue(stopping.await(5, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> closed.get(200, TimeUnit.MILLISECONDS));
        assertFalse(factory.isClosed());
      } finally {
        allowExit.countDown();
        assertTrue(closed.get(5, TimeUnit.SECONDS));
      }
    }
    assertTrue(providerOpenAtWorkerExit.get());
    assertTrue(factory.isClosed());
  }

  private static void awaitUninterruptibly(CountDownLatch latch) {
    boolean interrupted = false;
    while (true) {
      try {
        latch.await();
        break;
      } catch (InterruptedException failure) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
