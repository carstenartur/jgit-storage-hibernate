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
import java.util.Arrays;
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

/** Runs the JGit/FileRepository versus indexed-history comparison with real PostgreSQL. */
@Testcontainers(disabledWithoutDocker = true)
class HistoryQueryCrossoverBenchmarkIT {

  private static final Set<String> EXPECTED_ENGINES =
      Set.of(
          HistoryQueryCrossoverBenchmark.FILESYSTEM_JGIT,
          HistoryQueryCrossoverBenchmark.HIBERNATE_JGIT,
          HistoryQueryCrossoverBenchmark.INDEXED_PROJECTION);

  private static final Set<String> EXPECTED_QUERIES =
      Set.of(
          HistoryQueryCrossoverBenchmark.AUTHOR_TIME,
          HistoryQueryCrossoverBenchmark.PATH_TIME,
          HistoryQueryCrossoverBenchmark.MESSAGE_TEXT,
          HistoryQueryCrossoverBenchmark.PATH_CONTENT,
          HistoryQueryCrossoverBenchmark.COMPOUND);

  @Container
  static final PostgreSQLContainer<?> POSTGRESQL =
      new PostgreSQLContainer<>("postgres:17.10-alpine")
          .withDatabaseName("jgit_storage_history_query_benchmark")
          .withUsername("benchmark")
          .withPassword("benchmark");

  @Test
  void recordsEquivalentOnDemandIndexedAndProjectionBuildCosts() throws Exception {
    Path resultFile =
        Path.of(
                System.getProperty(
                    "benchmark.resultFile", "target/history-query-crossover/jmh-result.json"))
            .toAbsolutePath();
    Files.createDirectories(resultFile.getParent());
    Path outputFile = resultFile.resolveSibling("jmh-output.txt");
    String[] commitCounts = commitCounts();

    Options options =
        new OptionsBuilder()
            .include(HistoryQueryCrossoverBenchmark.class.getName())
            .param("commitCount", commitCounts)
            .param("queryLimit", "500")
            .param(
                "engine",
                HistoryQueryCrossoverBenchmark.FILESYSTEM_JGIT,
                HistoryQueryCrossoverBenchmark.HIBERNATE_JGIT,
                HistoryQueryCrossoverBenchmark.INDEXED_PROJECTION)
            .param(
                "queryKind",
                HistoryQueryCrossoverBenchmark.AUTHOR_TIME,
                HistoryQueryCrossoverBenchmark.PATH_TIME,
                HistoryQueryCrossoverBenchmark.MESSAGE_TEXT,
                HistoryQueryCrossoverBenchmark.PATH_CONTENT,
                HistoryQueryCrossoverBenchmark.COMPOUND)
            .addProfiler(GCProfiler.class)
            .shouldFailOnError(true)
            .resultFormat(ResultFormatType.JSON)
            .result(resultFile.toString())
            .output(outputFile.toString())
            .jvmArgsAppend(
                "-Xms1g",
                "-Xmx2g",
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
    int expectedPerCommitCount = EXPECTED_ENGINES.size() * EXPECTED_QUERIES.size() + 1;
    assertEquals(expectedPerCommitCount * commitCounts.length, results.size());

    Set<String> queryEngines =
        results.stream()
            .filter(result -> operation(result).equals("query"))
            .map(result -> result.getParams().getParam("engine"))
            .collect(Collectors.toSet());
    assertEquals(EXPECTED_ENGINES, queryEngines);

    Set<String> queryKinds =
        results.stream()
            .filter(result -> operation(result).equals("query"))
            .map(result -> result.getParams().getParam("queryKind"))
            .collect(Collectors.toSet());
    assertEquals(EXPECTED_QUERIES, queryKinds);

    long projectionBuilds =
        results.stream().filter(result -> operation(result).equals("projectionBuild")).count();
    assertEquals(commitCounts.length, projectionBuilds);
    assertTrue(
        results.stream().allMatch(result -> result.getPrimaryResult().getScore() > 0.0),
        "Every history-query benchmark must report a positive elapsed time");
    assertTrue(Files.isRegularFile(resultFile), "History-query JMH JSON was not written");
    assertTrue(Files.size(resultFile) > 2, "History-query JMH JSON is empty");
    assertTrue(Files.isRegularFile(outputFile), "History-query JMH output was not written");
  }

  private static String[] commitCounts() {
    String configured = System.getProperty("benchmark.history.commitCounts", "1000");
    String[] values =
        Arrays.stream(configured.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .toArray(String[]::new);
    if (values.length == 0) {
      throw new IllegalArgumentException("benchmark.history.commitCounts must not be empty");
    }
    for (String value : values) {
      int count;
      try {
        count = Integer.parseInt(value);
      } catch (NumberFormatException exception) {
        throw new IllegalArgumentException(
            "Invalid benchmark.history.commitCounts value '" + value + "'", exception);
      }
      if (count <= 0 || count > 100_000) {
        throw new IllegalArgumentException(
            "benchmark.history.commitCounts values must be between 1 and 100000: " + value);
      }
    }
    return values;
  }

  private static String operation(RunResult result) {
    String benchmark = result.getParams().getBenchmark();
    return benchmark.substring(benchmark.lastIndexOf('.') + 1);
  }
}
