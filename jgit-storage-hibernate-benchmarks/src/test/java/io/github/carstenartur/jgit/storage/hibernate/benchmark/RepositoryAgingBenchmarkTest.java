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
    RepositoryAgingBenchmark benchmark = benchmark(1);
    benchmark.maintenanceMode = RepositoryAgingBenchmark.NONE;
    benchmark.cacheState = RepositoryAgingBenchmark.WARM;
    benchmark.providerLifecycle = RepositoryAgingBenchmark.SAME_PROVIDER;

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
      assertEquals(0, counters.providerRestarts);
    } finally {
      benchmark.tearDownTrial();
    }
  }

  @Test
  void restartedProviderRetainsColdFixtureAndMaintenanceResult() throws Exception {
    RepositoryAgingBenchmark benchmark = benchmark(10);
    benchmark.maintenanceMode = RepositoryAgingBenchmark.COMPACT_ONLY;
    benchmark.cacheState = RepositoryAgingBenchmark.COLD;
    benchmark.providerLifecycle = RepositoryAgingBenchmark.RESTARTED_PROVIDER;
    benchmark.evidenceRepeat = 2;

    try {
      benchmark.setupTrial();
      RepositoryAgingBenchmark.AgingCounters counters =
          new RepositoryAgingBenchmark.AgingCounters();
      counters.reset();
      benchmark.setupInvocation();

      assertEquals(10, benchmark.revisionWalk(counters));
      assertEquals(1, counters.providerRestarts);
      assertTrue(counters.activePacks < 10);
      assertTrue(counters.maintenancePackReduction > 0);
      assertTrue(counters.packPayloadBytes > 0);
      assertTrue(counters.packIndexBytes > 0);
    } finally {
      benchmark.tearDownTrial();
    }
  }

  private static RepositoryAgingBenchmark benchmark(int pushes) {
    RepositoryAgingBenchmark benchmark = new RepositoryAgingBenchmark();
    benchmark.backend = HibernateRepositoryBenchmark.HSQLDB;
    benchmark.pushes = pushes;
    benchmark.deployment = RepositoryAgingBenchmark.LOCAL_TESTCONTAINERS;
    benchmark.evidenceRepeat = 1;
    return benchmark;
  }
}
