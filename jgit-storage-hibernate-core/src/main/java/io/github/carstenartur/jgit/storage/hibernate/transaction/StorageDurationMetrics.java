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
 * Monotone cumulative durations for one Hibernate-backed repository instance.
 *
 * <p>Durations are collected only when repository metrics are enabled through {@value
 * HibernateTransactionContext#METRICS_ENABLED_PROPERTY}. Transaction duration starts after
 * Hibernate successfully begins the top-level transaction and ends after commit or rollback
 * returns. Repository-lock hold duration starts after the first successful pessimistic lock
 * acquisition in that transaction and ends at the same transaction completion point.
 *
 * <p>Lock acquisition duration remains part of {@link StorageOperationMetrics}; keeping wait and
 * hold time separate makes it possible to distinguish contention from expensive work performed
 * while the repository is already locked.
 *
 * @param transactionDurationNanos elapsed duration of completed top-level storage transactions
 * @param repositoryLockHeldNanos elapsed duration for transactions that held the repository lock
 */
public record StorageDurationMetrics(
    long transactionDurationNanos, long repositoryLockHeldNanos) {

  /** Empty duration snapshot. */
  public static final StorageDurationMetrics ZERO = new StorageDurationMetrics(0, 0);

  /**
   * Calculate the non-negative difference from an earlier monotone snapshot.
   *
   * @param earlier earlier snapshot from the same repository instance
   * @return duration delta
   */
  public StorageDurationMetrics minus(StorageDurationMetrics earlier) {
    return new StorageDurationMetrics(
        difference(
            transactionDurationNanos,
            earlier.transactionDurationNanos,
            "transactionDurationNanos"),
        difference(
            repositoryLockHeldNanos,
            earlier.repositoryLockHeldNanos,
            "repositoryLockHeldNanos"));
  }

  private static long difference(long current, long earlier, String counter) {
    long result = current - earlier;
    if (result < 0) {
      throw new IllegalArgumentException(counter + " is not a monotone snapshot");
    }
    return result;
  }
}
