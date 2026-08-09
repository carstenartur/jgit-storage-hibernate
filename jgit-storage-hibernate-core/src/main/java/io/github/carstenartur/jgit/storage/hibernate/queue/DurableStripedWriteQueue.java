/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.queue;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded durable write queue with repository-stable writer stripes and real atomic micro-batches.
 *
 * <p>Commands for one repository are collected up to the configured command or byte limit. When
 * the limit is not reached, the oldest command's configurable wait deadline releases every command
 * currently queued for that repository. The batch processor is called once for the complete group
 * and must return only after its owning database transaction commits. Futures are completed only
 * after that return, so enqueueing alone can never be reported as durable success.
 *
 * <p>One batch never mixes repositories. Independent repositories may execute concurrently on
 * different stripes, while FIFO order remains deterministic within each repository. A process crash
 * may lose unacknowledged in-memory commands but cannot lose a command whose future completed
 * successfully before the crash.
 *
 * @param <C> command or record type retained by the queue
 * @param <R> durable result type returned per command
 */
public final class DurableStripedWriteQueue<C, R> implements AutoCloseable {

  /** Queue capacity, atomic batch and admission limits. */
  public record Limits(
      int stripes,
      int maxQueuedCommandsPerStripe,
      long maxQueuedBytesPerStripe,
      int maxBatchCommands,
      long maxBatchBytes,
      Duration maxBatchWait,
      Duration enqueueTimeout) {

    /** Validate every hard bound. */
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
      if (maxBatchCommands > maxQueuedCommandsPerStripe) {
        throw new IllegalArgumentException(
            "maxBatchCommands must not exceed maxQueuedCommandsPerStripe");
      }
      if (maxBatchBytes <= 0 || maxBatchBytes > maxQueuedBytesPerStripe) {
        throw new IllegalArgumentException(
            "maxBatchBytes must be positive and not exceed maxQueuedBytesPerStripe");
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

    /**
     * Bounded defaults for ordinary database records.
     *
     * <p>At most 50 records or 64 MiB are submitted per atomic transaction. A two-millisecond
     * collection window allows a receiver to combine bursts without imposing unbounded latency on a
     * sparse stream. Applications with large records should lower the byte or command bound.
     */
    public static Limits productionDefaults(int stripes) {
      return new Limits(
          stripes,
          1_000,
          256L * 1024 * 1024,
          50,
          64L * 1024 * 1024,
          Duration.ofMillis(2),
          Duration.ofSeconds(10));
    }
  }

  /**
   * Persists one repository-homogeneous batch atomically.
   *
   * <p>The processor must return one result per command in the original order and only after the
   * database transaction commits. Throwing indicates that the whole batch was not durably accepted;
   * every submission in that batch is then completed exceptionally.
   */
  @FunctionalInterface
  public interface DurableBatchProcessor<C, R> {
    List<R> execute(String repositoryName, List<C> commands) throws Exception;
  }

  /** One accepted command and its durable completion plus scheduling measurements. */
  public static final class Submission<R> {
    private final CompletableFuture<R> completion = new CompletableFuture<>();
    private volatile long queueWaitNanos;
    private volatile int batchSize;

    private Submission() {}

    /** @return completion that resolves only after the batch transaction commits */
    public CompletableFuture<R> completion() {
      return completion;
    }

    /** @return time from accepted enqueue to start of batch execution */
    public long queueWaitNanos() {
      return queueWaitNanos;
    }

    /** @return number of commands submitted in the same atomic batch */
    public int batchSize() {
      return batchSize;
    }
  }

  /**
   * Monotone queue telemetry plus current and maximum queued occupancy.
   *
   * <p>{@code completed} and {@code failed} describe durable processor outcomes. Once a command has
   * entered the processor, a later caller-side cancellation of its completion future does not undo
   * the database outcome and therefore does not decrement or replace those counters. {@code
   * cancelled} counts commands discarded before processor execution; {@code rejected} counts
   * commands rejected at admission or by shutdown before execution.
   */
  public record Metrics(
      long submitted,
      long completed,
      long failed,
      long cancelled,
      long rejected,
      long batches,
      long failedBatches,
      long batchedCommands,
      long totalQueueWaitNanos,
      long maximumQueueWaitNanos,
      long currentQueueDepth,
      long currentQueuedBytes,
      long maximumQueueDepthPerStripe,
      long maximumQueuedBytesPerStripe,
      long maximumBatchSize,
      long maximumBatchBytes) {}

  private final Limits limits;
  private final DurableBatchProcessor<C, R> processor;
  private final List<Stripe> stripes;
  private final AtomicBoolean accepting = new AtomicBoolean(true);
  private final LongAdder submitted = new LongAdder();
  private final LongAdder completed = new LongAdder();
  private final LongAdder failed = new LongAdder();
  private final LongAdder cancelled = new LongAdder();
  private final LongAdder rejected = new LongAdder();
  private final LongAdder batches = new LongAdder();
  private final LongAdder failedBatches = new LongAdder();
  private final LongAdder batchedCommands = new LongAdder();
  private final LongAdder totalQueueWaitNanos = new LongAdder();
  private final AtomicLong maximumQueueWaitNanos = new AtomicLong();
  private final AtomicLong maximumQueueDepth = new AtomicLong();
  private final AtomicLong maximumQueuedBytes = new AtomicLong();
  private final AtomicLong maximumBatchSize = new AtomicLong();
  private final AtomicLong maximumBatchBytes = new AtomicLong();

  /** Create and start all writer stripes. */
  public DurableStripedWriteQueue(
      Limits limits, DurableBatchProcessor<C, R> processor) {
    this.limits = Objects.requireNonNull(limits, "limits");
    this.processor = Objects.requireNonNull(processor, "processor");
    List<Stripe> created = new ArrayList<>(limits.stripes());
    for (int index = 0; index < limits.stripes(); index++) {
      created.add(new Stripe(index));
    }
    stripes = List.copyOf(created);
    for (Stripe stripe : stripes) {
      stripe.worker.start();
    }
  }

  /**
   * Submit one record, waiting only for bounded queue capacity.
   *
   * @param repositoryName repository and atomic-batch key
   * @param payloadBytes bytes retained or referenced by the command
   * @param command immutable command or record
   * @return accepted submission
   * @throws InterruptedException when admission is interrupted
   * @throws RejectedExecutionException when closed, oversized or still full at the timeout
   */
  public Submission<R> submit(String repositoryName, long payloadBytes, C command)
      throws InterruptedException {
    Objects.requireNonNull(repositoryName, "repositoryName");
    Objects.requireNonNull(command, "command");
    if (repositoryName.isBlank()) {
      throw new IllegalArgumentException("repositoryName must not be blank");
    }
    if (payloadBytes < 0) {
      throw new IllegalArgumentException("payloadBytes must not be negative");
    }
    if (payloadBytes > limits.maxBatchBytes()) {
      rejected.increment();
      throw new RejectedExecutionException(
          "Command payload exceeds one atomic batch's byte bound: " + payloadBytes);
    }
    if (!accepting.get()) {
      rejected.increment();
      throw new RejectedExecutionException("Write queue is closed");
    }
    return stripes.get(stripeIndex(repositoryName)).enqueue(
        repositoryName, payloadBytes, command);
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
        failedBatches.sum(),
        batchedCommands.sum(),
        totalQueueWaitNanos.sum(),
        maximumQueueWaitNanos.get(),
        depth,
        bytes,
        maximumQueueDepth.get(),
        maximumQueuedBytes.get(),
        maximumBatchSize.get(),
        maximumBatchBytes.get());
  }

