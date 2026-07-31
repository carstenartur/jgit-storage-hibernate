/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.transaction;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable per-category view of opt-in repository transaction and lock metrics.
 *
 * <p>Zero-valued categories are omitted from the stored map but are returned as {@link
 * StorageOperationMetrics#ZERO} by {@link #metrics(StorageOperationKind)}. Snapshots are monotone;
 * use {@link #minus(StorageOperationBreakdown)} to obtain one measured interval.
 */
public final class StorageOperationBreakdown {

  /** Empty snapshot returned when repository metrics are disabled. */
  public static final StorageOperationBreakdown ZERO = new StorageOperationBreakdown(Map.of());

  private final Map<StorageOperationKind, StorageOperationMetrics> metrics;

  /**
   * Create an immutable snapshot from category values.
   *
   * @param metrics category values; null keys and values are rejected
   */
  public StorageOperationBreakdown(Map<StorageOperationKind, StorageOperationMetrics> metrics) {
    Objects.requireNonNull(metrics, "metrics");
    EnumMap<StorageOperationKind, StorageOperationMetrics> copy =
        new EnumMap<>(StorageOperationKind.class);
    metrics.forEach(
        (kind, value) -> {
          Objects.requireNonNull(kind, "operation kind");
          Objects.requireNonNull(value, "operation metrics");
          if (!StorageOperationMetrics.ZERO.equals(value)) {
            copy.put(kind, value);
          }
        });
    this.metrics = Map.copyOf(copy);
  }

  /**
   * Return the immutable non-zero category map.
   *
   * @return category values present in this snapshot
   */
  public Map<StorageOperationKind, StorageOperationMetrics> asMap() {
    return metrics;
  }

  /**
   * Return metrics for one category.
   *
   * @param kind operation category
   * @return stored metrics, or zero when the category has no events
   */
  public StorageOperationMetrics metrics(StorageOperationKind kind) {
    return metrics.getOrDefault(Objects.requireNonNull(kind, "kind"), StorageOperationMetrics.ZERO);
  }

  /**
   * Sum every category into the backward-compatible aggregate representation.
   *
   * @return aggregate transaction and lock metrics
   */
  public StorageOperationMetrics total() {
    long started = 0;
    long committed = 0;
    long rolledBack = 0;
    long locks = 0;
    long lockAcquisitionNanos = 0;
    long transactionDurationNanos = 0;
    long lockHeldNanos = 0;
    for (StorageOperationMetrics value : metrics.values()) {
      started += value.transactionsStarted();
      committed += value.transactionsCommitted();
      rolledBack += value.transactionsRolledBack();
      locks += value.repositoryLocksAcquired();
      lockAcquisitionNanos += value.repositoryLockAcquisitionNanos();
      transactionDurationNanos += value.transactionDurationNanos();
      lockHeldNanos += value.repositoryLockHeldNanos();
    }
    return new StorageOperationMetrics(
        started,
        committed,
        rolledBack,
        locks,
        lockAcquisitionNanos,
        transactionDurationNanos,
        lockHeldNanos);
  }

  /**
   * Subtract an earlier monotone snapshot category by category.
   *
   * @param earlier snapshot captured before this one
   * @return immutable interval delta
   * @throws IllegalArgumentException if any earlier category counter is newer than this snapshot
   */
  public StorageOperationBreakdown minus(StorageOperationBreakdown earlier) {
    Objects.requireNonNull(earlier, "earlier");
    EnumMap<StorageOperationKind, StorageOperationMetrics> difference =
        new EnumMap<>(StorageOperationKind.class);
    for (StorageOperationKind kind : StorageOperationKind.values()) {
      StorageOperationMetrics delta = metrics(kind).minus(earlier.metrics(kind));
      if (!StorageOperationMetrics.ZERO.equals(delta)) {
        difference.put(kind, delta);
      }
    }
    return new StorageOperationBreakdown(difference);
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof StorageOperationBreakdown breakdown
            && metrics.equals(breakdown.metrics);
  }

  @Override
  public int hashCode() {
    return metrics.hashCode();
  }

  @Override
  public String toString() {
    return metrics.toString();
  }
}
