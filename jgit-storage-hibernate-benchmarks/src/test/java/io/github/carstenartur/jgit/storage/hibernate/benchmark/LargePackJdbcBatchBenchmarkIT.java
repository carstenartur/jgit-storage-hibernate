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
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Runs only the focused large-pack JDBC batching comparison. */
@Testcontainers(disabledWithoutDocker = true)
class LargePackJdbcBatchBenchmarkIT {

  static final String RESULT_FILE_PROPERTY = "benchmark.jdbcBatchResultFile";

  private static final Set<String> EXPECTED_MODES =
      Set.of(LargePackJdbcBatchBenchmark.DISABLED, LargePackJdbcBatchBenchmark.ENABLED);

  @Container
  static final PostgreSQLContainer<?> POSTGRESQL =
      new PostgreSQLContainer<>("postgres:17.10-alpine")
          .withDatabaseName("jgit_storage_batch_benchmark")
          .withUsername("benchmark")
          .withPassword("benchmark");

  @Test
  void recordsDisabledAndEnabledBatchingModes() throws Exception {
    Path resultFile =
        Path.of(
                System.getProperty(
                    RESULT_FILE_PROPERTY, "target/benchmarks/jdbc-batch-jmh-result.json"))
            .toAbsolutePath();
    Files.createDirectories(resultFile.getParent());
    Path outputFile = resultFile.resolveSibling("jdbc-batch-jmh-output.txt");

    Options options =
        new OptionsBuilder()
            .include(LargePackJdbcBatchBenchmark.class.getName())
            .param(
                "batchingMode",
                LargePackJdbcBatchBenchmark.DISABLED,
                LargePackJdbcBatchBenchmark.ENABLED)
            .shouldFailOnError(true)
            .resultFormat(ResultFormatType.JSON)
            .result(resultFile.toString())
            .output(outputFile.toString())
            .jvmArgsAppend(
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
    assertEquals(2, results.size(), "The focused benchmark must produce exactly two results");
    assertEquals(
        EXPECTED_MODES,
        results.stream()
            .map(result -> result.getParams().getParam("batchingMode"))
            .collect(Collectors.toSet()));
    assertTrue(Files.isRegularFile(resultFile), "JMH JSON result was not written");
    assertTrue(Files.isRegularFile(outputFile), "JMH text output was not written");
  }
}
