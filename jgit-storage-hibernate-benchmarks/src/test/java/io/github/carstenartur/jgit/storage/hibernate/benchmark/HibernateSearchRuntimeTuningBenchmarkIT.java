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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Runs the Hibernate Search runtime tuning matrix through PostgreSQL Testcontainers. */
@Testcontainers(disabledWithoutDocker = true)
class HibernateSearchRuntimeTuningBenchmarkIT {

  private static final Set<String> EXPECTED_OPERATIONS =
      Set.of(
          "incrementalBurstSubmission",
          "incrementalBurstReady",
          "steadyQueriesDuringBurst",
          "projectionRebuildReady");

  @Container
  static final PostgreSQLContainer<?> POSTGRESQL =
      new PostgreSQLContainer<>("postgres:17.10-alpine")
          .withDatabaseName("jgit_storage_search_runtime")
          .withUsername("benchmark")
          .withPassword("benchmark");

  @Test
  void recordsRuntimeCostVisibilityAndConcurrentQueryEvidence() throws Exception {
    boolean full = Boolean.getBoolean("search.runtime.full");
    List<String> scenarios =
        full ? SearchRuntimeScenario.fullScenarioIds() : SearchRuntimeScenario.smokeScenarioIds();
    Path resultFile =
        Path.of(
                System.getProperty(
                    "benchmark.resultFile", "target/search-runtime/jmh-result.json"))
            .toAbsolutePath();
    Files.createDirectories(resultFile.getParent());
    Path outputFile = resultFile.resolveSibling("jmh-output.txt");

    Options options =
        new OptionsBuilder()
            .include(HibernateSearchRuntimeTuningBenchmark.class.getName())
            .param("commitCount", "100")
            .param("burstCount", "50")
            .param("runtimeScenario", scenarios.toArray(String[]::new))
            .warmupIterations(full ? 1 : 0)
            .measurementIterations(full ? 3 : 2)
            .addProfiler(GCProfiler.class)
            .shouldFailOnError(true)
            .resultFormat(ResultFormatType.JSON)
            .result(resultFile.toString())
            .output(outputFile.toString())
            .jvmArgsAppend(
                "-Xms768m",
                "-Xmx1024m",
                RepositoryBackendBenchmarkIT.systemProperty(
                    HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY,
                    POSTGRESQL.getJdbcUrl()),
                RepositoryBackendBenchmarkIT.systemProperty(
                    HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY,
                    POSTGRESQL.getUsername()),
                RepositoryBackendBenchmarkIT.systemProperty(
                    HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY,
                    POSTGRESQL.getPassword()))
            .build();

    Collection<RunResult> results = new Runner(options).run();
    assertEquals(EXPECTED_OPERATIONS.size() * scenarios.size(), results.size());
    assertEquals(
        EXPECTED_OPERATIONS,
        results.stream()
            .map(result -> operation(result.getParams().getBenchmark()))
            .collect(Collectors.toSet()));
    assertEquals(
        Set.copyOf(scenarios),
        results.stream()
            .map(result -> result.getParams().getParam("runtimeScenario"))
            .collect(Collectors.toSet()));
    assertTrue(
        results.stream().allMatch(result -> result.getPrimaryResult().getScore() > 0.0),
        "Every Search runtime benchmark must report a positive elapsed time");
    assertTrue(Files.isRegularFile(resultFile), "Search runtime JMH JSON was not written");
    assertTrue(Files.size(resultFile) > 2, "Search runtime JMH JSON is empty");
    assertTrue(Files.isRegularFile(outputFile), "Search runtime JMH output was not written");
  }

  private static String operation(String benchmark) {
    return benchmark.substring(benchmark.lastIndexOf('.') + 1);
  }
}
