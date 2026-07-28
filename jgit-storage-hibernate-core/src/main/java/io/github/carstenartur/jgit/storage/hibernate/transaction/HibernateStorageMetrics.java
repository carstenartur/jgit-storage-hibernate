/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.transaction;

import java.util.concurrent.atomic.LongAdder;

/**
 * Cumulative repository-specific storage counters that complement Hibernate's SessionFactory
 * statistics.
 *
 * <p>Hibernate already exposes prepared-statement, query, connection, flush and entity-operation
 * counts. These counters cover information Hibernate cannot attribute to one logical repository:
 * transaction outcomes, repository-row-lock wait time and binary payload traffic through pack
 * storage. The counters are thread-safe and may be sampled while the repository is active.
 */
public final class HibernateStorageMetrics {

  private final LongAdder transactionsStarted = new LongAdder();
  private final LongAdder transactionsCommitted = new LongAdder();
  private final LongAdder transactionsRolledBack = new LongAdder();
  private final LongAdder repositoryLockAcquisitions = new LongAdder();
  private final LongAdder repositoryLockWaitNanos = new LongAdder();
  private final LongAdder payloadBytesRead = new LongAdder();
  private final LongAdder payloadBytesWritten = new LongAdder();
  private final LongAdder chunkRowsInserted = new LongAdder();
  private final LongAdder chunkRowsDeleted = new LongAdder();

  /** Return a non-destructive snapshot of all counters. */
  public HibernateStorageMetricsSnapshot snapshot() {
    return new HibernateStorageMetricsSnapshot(
        transactionsStarted.sum(),
        transactionsCommitted.sum(),
        transactionsRolledBack.sum(),
        repositoryLockAcquisitions.sum(),
        repositoryLockWaitNanos.sum(),
        payloadBytesRead.sum(),
        payloadBytesWritten.sum(),
        chunkRowsInserted.sum(),
        chunkRowsDeleted.sum());
  }

  /** Reset all counters. Operations running concurrently may be split across the reset boundary. */
  public void reset() {
    transactionsStarted.reset();
    transactionsCommitted.reset();
    transactionsRolledBack.reset();
    repositoryLockAcquisitions.reset();
    repositoryLockWaitNanos.reset();
    payloadBytesRead.reset();
    payloadBytesWritten.reset();
    chunkRowsInserted.reset();
    chunkRowsDeleted.reset();
  }

  void recordTransactionStarted() {
    transactionsStarted.increment();
  }

  void recordTransactionCommitted() {
    transactionsCommitted.increment();
  }

  void recordTransactionRolledBack() {
    transactionsRolledBack.increment();
  }

  void recordRepositoryLock(long waitNanos) {
    repositoryLockAcquisitions.increment();
    repositoryLockWaitNanos.add(Math.max(0L, waitNanos));
  }

  void recordPayloadBytesRead(long bytes) {
    if (bytes > 0L) {
      payloadBytesRead.add(bytes);
    }
  }

  void recordPayloadBytesWritten(long bytes) {
    if (bytes > 0L) {
      payloadBytesWritten.add(bytes);
    }
  }

  void recordChunkRowsInserted(long rows) {
    if (rows > 0L) {
      chunkRowsInserted.add(rows);
    }
  }

  void recordChunkRowsDeleted(long rows) {
    if (rows > 0L) {
      chunkRowsDeleted.add(rows);
    }
  }
}
