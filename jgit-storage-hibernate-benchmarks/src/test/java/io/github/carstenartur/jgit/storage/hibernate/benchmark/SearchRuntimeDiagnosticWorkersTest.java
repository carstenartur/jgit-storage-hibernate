/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SearchRuntimeDiagnosticWorkersTest {
  @Test
  void closeReleasesOutputGateBeforeWaitingForWorkers() throws Exception {
    var gate = new SearchRuntimeOutputGate(Duration.ofSeconds(3));
    var workers = new SearchRuntimeDiagnosticWorkers(gate, Duration.ofSeconds(3));
    gate.arm();
    workers.submit(
        () -> {
          gate.beforeOutput();
          return null;
        });
    workers.awaitGate();
    workers.close();
    assertTrue(gate.released(), "The provider may close only after the output gate is released");
    assertEquals(0, workers.active());
  }

  @Test
  void producerFailureBeforeGateSurfacesOriginalCause() throws Exception {
    var original = new IOException("producer failed before output");
    var gate = new SearchRuntimeOutputGate(Duration.ofSeconds(1));
    try (var workers = new SearchRuntimeDiagnosticWorkers(gate, Duration.ofSeconds(2))) {
      gate.arm();
      workers.submit(
          () -> {
            throw original;
          });
      var failure = assertThrows(IllegalStateException.class, workers::awaitGate);
      assertSame(original, failure.getCause(), "Do not replace worker failure with a gate timeout");
    }
  }

  @Test
  void interruptedOwnerStillJoinsBeforeReturningToProviderClose() throws Exception {
    var gate = new SearchRuntimeOutputGate(Duration.ofSeconds(3));
    var workers = new SearchRuntimeDiagnosticWorkers(gate, Duration.ofSeconds(3));
    var workerStarted = new CountDownLatch(1);
    var allowWorkerExit = new CountDownLatch(1);
    var closeStarted = new CountDownLatch(1);
    var closeReturned = new CountDownLatch(1);
    var restoredInterrupt = new AtomicBoolean();
    var unexpected = new AtomicReference<Throwable>();
    workers.submit(
        () -> {
          workerStarted.countDown();
          boolean interrupted = false;
          while (true) {
            try {
              allowWorkerExit.await();
              break;
            } catch (InterruptedException expected) {
              interrupted = true;
            }
          }
          if (interrupted) Thread.currentThread().interrupt();
          return null;
        });
    assertTrue(workerStarted.await(2, TimeUnit.SECONDS));
    Thread owner =
        new Thread(
            () -> {
              Thread.currentThread().interrupt();
              closeStarted.countDown();
              try {
                assertThrows(InterruptedException.class, workers::close);
                restoredInterrupt.set(Thread.currentThread().isInterrupted());
                assertEquals(0, workers.active());
              } catch (Throwable failure) {
                unexpected.set(failure);
              } finally {
                closeReturned.countDown();
              }
            });
    owner.start();
    try {
      assertTrue(closeStarted.await(2, TimeUnit.SECONDS));
      assertFalse(
          closeReturned.await(100, TimeUnit.MILLISECONDS),
          "Interrupted close returned while its worker still used the fixture");
    } finally {
      allowWorkerExit.countDown();
      owner.join(3000);
      workers.close();
    }
    assertFalse(owner.isAlive());
    if (unexpected.get() != null) throw new AssertionError(unexpected.get());
    assertTrue(restoredInterrupt.get());
  }
}
