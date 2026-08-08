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
import org.testcontainers.mssqlserver.MSSQLServerContainer;

/** Runs the reverse-reflog index comparison on both production database families. */
@Testcontainers(disabledWithoutDocker = true)
class ReflogLookupBenchmarkIT {

  private static final Set<String> EXPECTED_METHODS = Set.of("lastEntry", "lastHundred");
  private static final Set<String> EXPECTED_BACKENDS =
      Set.of(ReflogLookupBenchmark.POSTGRESQL, ReflogLookupBenchmark.SQL_SERVER);
  private static final Set<String> EXPECTED_INDEXES =
      Set.of(ReflogLookupBenchmark.LEGACY_INDEX, ReflogLookupBenchmark.REF_KEY_INDEX);

  @Container
  static final PostgreSQLContainer<?> POSTGRESQL =
      new PostgreSQLContainer<>("postgres:17.10-alpine")
          .withDatabaseName("jgit_storage_reflog_benchmark")
          .withUsername("benchmark")
          .withPassword("benchmark");

  @Container
  static final MSSQLServerContainer SQL_SERVER =
      new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-CU20-ubuntu-22.04")
          .acceptLicense();

  @Test
  void comparesLegacyAndSelectiveIndexes() throws Exception {
    Path resultFile =
        Path.of(
                System.getProperty(
                    "benchmark.resultFile", "target/reflog-performance/jmh-result.json"))
            .toAbsolutePath();
    Files.createDirectories(resultFile.getParent());
    Path outputFile = resultFile.resolveSibling("jmh-output.txt");

    Options options =
        new OptionsBuilder()
            .include(ReflogLookupBenchmark.class.getName())
            .param(
                "backend",
                ReflogLookupBenchmark.POSTGRESQL,
                ReflogLookupBenchmark.SQL_SERVER)
            .param(
                "indexMode",
                ReflogLookupBenchmark.LEGACY_INDEX,
                ReflogLookupBenchmark.REF_KEY_INDEX)
            .param("rowCount", "10000")
            .param("refCount", "100")
            .addProfiler(GCProfiler.class)
            .shouldFailOnError(true)
            .resultFormat(ResultFormatType.JSON)
            .result(resultFile.toString())
            .output(outputFile.toString())
            .jvmArgsAppend(
                "-Xms768m",
                "-Xmx1280m",
                systemProperty(
                    ReflogLookupBenchmark.POSTGRESQL_URL_PROPERTY, POSTGRESQL.getJdbcUrl()),
                systemProperty(
                    ReflogLookupBenchmark.POSTGRESQL_USER_PROPERTY, POSTGRESQL.getUsername()),
                systemProperty(
                    ReflogLookupBenchmark.POSTGRESQL_PASSWORD_PROPERTY,
                    POSTGRESQL.getPassword()),
                systemProperty(
                    ReflogLookupBenchmark.SQL_SERVER_URL_PROPERTY, SQL_SERVER.getJdbcUrl()),
                systemProperty(
                    ReflogLookupBenchmark.SQL_SERVER_USER_PROPERTY, SQL_SERVER.getUsername()),
                systemProperty(
                    ReflogLookupBenchmark.SQL_SERVER_PASSWORD_PROPERTY,
                    SQL_SERVER.getPassword()))
            .build();

    Collection<RunResult> results = new Runner(options).run();
    assertEquals(8, results.size());
    assertEquals(
        EXPECTED_METHODS,
        results.stream()
            .map(result -> operation(result.getParams().getBenchmark()))
            .collect(Collectors.toSet()));
    assertEquals(
        EXPECTED_BACKENDS,
        results.stream()
            .map(result -> result.getParams().getParam("backend"))
            .collect(Collectors.toSet()));
    assertEquals(
        EXPECTED_INDEXES,
        results.stream()
            .map(result -> result.getParams().getParam("indexMode"))
            .collect(Collectors.toSet()));
    assertTrue(
        results.stream().allMatch(result -> result.getPrimaryResult().getScore() > 0.0),
        "Every reflog benchmark must report positive elapsed time");
    assertTrue(Files.isRegularFile(resultFile), "Reflog JMH JSON was not written");
    assertTrue(Files.size(resultFile) > 2, "Reflog JMH JSON is empty");
    assertTrue(Files.isRegularFile(outputFile), "Reflog JMH output was not written");
  }

  private static String operation(String benchmark) {
    return benchmark.substring(benchmark.lastIndexOf('.') + 1);
  }

  private static String systemProperty(String name, String value) {
    return "-D" + name + "=" + value;
  }
}
