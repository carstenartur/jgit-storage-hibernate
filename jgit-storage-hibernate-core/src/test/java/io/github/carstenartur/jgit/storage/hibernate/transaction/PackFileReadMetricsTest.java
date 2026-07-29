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

class PackFileReadMetricsTest {

  @Test
  void totalsAndMonotoneDifferenceReconcile() {
    PackFileReadMetrics earlier = new PackFileReadMetrics(1, 2, 3, 4, 5, 6, 7, 8, 9);
    PackFileReadMetrics current = new PackFileReadMetrics(2, 4, 6, 8, 10, 12, 14, 16, 18);

    PackFileReadMetrics delta = current.minus(earlier);

    assertEquals(36, earlier.successfulReads());
    assertEquals(45, earlier.totalLookups());
    assertEquals(36, delta.successfulReads());
    assertEquals(45, delta.totalLookups());
    assertEquals(earlier, delta);
  }

  @Test
  void rejectsNonMonotoneSnapshots() {
    PackFileReadMetrics current = PackFileReadMetrics.ZERO;
    PackFileReadMetrics earlier = new PackFileReadMetrics(1, 0, 0, 0, 0, 0, 0, 0, 0);

    assertThrows(IllegalArgumentException.class, () -> current.minus(earlier));
  }
}
