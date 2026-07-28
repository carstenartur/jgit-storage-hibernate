/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.transaction;

/**
 * Monotone diagnostic counters for one Hibernate-backed repository instance.
 *
 * <p>The counters deliberately cover only storage transaction boundaries and pessimistic repository
 * lock acquisition. Hibernate query, statement and connection counts remain available through
 * Hibernate's own {@code Statistics} API. Metrics are disabled by default and can be enabled with
 * {@value HibernateTransactionContext#METRICS_ENABLED_PROPERTY}.
 *
 * @param transactionsStarted top-level repository transactions started
 * @param transactionsCommitted top-level repository transactions committed
 * @param transactionsRolledBack top-level repository transactions rolled back
 * @param repositoryLocksAcquired pessimistic repository row locks acquired
 * @param repositoryLockAcquisitionNanos elapsed time spent acquiring repository row locks, including
 *     the database round trip
 */
public record StorageOperationMetrics(
    long transactionsStarted,
    long transactionsCommitted,
    long transactionsRolledBack,
    long repositoryLocksAcquired,
    long repositoryLockAcquisitionNanos) {

  /** Empty metrics snapshot. */
  public static final StorageOperationMetrics ZERO = new StorageOperationMetrics(0, 0, 0, 0, 0);

  /**
   * Calculate the non-negative difference from an earlier monotone snapshot.
   *
   * @param earlier earlier snapshot from the same repository instance
   * @return counter delta
   */
  public StorageOperationMetrics minus(StorageOperationMetrics earlier) {
    return new StorageOperationMetrics(
        difference(transactionsStarted, earlier.transactionsStarted, "transactionsStarted"),
        difference(transactionsCommitted, earlier.transactionsCommitted, "transactionsCommitted"),
        difference(transactionsRolledBack, earlier.transactionsRolledBack, "transactionsRolledBack"),
        difference(repositoryLocksAcquired, earlier.repositoryLocksAcquired, "repositoryLocksAcquired"),
        difference(
            repositoryLockAcquisitionNanos,
            earlier.repositoryLockAcquisitionNanos,
            "repositoryLockAcquisitionNanos"));
  }

  private static long difference(long current, long earlier, String counter) {
    long result = current - earlier;
    if (result < 0) {
      throw new IllegalArgumentException(counter + " is not a monotone snapshot");
    }
    return result;
  }
}
