/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class SearchRuntimeRunTest {
  @Test
  void defaultsPreserveThePublishedSmokeCoordinate() {
    SearchRuntimeRun run = SearchRuntimeRun.from(new Properties());
    assertEquals(100, run.commitCount());
    assertEquals(50, run.burstCount());
    assertEquals(SearchRuntimeScenario.smokeScenarioIds(), run.scenarios());
    assertEquals(0, run.warmupIterations());
    assertEquals(2, run.measurementIterations());
    assertEquals(List.of("-Xms768m", "-Xmx1024m"), run.resources().jvmArguments());
    run.resources().verifyAffinity("unavailable");
  }

  @Test
  void choosesALargeBurstWithAnExplicitJvmBudget() {
    SearchRuntimeRun run = SearchRuntimeRun.from(properties("10000-500", "constrained", "false"));
    assertEquals(10_000, run.commitCount());
    assertEquals(500, run.burstCount());
    assertEquals(
        List.of("-Xms256m", "-Xmx512m", "-XX:ActiveProcessorCount=1"),
        run.resources().jvmArguments());
    run.resources().verifyAffinity("7");
    assertThrows(IllegalArgumentException.class, () -> run.resources().verifyAffinity("1,7"));
  }

  @Test
  void acceptsSparseCpuIdsAndKeepsTheFullSettingsAtOneThousandCommits() {
    SearchRuntimeRun run = SearchRuntimeRun.from(properties("1000-50", "normal", "true"));
    assertEquals(SearchRuntimeScenario.fullScenarioIds(), run.scenarios());
    assertEquals(1, run.warmupIterations());
    assertEquals(3, run.measurementIterations());
    assertEquals(
        List.of("-Xms768m", "-Xmx1024m", "-XX:ActiveProcessorCount=4"),
        run.resources().jvmArguments());
    run.resources().verifyAffinity("1,3-4,7");
    assertThrows(IllegalArgumentException.class, () -> run.resources().verifyAffinity("0-2"));
    assertThrows(IllegalArgumentException.class, () -> run.resources().verifyAffinity("unavailable"));
  }

  @Test
  void rejectsInvalidOrOverlongCoordinatesBeforeStartingTheFixture() {
    assertThrows(
        IllegalArgumentException.class,
        () -> SearchRuntimeRun.from(properties("100-500", "host", "false")));
    assertThrows(
        IllegalArgumentException.class,
        () -> SearchRuntimeRun.from(properties("100000-500", "host", "false")));
    assertThrows(
        IllegalArgumentException.class,
        () -> SearchRuntimeRun.from(properties("10000-50", "normal", "true")));
    assertThrows(
        IllegalArgumentException.class,
        () -> SearchRuntimeRun.from(properties("1000-50", "typo", "false")));
    assertThrows(
        IllegalArgumentException.class,
        () -> SearchRuntimeRun.from(properties("1000-50", "host", "typo")));
  }

  private static Properties properties(String workload, String resources, String full) {
    Properties properties = new Properties();
    properties.setProperty("search.runtime.workload", workload);
    properties.setProperty("search.runtime.resources", resources);
    properties.setProperty("search.runtime.full", full);
    return properties;
  }
}
