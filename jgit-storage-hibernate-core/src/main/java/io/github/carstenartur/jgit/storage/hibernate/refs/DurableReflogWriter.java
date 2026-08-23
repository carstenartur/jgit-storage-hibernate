/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.refs;

import io.github.carstenartur.jgit.storage.hibernate.queue.DurableStripedWriteQueue;
import java.util.Objects;
import org.hibernate.SessionFactory;

/**
 * Bounded queue facade for atomic, idempotent queryable reflog projection batches.
 *
 * <p>A successful completion means the owning repository-locked transaction committed. A failed
 * completion means no command in that attempted batch may be assumed durable; callers use {@link
 * HibernateReflogBatchProcessor#retryAdvice(Throwable)} and always preserve delivery IDs when
 * retrying.
 */
public final class DurableReflogWriter implements AutoCloseable {

  private final DurableStripedWriteQueue<ReflogAppendCommand, ReflogAppendResult> queue;

  /** Create a writer with application-selected bounded queue limits. */
  public DurableReflogWriter(
      SessionFactory sessionFactory, DurableStripedWriteQueue.Limits limits) {
    queue =
        new DurableStripedWriteQueue<>(
            Objects.requireNonNull(limits, "limits"),
            new HibernateReflogBatchProcessor(
                Objects.requireNonNull(sessionFactory, "sessionFactory")));
  }

  /**
   * Submit one immutable append command.
   *
   * @param repositoryName logical repository and atomic-batch key
   * @param command immutable command with a stable delivery ID
   * @return completion published only after commit
   * @throws InterruptedException when bounded admission is interrupted
   */
  public DurableStripedWriteQueue.Submission<ReflogAppendResult> append(
      String repositoryName, ReflogAppendCommand command) throws InterruptedException {
    Objects.requireNonNull(command, "command");
    return queue.submit(repositoryName, command.estimatedBytes(), command);
  }

  /** @return current queue scheduling and durable-outcome metrics */
  public DurableStripedWriteQueue.Metrics metrics() {
    return queue.metrics();
  }

  /** Reject queued commands without interrupting an executing transaction. */
  public long shutdownNow() {
    return queue.shutdownNow();
  }

  /** Stop admission, drain every accepted batch and join all writer stripes. */
  @Override
  public void close() {
    queue.close();
  }
}
