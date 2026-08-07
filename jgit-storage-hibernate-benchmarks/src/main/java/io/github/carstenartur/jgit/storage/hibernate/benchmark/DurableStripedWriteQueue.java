/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded experimental write queue that consistently maps a repository to one FIFO writer stripe.
 *
 * <p>The queue does not acknowledge an operation when it is enqueued. A submission completes only
 * after its command returns, so a command that wraps a database transaction preserves the normal
 * durable acknowledgement boundary. A process crash may lose unacknowledged in-memory commands but
 * cannot turn an enqueue into a reported durable success.
 *
 * <p>Commands drained together form a scheduling micro-batch. They are executed sequentially in
 * repository order; the queue deliberately does not merge their database transactions. That makes
 * this class suitable for measuring serialization, connection and lock effects without weakening
 * JGit compare-and-set or logical-pack atomicity. A production transaction-combining implementation
 * would need a storage-specific batch command and separate semantic validation.
 *
 * <p>{@link #close()} stops admission and drains accepted commands. {@link #shutdownNow()} rejects
 * queued commands but never interrupts a command that may already own a database transaction.
 */
public final class DurableStripedWriteQueue implements AutoCloseable {

  /** Queue and micro-batch limits. */
  public record Limits(
      int stripes,
      int maxQueuedCommandsPerStripe,
      long maxQueuedBytesPerStripe,
      int maxBatchCommands,
      long maxBatchBytes,
      Duration maxBatchWait,
      Duration enqueueTimeout) {

    /** Validate all hard bounds. */
    public Limits {
      if (stripes <= 0) {
        throw new IllegalArgumentException("stripes must be positive");
      }
      if (maxQueuedCommandsPerStripe <= 0) {
        throw new IllegalArgumentException("maxQueuedCommandsPerStripe must be positive");
      }
      if (maxQueuedBytesPerStripe <= 0) {
        throw new IllegalArgumentException("maxQueuedBytesPerStripe must be positive");
      }
      if (maxBatchCommands <= 0) {
        throw new IllegalArgumentException("maxBatchCommands must be positive");
      }
      if (maxBatchBytes <= 0) {
        throw new IllegalArgumentException("maxBatchBytes must be positive");
      }
      Objects.requireNonNull(maxBatchWait, "maxBatchWait");
      Objects.requireNonNull(enqueueTimeout, "enqueueTimeout");
      if (maxBatchWait.isNegative()) {
        throw new IllegalArgumentException("maxBatchWait must not be negative");
      }
      if (enqueueTimeout.isNegative()) {
        throw new IllegalArgumentException("enqueueTimeout must not be negative");
      }
    }

    /** Conservative benchmark defaults for the requested stripe count. */
    public static Limits benchmarkDefaults(int stripes) {
      return new Limits(
          stripes,
          64,
          64L * 1024 * 1024,
          8,
          8L * 1024 * 1024,
          Duration.ofMillis(1),
          Duration.ofSeconds(10));
    }
  }

  /** Command whose successful return marks the durable completion boundary. */
  @FunctionalInterface
  public interface DurableCommand<T> {
    T execute() throws Exception;
  }

  /** One accepted command and its per-command scheduling measurements. */
  public static final class Submission<T> {
    private final CompletableFuture<T> completion = new CompletableFuture<>();
    private volatile long queueWaitNanos;
    private volatile int batchSize;

    private Submission() {}

    /** @return completion that resolves only after command execution finishes */
    public CompletableFuture<T> completion() {
      return completion;
    }

    /** @return time from accepted enqueue to start of command execution */
    public long queueWaitNanos() {
      return queueWaitNanos;
    }

    /** @return number of commands drained in the same scheduling micro-batch */
    public int batchSize() {
      return batchSize;
    }
  }

  /** Monotone aggregate queue telemetry plus current and maximum occupancy. */
  public record Metrics(
      long submitted,
      long completed,
      long failed,
      long cancelled,
      long rejected,
      long batches,
      long batchedCommands,
      long totalQueueWaitNanos,
      long maximumQueueWaitNanos,
      long currentQueueDepth,
      long currentQueuedBytes,
      long maximumQueueDepth,
      long maximumQueuedBytes,
      long maximumBatchSize) {}

  private final Limits limits;
  private final Stripe[] stripes;
  private final AtomicBoolean accepting = new AtomicBoolean(true);
  private final LongAdder submitted = new LongAdder();
  private final LongAdder completed = new LongAdder();
  private final LongAdder failed = new LongAdder();
  private final LongAdder cancelled = new LongAdder();
  private final LongAdder rejected = new LongAdder();
  private final LongAdder batches = new LongAdder();
  private final LongAdder batchedCommands = new LongAdder();
  private final LongAdder totalQueueWaitNanos = new LongAdder();
  private final AtomicLong maximumQueueWaitNanos = new AtomicLong();
  private final AtomicLong maximumQueueDepth = new AtomicLong();
  private final AtomicLong maximumQueuedBytes = new AtomicLong();
  private final AtomicLong maximumBatchSize = new AtomicLong();

  public DurableStripedWriteQueue(Limits limits) {
    this.limits = Objects.requireNonNull(limits, "limits");
    stripes = new Stripe[limits.stripes()];
    for (int index = 0; index < stripes.length; index++) {
      stripes[index] = new Stripe(index);
    }
    for (Stripe stripe : stripes) {
      stripe.worker.start();
    }
  }

  /**
   * Submit one durable command, waiting only for bounded queue capacity.
   *
   * @param repositoryName logical repository used for stable stripe routing
   * @param payloadBytes bytes retained or referenced by the queued command
   * @param command command that returns after its owning transaction commits
   * @return accepted submission
   * @throws InterruptedException when admission is interrupted
   * @throws RejectedExecutionException when closed, oversized or still full at the timeout
   */
  public <T> Submission<T> submit(
      String repositoryName, long payloadBytes, DurableCommand<T> command)
      throws InterruptedException {
    Objects.requireNonNull(repositoryName, "repositoryName");
    Objects.requireNonNull(command, "command");
    if (payloadBytes < 0) {
      throw new IllegalArgumentException("payloadBytes must not be negative");
    }
    if (payloadBytes > limits.maxQueuedBytesPerStripe()) {
      rejected.increment();
      throw new RejectedExecutionException(
          "Command payload exceeds one stripe's byte bound: " + payloadBytes);
    }
    if (!accepting.get()) {
      rejected.increment();
      throw new RejectedExecutionException("Write queue is closed");
    }
    return stripes[stripeIndex(repositoryName)].enqueue(payloadBytes, command);
  }

  /** @return current monotone telemetry snapshot */
  public Metrics metrics() {
    long depth = 0;
    long bytes = 0;
    for (Stripe stripe : stripes) {
      Occupancy occupancy = stripe.occupancy();
      depth += occupancy.commands();
      bytes += occupancy.bytes();
    }
    return new Metrics(
        submitted.sum(),
        completed.sum(),
        failed.sum(),
        cancelled.sum(),
        rejected.sum(),
        batches.sum(),
        batchedCommands.sum(),
        totalQueueWaitNanos.sum(),
        maximumQueueWaitNanos.get(),
        depth,
        bytes,
        maximumQueueDepth.get(),
        maximumQueuedBytes.get(),
        maximumBatchSize.get());
  }

  /**
   * Stop admission and fail queued commands without interrupting a command already executing.
   *
   * @return number of queued commands failed before execution
   */
  public long shutdownNow() {
    accepting.set(false);
    long aborted = 0;
    for (Stripe stripe : stripes) {
      aborted += stripe.abortPending();
    }
    return aborted;
  }

  /** Stop admission, drain every accepted command and join all writer stripes. */
  @Override
  public void close() {
    accepting.set(false);
    for (Stripe stripe : stripes) {
      stripe.signalShutdown();
    }
    boolean interrupted = false;
    for (Stripe stripe : stripes) {
      while (true) {
        try {
          stripe.worker.join();
          break;
        } catch (InterruptedException exception) {
          interrupted = true;
        }
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  int stripeIndex(String repositoryName) {
    int hash = repositoryName.hashCode();
    hash ^= hash >>> 16;
    return Math.floorMod(hash, stripes.length);
  }

  private void recordOccupancy(long queueDepth, long queuedBytes) {
    updateMaximum(maximumQueueDepth, queueDepth);
    updateMaximum(maximumQueuedBytes, queuedBytes);
  }

  private static void updateMaximum(AtomicLong maximum, long candidate) {
    long observed = maximum.get();
    while (candidate > observed && !maximum.compareAndSet(observed, candidate)) {
      observed = maximum.get();
    }
  }

  private final class Stripe {
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private final ArrayDeque<QueuedCommand<?>> queue = new ArrayDeque<>();
    private final Thread worker;
    private long queuedBytes;

    private Stripe(int index) {
      worker = new Thread(this::run, "jgit-durable-write-stripe-" + index);
      worker.setDaemon(true);
    }

    private <T> Submission<T> enqueue(long payloadBytes, DurableCommand<T> command)
        throws InterruptedException {
      Submission<T> submission = new Submission<>();
      long remaining = limits.enqueueTimeout().toNanos();
      lock.lockInterruptibly();
      try {
        while (accepting.get() && !hasCapacity(payloadBytes)) {
          if (remaining <= 0) {
            rejected.increment();
            throw new RejectedExecutionException("Timed out waiting for bounded queue capacity");
          }
          remaining = notFull.awaitNanos(remaining);
        }
        if (!accepting.get()) {
          rejected.increment();
          throw new RejectedExecutionException("Write queue is closed");
        }
        QueuedCommand<T> queued =
            new QueuedCommand<>(payloadBytes, System.nanoTime(), command, submission);
        queue.addLast(queued);
        queuedBytes = Math.addExact(queuedBytes, payloadBytes);
        submitted.increment();
        recordOccupancy(queue.size(), queuedBytes);
        notEmpty.signal();
        return submission;
      } finally {
        lock.unlock();
      }
    }

    private boolean hasCapacity(long payloadBytes) {
      return queue.size() < limits.maxQueuedCommandsPerStripe()
          && queuedBytes <= limits.maxQueuedBytesPerStripe() - payloadBytes;
    }

    private void run() {
      try {
        while (true) {
          List<QueuedCommand<?>> batch = takeBatch();
          if (batch == null) {
            return;
          }
          execute(batch);
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        abortPending();
      }
    }

    private List<QueuedCommand<?>> takeBatch() throws InterruptedException {
      lock.lockInterruptibly();
      try {
        while (queue.isEmpty() && accepting.get()) {
          notEmpty.await();
        }
        if (queue.isEmpty()) {
          return null;
        }

        QueuedCommand<?> first = queue.peekFirst();
        long deadline = first.enqueuedAtNanos + limits.maxBatchWait().toNanos();
        ArrayList<QueuedCommand<?>> batch = new ArrayList<>(limits.maxBatchCommands());
        long batchBytes = 0;
        while (batch.size() < limits.maxBatchCommands()) {
          QueuedCommand<?> next = queue.peekFirst();
          if (next == null) {
            if (!accepting.get()) {
              break;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
              break;
            }
            notEmpty.awaitNanos(remaining);
            continue;
          }
          if (!batch.isEmpty() && batchBytes > limits.maxBatchBytes() - next.payloadBytes) {
            break;
          }
          queue.removeFirst();
          queuedBytes -= next.payloadBytes;
          batch.add(next);
          batchBytes = Math.addExact(batchBytes, next.payloadBytes);
        }
        notFull.signalAll();
        batches.increment();
        batchedCommands.add(batch.size());
        updateMaximum(maximumBatchSize, batch.size());
        return List.copyOf(batch);
      } finally {
        lock.unlock();
      }
    }

    private void execute(List<QueuedCommand<?>> batch) {
      int batchSize = batch.size();
      for (QueuedCommand<?> queued : batch) {
        executeOne(queued, batchSize);
      }
    }

    private <T> void executeOne(QueuedCommand<T> queued, int batchSize) {
      Submission<T> submission = queued.submission;
      if (submission.completion.isCancelled()) {
        cancelled.increment();
        return;
      }
      long wait = Math.max(0, System.nanoTime() - queued.enqueuedAtNanos);
      submission.queueWaitNanos = wait;
      submission.batchSize = batchSize;
      totalQueueWaitNanos.add(wait);
      updateMaximum(maximumQueueWaitNanos, wait);
      try {
        T result = queued.command.execute();
        submission.completion.complete(result);
        completed.increment();
      } catch (Throwable failure) {
        submission.completion.completeExceptionally(failure);
        failed.increment();
      }
    }

    private long abortPending() {
      lock.lock();
      try {
        long count = 0;
        RejectedExecutionException failure =
            new RejectedExecutionException("Write queue was shut down before execution");
        QueuedCommand<?> command;
        while ((command = queue.pollFirst()) != null) {
          queuedBytes -= command.payloadBytes;
          command.submission.completion.completeExceptionally(failure);
          rejected.increment();
          count++;
        }
        notFull.signalAll();
        notEmpty.signalAll();
        return count;
      } finally {
        lock.unlock();
      }
    }

    private void signalShutdown() {
      lock.lock();
      try {
        notEmpty.signalAll();
        notFull.signalAll();
      } finally {
        lock.unlock();
      }
    }

    private Occupancy occupancy() {
      lock.lock();
      try {
        return new Occupancy(queue.size(), queuedBytes);
      } finally {
        lock.unlock();
      }
    }
  }

  private record QueuedCommand<T>(
      long payloadBytes,
      long enqueuedAtNanos,
      DurableCommand<T> command,
      Submission<T> submission) {}

  private record Occupancy(long commands, long bytes) {}
}
