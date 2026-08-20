/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RepositoryAgingBenchmarkTest {

  @Test
  void warmCacheFixtureRetainsRevisionWalkAndStructuralCounters() throws Exception {
    RepositoryAgingBenchmark benchmark = new RepositoryAgingBenchmark();
    benchmark.backend = HibernateRepositoryBenchmark.HSQLDB;
    benchmark.pushes = 1;
    benchmark.maintenanceMode = RepositoryAgingBenchmark.NONE;
    benchmark.cacheState = RepositoryAgingBenchmark.WARM;
    benchmark.deployment = RepositoryAgingBenchmark.LOCAL_TESTCONTAINERS;

    try {
      benchmark.setupTrial();
      RepositoryAgingBenchmark.AgingCounters counters =
          new RepositoryAgingBenchmark.AgingCounters();
      counters.reset();
      benchmark.setupInvocation();

      assertEquals(1, benchmark.revisionWalk(counters));
      assertTrue(counters.activePacks >= 1);
      assertTrue(counters.packPayloadBytes > 0);
      assertTrue(counters.packIndexBytes > 0);
      assertEquals(0, counters.maintenanceElapsedMillis);
      assertEquals(0, counters.maintenancePackReduction);
    } finally {
      benchmark.tearDownTrial();
    }
  }
}
