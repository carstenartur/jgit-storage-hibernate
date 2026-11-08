/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Owns exactly two fixture workers; close must finish them before storage closes. */
final class SearchRuntimeDiagnosticWorkers implements AutoCloseable {
  private final ExecutorService executor =
      new ThreadPoolExecutor(
          2,
          2,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(2),
          Thread.ofPlatform().name("search-diagnostic-", 0).factory());
  private final SearchRuntimeOutputGate gate;
  private final Duration timeout;
  private final AtomicInteger active = new AtomicInteger();
  private final AtomicReference<Throwable> failure = new AtomicReference<>();
  private volatile boolean closing;

  SearchRuntimeDiagnosticWorkers(SearchRuntimeOutputGate gate, Duration timeout) {
    this.gate = gate;
    this.timeout = timeout;
  }

  int active() {
    return active.get();
  }

  <T> Future<T> submit(Callable<T> task) {
    return executor.submit(
        () -> {
          active.incrementAndGet();
          try {
            return task.call();
          } catch (Throwable error) {
            if (!closing) failure.compareAndSet(null, error);
            throw error;
          } finally {
            active.decrementAndGet();
          }
        });
  }

  void assertHealthy() {
    if (failure.get() != null)
      throw new IllegalStateException("Diagnostic worker failed", failure.get());
  }

  void awaitGate() throws InterruptedException {
    awaitGate(() -> {});
  }

  void awaitGate(Runnable check) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!gate.await(25L)) {
      assertHealthy();
      check.run();
      if (System.nanoTime() >= deadline)
        throw new IllegalStateException("Output gate was not reached before the timeout");
    }
    assertHealthy();
    check.run();
  }

  @Override
  public void close() throws InterruptedException {
    closing = true;
    gate.release();
    executor.shutdownNow();
    long deadline = System.nanoTime() + timeout.toNanos();
    boolean interrupted = Thread.interrupted();
    try {
      while (!executor.isTerminated()) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0L) throw new IllegalStateException("Diagnostic workers did not stop");
        try {
          executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException expected) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
    if (interrupted) throw new InterruptedException("Interrupted while joining diagnostic workers");
  }
}
