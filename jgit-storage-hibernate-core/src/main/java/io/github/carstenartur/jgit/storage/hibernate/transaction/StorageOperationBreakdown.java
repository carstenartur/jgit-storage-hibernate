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

/** Immutable per-category view of opt-in repository transaction and lock metrics. */
public final class StorageOperationBreakdown {

  public static final StorageOperationBreakdown ZERO = new StorageOperationBreakdown(Map.of());

  private final Map<StorageOperationKind, StorageOperationMetrics> metrics;

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

  public Map<StorageOperationKind, StorageOperationMetrics> asMap() {
    return metrics;
  }

  public StorageOperationMetrics metrics(StorageOperationKind kind) {
    return metrics.getOrDefault(Objects.requireNonNull(kind, "kind"), StorageOperationMetrics.ZERO);
  }

  public StorageOperationMetrics total() {
    long started = 0;
    long committed = 0;
    long rolledBack = 0;
    long locks = 0;
    long lockNanos = 0;
    for (StorageOperationMetrics value : metrics.values()) {
      started += value.transactionsStarted();
      committed += value.transactionsCommitted();
      rolledBack += value.transactionsRolledBack();
      locks += value.repositoryLocksAcquired();
      lockNanos += value.repositoryLockAcquisitionNanos();
    }
    return new StorageOperationMetrics(started, committed, rolledBack, locks, lockNanos);
  }

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
