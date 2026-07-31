/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class StorageDurationMetricsTest {

  @Test
  void calculatesDurationDeltasAndBreakdownTotals() {
    StorageDurationMetrics earlier = new StorageDurationMetrics(11, 3);
    StorageDurationMetrics current = new StorageDurationMetrics(29, 13);
    StorageDurationMetrics delta = new StorageDurationMetrics(18, 10);

    assertEquals(delta, current.minus(earlier));

    StorageDurationBreakdown breakdown =
        new StorageDurationBreakdown(
            Map.of(
                StorageOperationKind.PACK_PUBLICATION,
                new StorageDurationMetrics(12, 8),
                StorageOperationKind.REF_PUBLICATION,
                new StorageDurationMetrics(6, 2)));
    assertEquals(delta, breakdown.total());
    assertEquals(
        StorageDurationMetrics.ZERO,
        breakdown.metrics(StorageOperationKind.PACK_FILE_READ));
  }

  @Test
  void rejectsEveryNonMonotoneDuration() {
    StorageDurationMetrics baseline = new StorageDurationMetrics(2, 2);
    assertThrows(
        IllegalArgumentException.class,
        () -> new StorageDurationMetrics(1, 2).minus(baseline));
    assertThrows(
        IllegalArgumentException.class,
        () -> new StorageDurationMetrics(2, 1).minus(baseline));

    StorageDurationBreakdown older =
        new StorageDurationBreakdown(
            Map.of(StorageOperationKind.OTHER, new StorageDurationMetrics(1, 1)));
    StorageDurationBreakdown newer =
        new StorageDurationBreakdown(
            Map.of(StorageOperationKind.OTHER, new StorageDurationMetrics(2, 2)));
    assertThrows(IllegalArgumentException.class, () -> older.minus(newer));
  }
}
