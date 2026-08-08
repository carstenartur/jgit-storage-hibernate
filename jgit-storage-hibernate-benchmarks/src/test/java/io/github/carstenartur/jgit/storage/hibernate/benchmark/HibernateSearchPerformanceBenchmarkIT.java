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

/** Runs the dedicated Hibernate Search comparison through Maven and PostgreSQL Testcontainers. */
@Testcontainers(disabledWithoutDocker = true)
class HibernateSearchPerformanceBenchmarkIT {

  private static final Set<String> EXPECTED_OPERATIONS =
      Set.of(
          "incrementalIndexing",
          "projectionRebuild",
          "fullTextEntityHits",
          "fullTextSummaryHits",
          "contentOnlySummaryHits",
          "pathLiteralSql",
          "pathTermsLucene");

  private static final Set<String> EXPECTED_PROFILES =
      Set.of("metadata-v1", "paths-v1", "content-v1", "diff-hunks-v1");

  @Container
  static final PostgreSQLContainer<?> POSTGRESQL =
      new PostgreSQLContainer<>("postgres:17.10-alpine")
          .withDatabaseName("jgit_storage_search_benchmark")
          .withUsername("benchmark")
          .withPassword("benchmark");

  @Test
  void recordsIndexingRebuildQueryFootprintAndQualityComparisons() throws Exception {
    Path resultFile =
        Path.of(
                System.getProperty(
                    "benchmark.resultFile", "target/search-performance/jmh-result.json"))
            .toAbsolutePath();
    Files.createDirectories(resultFile.getParent());
    Path outputFile = resultFile.resolveSibling("jmh-output.txt");

    Options options =
        new OptionsBuilder()
            .include(HibernateSearchPerformanceBenchmark.class.getName())
            .param("commitCount", "100")
            .param("queryLimit", "50")
            .param(
                "indexProfile",
                "metadata-v1",
                "paths-v1",
                "content-v1",
                "diff-hunks-v1")
            .addProfiler(GCProfiler.class)
            .shouldFailOnError(true)
            .resultFormat(ResultFormatType.JSON)
            .result(resultFile.toString())
            .output(outputFile.toString())
            .jvmArgsAppend(
                "-Xms1g",
                "-Xmx1536m",
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
    assertEquals(EXPECTED_OPERATIONS.size() * EXPECTED_PROFILES.size(), results.size());
    assertEquals(
        EXPECTED_OPERATIONS,
        results.stream()
            .map(result -> operation(result.getParams().getBenchmark()))
            .collect(Collectors.toSet()));
    assertEquals(
        EXPECTED_PROFILES,
        results.stream()
            .map(result -> result.getParams().getParam("indexProfile"))
            .collect(Collectors.toSet()));
    assertTrue(
        results.stream().allMatch(result -> result.getPrimaryResult().getScore() > 0.0),
        "Every Search benchmark must report a positive elapsed time");
    assertTrue(Files.isRegularFile(resultFile), "Search JMH JSON was not written");
    assertTrue(Files.size(resultFile) > 2, "Search JMH JSON is empty");
    assertTrue(Files.isRegularFile(outputFile), "Search JMH output was not written");
  }

  private static String operation(String benchmark) {
    return benchmark.substring(benchmark.lastIndexOf('.') + 1);
  }
}
