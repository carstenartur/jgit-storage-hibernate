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

import org.junit.jupiter.api.Test;

class MetricsSnapshotHashCodeTest {

  @Test
  void equalSnapshotsHaveEqualHashes() {
    StorageOperationMetrics first = new StorageOperationMetrics(1, 2, 3, 4, 5);
    StorageOperationMetrics second = new StorageOperationMetrics(1, 2, 3, 4, 5);
    assertEquals(first.hashCode(), second.hashCode());
  }
}
