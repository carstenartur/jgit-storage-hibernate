/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.transaction;

/** Immutable cumulative storage metrics for one {@code HibernateRepository} instance. */
public record HibernateStorageMetricsSnapshot(
    long transactionsStarted,
    long transactionsCommitted,
    long transactionsRolledBack,
    long repositoryLockAcquisitions,
    long repositoryLockWaitNanos,
    long payloadBytesRead,
    long payloadBytesWritten,
    long chunkRowsInserted,
    long chunkRowsDeleted) {

  /** Repository-lock wait time converted to milliseconds for reports and operational telemetry. */
  public double repositoryLockWaitMillis() {
    return repositoryLockWaitNanos / 1_000_000.0;
  }
}
