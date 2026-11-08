/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Benchmark-only worker lifecycle. */
final class ConsumerPublicationWorkers implements AutoCloseable {
  private final int parallelism;
  private final ThreadPoolExecutor executor;

  ConsumerPublicationWorkers(int parallelism) {
    if (parallelism < 1) throw new IllegalArgumentException("Expected positive worker count");
    this.parallelism = parallelism;
    executor = new ThreadPoolExecutor(parallelism, parallelism, 0, TimeUnit.SECONDS,
        new LinkedBlockingQueue<>(),
        Thread.ofPlatform().name("consumer-publication-", 0).factory());
    executor.prestartAllCoreThreads();
  }

  <T> List<T> execute(List<? extends Callable<T>> tasks) throws IOException {
    if (tasks.isEmpty()) return List.of();
    var ready = new CountDownLatch(Math.min(parallelism, tasks.size()));
    var start = new CountDownLatch(1);
    var completion = new ExecutorCompletionService<Indexed<T>>(executor);
    List<Future<Indexed<T>>> submitted = new ArrayList<>();
    try {
      for (int index = 0; index < tasks.size(); index++) {
        int position = index;
        Callable<T> task = tasks.get(index);
        submitted.add(completion.submit(() -> {
          // Each active worker waits once; queued tasks cannot satisfy this readiness barrier.
          ready.countDown();
          start.await();
          return new Indexed<>(position, task.call());
        }));
      }
      if (!ready.await(30, TimeUnit.SECONDS)) {
        throw new TimeoutException("Publication workers did not become ready");
      }
      start.countDown();
      List<T> results = new ArrayList<>(Collections.nCopies(tasks.size(), null));
      for (int count = 0; count < tasks.size(); count++) {
        var completed = completion.poll(10, TimeUnit.MINUTES);
        if (completed == null) throw new TimeoutException("Workspace publication did not finish");
        var result = completed.get();
        results.set(result.index(), result.value());
      }
      return List.copyOf(results);
    } catch (InterruptedException | ExecutionException | TimeoutException | RuntimeException failure) {
      IOException reported;
      if (failure instanceof InterruptedException) {
        Thread.currentThread().interrupt();
        reported = new InterruptedIOException("Interrupted during workspace publication");
      } else {
        reported = new IOException("Workspace publication failed");
      }
      reported.initCause(failure instanceof ExecutionException ? failure.getCause() : failure);
      submitted.forEach(future -> future.cancel(true));
      try {
        close();
      } catch (IOException cleanup) {
        reported.addSuppressed(cleanup);
      }
      throw reported;
    } finally {
      start.countDown();
    }
  }

  /** The caller may close repositories only after this bounded join succeeds. */
  @Override
  public void close() throws IOException {
    boolean interrupted = Thread.interrupted();
    executor.shutdownNow();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    try {
      while (!executor.isTerminated()) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IOException("Publication workers did not stop; resources remain open");
        try {
          executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException failure) {
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }

  private record Indexed<T>(int index, T value) {}
}
