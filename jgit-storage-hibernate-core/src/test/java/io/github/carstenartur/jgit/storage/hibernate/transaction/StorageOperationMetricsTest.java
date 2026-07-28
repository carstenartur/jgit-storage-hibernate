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

import org.junit.jupiter.api.Test;

class StorageOperationMetricsTest {

  @Test
  void calculatesAllCounterDeltas() {
    StorageOperationMetrics earlier = new StorageOperationMetrics(2, 1, 1, 3, 5);
    StorageOperationMetrics current = new StorageOperationMetrics(7, 5, 2, 9, 17);

    assertEquals(new StorageOperationMetrics(5, 4, 1, 6, 12), current.minus(earlier));
  }

  @Test
  void rejectsEveryNonMonotoneCounter() {
    StorageOperationMetrics baseline = new StorageOperationMetrics(1, 1, 1, 1, 1);
    assertThrows(
        IllegalArgumentException.class,
        () -> new StorageOperationMetrics(0, 1, 1, 1, 1).minus(baseline));
    assertThrows(
        IllegalArgumentException.class,
        () -> new StorageOperationMetrics(1, 0, 1, 1, 1).minus(baseline));
    assertThrows(
        IllegalArgumentException.class,
        () -> new StorageOperationMetrics(1, 1, 0, 1, 1).minus(baseline));
    assertThrows(
        IllegalArgumentException.class,
        () -> new StorageOperationMetrics(1, 1, 1, 0, 1).minus(baseline));
    assertThrows(
        IllegalArgumentException.class,
        () -> new StorageOperationMetrics(1, 1, 1, 1, 0).minus(baseline));
  }
}