  /**
   * Stop admission and fail queued commands without interrupting an executing database transaction.
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

  /** Stop admission, drain every accepted batch and join all writer stripes. */
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
    return Math.floorMod(hash, stripes.size());
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
    private final Map<String, ArrayDeque<QueuedCommand>> repositoryQueues =
        new LinkedHashMap<>();
    private final ArrayDeque<String> readyRepositories = new ArrayDeque<>();
    private final Thread worker;
    private int queuedCommands;
    private long queuedBytes;

    private Stripe(int index) {
      worker = new Thread(this::run, "jgit-durable-write-stripe-" + index);
      worker.setDaemon(true);
    }

    private Submission<R> enqueue(
        String repositoryName, long payloadBytes, C command) throws InterruptedException {
      Submission<R> submission = new Submission<>();
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

        ArrayDeque<QueuedCommand> repositoryQueue =
            repositoryQueues.computeIfAbsent(repositoryName, ignored -> new ArrayDeque<>());
        boolean wasEmpty = repositoryQueue.isEmpty();
        repositoryQueue.addLast(
            new QueuedCommand(payloadBytes, System.nanoTime(), command, submission));
        if (wasEmpty) {
          readyRepositories.addLast(repositoryName);
        }
        queuedCommands++;
        queuedBytes = Math.addExact(queuedBytes, payloadBytes);
        submitted.increment();
        recordOccupancy(queuedCommands, queuedBytes);
        notEmpty.signalAll();
        return submission;
      } finally {
        lock.unlock();
      }
    }

    private boolean hasCapacity(long payloadBytes) {
      return queuedCommands < limits.maxQueuedCommandsPerStripe()
          && queuedBytes <= limits.maxQueuedBytesPerStripe() - payloadBytes;
    }

    private void run() {
      try {
        while (true) {
          Batch batch = takeBatch();
          if (batch == null) {
            return;
          }
          if (!batch.commands.isEmpty()) {
            execute(batch);
          }
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        abortPending();
      }
    }

    private Batch takeBatch() throws InterruptedException {
      lock.lockInterruptibly();
      try {
        while (readyRepositories.isEmpty() && accepting.get()) {
          notEmpty.await();
        }
        if (readyRepositories.isEmpty()) {
          return null;
        }

        String repositoryName = readyRepositories.removeFirst();
        ArrayDeque<QueuedCommand> repositoryQueue = repositoryQueues.get(repositoryName);
        if (repositoryQueue == null || repositoryQueue.isEmpty()) {
          throw new IllegalStateException(
              "Ready repository has no queued commands: " + repositoryName);
        }

        long deadline =
            repositoryQueue.peekFirst().enqueuedAtNanos + limits.maxBatchWait().toNanos();
        while (accepting.get()
            && !batchLimitReached(repositoryQueue)
            && System.nanoTime() < deadline) {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) {
            break;
          }
          notEmpty.awaitNanos(remaining);
        }

        ArrayList<QueuedCommand> batch = new ArrayList<>(limits.maxBatchCommands());
        long batchBytes = 0;
        while (batch.size() < limits.maxBatchCommands()) {
          QueuedCommand next = repositoryQueue.peekFirst();
          if (next == null) {
            break;
          }
          if (!batch.isEmpty() && batchBytes > limits.maxBatchBytes() - next.payloadBytes) {
            break;
          }
          repositoryQueue.removeFirst();
          queuedCommands--;
          queuedBytes -= next.payloadBytes;
          if (next.submission.completion.isCancelled()) {
            cancelled.increment();
            continue;
          }
          batch.add(next);
          batchBytes = Math.addExact(batchBytes, next.payloadBytes);
        }

        if (repositoryQueue.isEmpty()) {
          repositoryQueues.remove(repositoryName);
        } else {
          readyRepositories.addLast(repositoryName);
        }
        notFull.signalAll();
        if (!batch.isEmpty()) {
          batches.increment();
          batchedCommands.add(batch.size());
          updateMaximum(maximumBatchSize, batch.size());
          updateMaximum(maximumBatchBytes, batchBytes);
        }
        return new Batch(repositoryName, List.copyOf(batch));
      } finally {
        lock.unlock();
      }
    }

    private boolean batchLimitReached(ArrayDeque<QueuedCommand> repositoryQueue) {
      int commands = 0;
      long bytes = 0;
      for (QueuedCommand queued : repositoryQueue) {
        if (queued.submission.completion.isCancelled()) {
          continue;
        }
        if (commands == limits.maxBatchCommands()) {
          return true;
        }
        if (commands > 0 && bytes > limits.maxBatchBytes() - queued.payloadBytes) {
          return true;
        }
        commands++;
        bytes = Math.addExact(bytes, queued.payloadBytes);
        if (commands == limits.maxBatchCommands() || bytes == limits.maxBatchBytes()) {
          return true;
        }
      }
      return false;
    }

    private void execute(Batch batch) {
      List<C> commands = new ArrayList<>(batch.commands.size());
      int batchSize = batch.commands.size();
      long startedAt = System.nanoTime();
      for (QueuedCommand queued : batch.commands) {
        long wait = Math.max(0, startedAt - queued.enqueuedAtNanos);
        queued.submission.queueWaitNanos = wait;
        queued.submission.batchSize = batchSize;
        totalQueueWaitNanos.add(wait);
        updateMaximum(maximumQueueWaitNanos, wait);
        commands.add(queued.command);
      }

      try {
        List<R> results =
            Objects.requireNonNull(
                processor.execute(batch.repositoryName, List.copyOf(commands)),
                "Batch processor returned null");
        if (results.size() != batchSize) {
          throw new IllegalStateException(
              "Batch processor returned "
                  + results.size()
                  + " results for "
                  + batchSize
                  + " commands");
        }
        for (int index = 0; index < batchSize; index++) {
          QueuedCommand queued = batch.commands.get(index);
          // Processor success is the durable outcome. Publish that monotone metric before making a
          // successful future observable; a caller-side cancellation cannot undo the committed work.
          completed.increment();
          queued.submission.completion.complete(results.get(index));
        }
      } catch (Throwable failure) {
        failedBatches.increment();
        for (QueuedCommand queued : batch.commands) {
          // Likewise, the processor failure is an outcome of the attempted durable batch even if a
          // caller raced to cancel its local completion future.
          failed.increment();
          queued.submission.completion.completeExceptionally(failure);
        }
      }
    }

    private long abortPending() {
      lock.lock();
      try {
        long count = 0;
        RejectedExecutionException failure =
            new RejectedExecutionException("Write queue was shut down before execution");
        for (ArrayDeque<QueuedCommand> repositoryQueue : repositoryQueues.values()) {
          QueuedCommand command;
          while ((command = repositoryQueue.pollFirst()) != null) {
            if (command.submission.completion.isCancelled()) {
              cancelled.increment();
            } else {
              rejected.increment();
              command.submission.completion.completeExceptionally(failure);
            }
            count++;
          }
        }
        repositoryQueues.clear();
        readyRepositories.clear();
        queuedCommands = 0;
        queuedBytes = 0;
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
        return new Occupancy(queuedCommands, queuedBytes);
      } finally {
        lock.unlock();
      }
    }
  }

  private final class QueuedCommand {
    private final long payloadBytes;
    private final long enqueuedAtNanos;
    private final C command;
    private final Submission<R> submission;

    private QueuedCommand(
        long payloadBytes, long enqueuedAtNanos, C command, Submission<R> submission) {
      this.payloadBytes = payloadBytes;
      this.enqueuedAtNanos = enqueuedAtNanos;
      this.command = command;
      this.submission = submission;
    }
  }

  private final class Batch {
    private final String repositoryName;
    private final List<QueuedCommand> commands;

    private Batch(String repositoryName, List<QueuedCommand> commands) {
      this.repositoryName = repositoryName;
      this.commands = commands;
    }
  }

  private record Occupancy(long commands, long bytes) {}
}
