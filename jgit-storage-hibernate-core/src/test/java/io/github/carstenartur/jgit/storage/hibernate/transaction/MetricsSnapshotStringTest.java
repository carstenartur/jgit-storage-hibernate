/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.transaction;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MetricsSnapshotStringTest {

  @Test
  void recordStringNamesTheCounters() {
    String value = new StorageOperationMetrics(1, 2, 3, 4, 5).toString();
    assertTrue(value.contains("transactionsStarted=1"));
    assertTrue(value.contains("repositoryLocksAcquired=4"));
  }
}
