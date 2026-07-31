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
 * Immutable per-category view of opt-in storage transaction and repository-lock durations.
 *
 * <p>Zero-valued categories are omitted from the stored map but are returned as {@link
 * StorageDurationMetrics#ZERO} by {@link #metrics(StorageOperationKind)}. Snapshots are monotone;
 * use {@link #minus(StorageDurationBreakdown)} to obtain one measured interval.
 */
public final class StorageDurationBreakdown {

  /** Empty snapshot returned when repository metrics are disabled. */
  public static final StorageDurationBreakdown ZERO = new StorageDurationBreakdown(Map.of());

  private final Map<StorageOperationKind, StorageDurationMetrics> metrics;

  /**
   * Create an immutable snapshot from category values.
   *
   * @param metrics category values; null keys and values are rejected
   */
  public StorageDurationBreakdown(Map<StorageOperationKind, StorageDurationMetrics> metrics) {
    Objects.requireNonNull(metrics, "metrics");
    EnumMap<StorageOperationKind, StorageDurationMetrics> copy =
        new EnumMap<>(StorageOperationKind.class);
    metrics.forEach(
        (kind, value) -> {
          Objects.requireNonNull(kind, "operation kind");
          Objects.requireNonNull(value, "duration metrics");
          if (!StorageDurationMetrics.ZERO.equals(value)) {
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
  public Map<StorageOperationKind, StorageDurationMetrics> asMap() {
    return metrics;
  }

  /**
   * Return durations for one category.
   *
   * @param kind operation category
   * @return stored durations, or zero when the category has no timed work
   */
  public StorageDurationMetrics metrics(StorageOperationKind kind) {
    return metrics.getOrDefault(Objects.requireNonNull(kind, "kind"), StorageDurationMetrics.ZERO);
  }

  /**
   * Sum every category into the aggregate representation.
   *
   * @return aggregate transaction and repository-lock durations
   */
  public StorageDurationMetrics total() {
    long transactionNanos = 0;
    long lockHeldNanos = 0;
    for (StorageDurationMetrics value : metrics.values()) {
      transactionNanos += value.transactionDurationNanos();
      lockHeldNanos += value.repositoryLockHeldNanos();
    }
    return new StorageDurationMetrics(transactionNanos, lockHeldNanos);
  }

  /**
   * Subtract an earlier monotone snapshot category by category.
   *
   * @param earlier snapshot captured before this one
   * @return immutable interval delta
   * @throws IllegalArgumentException if any earlier category duration is newer than this snapshot
   */
  public StorageDurationBreakdown minus(StorageDurationBreakdown earlier) {
    Objects.requireNonNull(earlier, "earlier");
    EnumMap<StorageOperationKind, StorageDurationMetrics> difference =
        new EnumMap<>(StorageOperationKind.class);
    for (StorageOperationKind kind : StorageOperationKind.values()) {
      StorageDurationMetrics delta = metrics(kind).minus(earlier.metrics(kind));
      if (!StorageDurationMetrics.ZERO.equals(delta)) {
        difference.put(kind, delta);
      }
    }
    return new StorageDurationBreakdown(difference);
  }

  @Override
  public boolean equals(Object other) {
    return this == other
        || other instanceof StorageDurationBreakdown breakdown
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
