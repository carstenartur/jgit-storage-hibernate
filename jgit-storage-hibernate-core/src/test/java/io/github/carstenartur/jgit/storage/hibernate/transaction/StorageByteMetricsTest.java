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

class StorageByteMetricsTest {

  @Test
  void totalsAndMonotoneDifferenceReconcile() {
    StorageByteMetrics earlier = new StorageByteMetrics(1, 2, 3, 4, 5, 6, 7);
    StorageByteMetrics current = new StorageByteMetrics(2, 4, 6, 8, 10, 12, 14);

    StorageByteMetrics delta = current.minus(earlier);

    assertEquals(3, earlier.temporaryFileIoBytes());
    assertEquals(7, earlier.databasePayloadIoBytes());
    assertEquals(earlier, delta);
  }

  @Test
  void rejectsNonMonotoneSnapshots() {
    StorageByteMetrics earlier = new StorageByteMetrics(1, 0, 0, 0, 0, 0, 0);

    assertThrows(IllegalArgumentException.class, () -> StorageByteMetrics.ZERO.minus(earlier));
  }

  @Test
  void rejectsOverflowWhenCombiningTrafficDirections() {
    StorageByteMetrics metrics =
        new StorageByteMetrics(Long.MAX_VALUE, 1, Long.MAX_VALUE, 1, 0, 0, 0);

    assertThrows(ArithmeticException.class, metrics::temporaryFileIoBytes);
    assertThrows(ArithmeticException.class, metrics::databasePayloadIoBytes);
  }
}
