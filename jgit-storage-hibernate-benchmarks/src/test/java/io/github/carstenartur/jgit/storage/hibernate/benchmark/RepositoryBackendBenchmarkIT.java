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

/** Runs the complete repository-backend comparison through Maven, JUnit and Testcontainers. */
@Testcontainers(disabledWithoutDocker = true)
class RepositoryBackendBenchmarkIT {

  private static final int EXPECTED_OPERATION_COUNT = 15;
  private static final Set<String> EXPECTED_BACKENDS =
      Set.of(
          HibernateRepositoryBenchmark.FILESYSTEM,
          HibernateRepositoryBenchmark.HSQLDB,
          HibernateRepositoryBenchmark.POSTGRESQL,
          HibernateRepositoryBenchmark.POSTGRESQL_HIKARI);

  @Container
  static final PostgreSQLContainer<?> POSTGRESQL =
      new PostgreSQLContainer<>("postgres:17.10-alpine")
          .withDatabaseName("jgit_storage_benchmark")
          .withUsername("benchmark")
          .withPassword("benchmark");

  @Test
  void recordsEveryOperationForEveryBackend() throws Exception {
    Path resultFile =
        Path.of(
                System.getProperty(
                    "benchmark.resultFile", "target/benchmarks/jmh-result.json"))
            .toAbsolutePath();
    Files.createDirectories(resultFile.getParent());
    Path outputFile = resultFile.resolveSibling("jmh-output.txt");

    Options options =
        new OptionsBuilder()
            .include(HibernateRepositoryBenchmark.class.getName())
            .include(GitProtocolBenchmark.class.getName())
            .param(
                "backend",
                HibernateRepositoryBenchmark.FILESYSTEM,
                HibernateRepositoryBenchmark.HSQLDB,
                HibernateRepositoryBenchmark.POSTGRESQL,
                HibernateRepositoryBenchmark.POSTGRESQL_HIKARI)
            .shouldFailOnError(true)
            .resultFormat(ResultFormatType.JSON)
            .result(resultFile.toString())
            .output(outputFile.toString())
            .jvmArgsAppend(
                systemProperty(
                    HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY,
                    POSTGRESQL.getJdbcUrl()),
                systemProperty(
                    HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY,
                    POSTGRESQL.getUsername()),
                systemProperty(
                    HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY,
                    POSTGRESQL.getPassword()))
            .build();

    Collection<RunResult> results = new Runner(options).run();

    assertEquals(
        EXPECTED_OPERATION_COUNT * EXPECTED_BACKENDS.size(),
        results.size(),
        "Every benchmark operation must run for every storage backend");
    assertEquals(
        EXPECTED_BACKENDS,
        results.stream()
            .map(result -> result.getParams().getParam("backend"))
            .collect(Collectors.toSet()));
    assertTrue(Files.isRegularFile(resultFile), "JMH JSON result was not written");
    assertTrue(Files.isRegularFile(outputFile), "JMH text output was not written");
  }

  private static String systemProperty(String name, String value) {
    return "-D" + name + "=" + value;
  }
}
