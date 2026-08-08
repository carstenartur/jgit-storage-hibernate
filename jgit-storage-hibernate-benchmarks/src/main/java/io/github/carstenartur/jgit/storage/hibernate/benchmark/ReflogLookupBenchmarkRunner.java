/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import java.util.stream.Collectors;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Standalone entry point for the PostgreSQL/SQL Server reflog index comparison.
 *
 * <p>The dedicated workflow owns the external database containers and appends the SQL Server JDBC
 * driver to the runtime classpath. Keeping container orchestration outside this Java class avoids
 * adding SQL Server-only dependencies to the portable benchmark artifact.
 */
public final class ReflogLookupBenchmarkRunner {

  private static final Set<String> EXPECTED_METHODS = Set.of("lastEntry", "lastHundred");
  private static final Set<String> EXPECTED_BACKENDS =
      Set.of(ReflogLookupBenchmark.POSTGRESQL, ReflogLookupBenchmark.SQL_SERVER);
  private static final Set<String> EXPECTED_INDEXES =
      Set.of(ReflogLookupBenchmark.LEGACY_INDEX, ReflogLookupBenchmark.REF_KEY_INDEX);

  private ReflogLookupBenchmarkRunner() {}

  /** Run the complete retained 10,000-row/100-ref comparison. */
  public static void main(String[] arguments) throws Exception {
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
            .param("rowCount", System.getProperty("benchmark.rowCount", "10000"))
            .param("refCount", System.getProperty("benchmark.refCount", "100"))
            .addProfiler(GCProfiler.class)
            .shouldFailOnError(true)
            .resultFormat(ResultFormatType.JSON)
            .result(resultFile.toString())
            .output(outputFile.toString())
            .jvmArgsAppend(
                "-Xms768m",
                "-Xmx1280m",
                requiredSystemProperty(ReflogLookupBenchmark.POSTGRESQL_URL_PROPERTY),
                requiredSystemProperty(ReflogLookupBenchmark.POSTGRESQL_USER_PROPERTY),
                requiredSystemProperty(ReflogLookupBenchmark.POSTGRESQL_PASSWORD_PROPERTY),
                requiredSystemProperty(ReflogLookupBenchmark.SQL_SERVER_URL_PROPERTY),
                requiredSystemProperty(ReflogLookupBenchmark.SQL_SERVER_USER_PROPERTY),
                requiredSystemProperty(ReflogLookupBenchmark.SQL_SERVER_PASSWORD_PROPERTY))
            .build();

    Collection<RunResult> results = new Runner(options).run();
    verifyResults(results);
    if (!Files.isRegularFile(resultFile) || Files.size(resultFile) <= 2) {
      throw new IllegalStateException("Reflog JMH JSON was not written: " + resultFile);
    }
    if (!Files.isRegularFile(outputFile)) {
      throw new IllegalStateException("Reflog JMH output was not written: " + outputFile);
    }
  }

  private static String requiredSystemProperty(String name) {
    String value = System.getProperty(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException("Missing required reflog benchmark property " + name);
    }
    return "-D" + name + "=" + value;
  }

  private static void verifyResults(Collection<RunResult> results) {
    if (results.size() != 8) {
      throw new IllegalStateException("Expected eight reflog benchmark results, got " + results.size());
    }
    Set<String> methods =
        results.stream()
            .map(result -> operation(result.getParams().getBenchmark()))
            .collect(Collectors.toSet());
    Set<String> backends =
        results.stream()
            .map(result -> result.getParams().getParam("backend"))
            .collect(Collectors.toSet());
    Set<String> indexes =
        results.stream()
            .map(result -> result.getParams().getParam("indexMode"))
            .collect(Collectors.toSet());
    if (!EXPECTED_METHODS.equals(methods)
        || !EXPECTED_BACKENDS.equals(backends)
        || !EXPECTED_INDEXES.equals(indexes)) {
      throw new IllegalStateException(
          "Incomplete reflog matrix: methods="
              + methods
              + ", backends="
              + backends
              + ", indexes="
              + indexes);
    }
    if (results.stream().anyMatch(result -> result.getPrimaryResult().getScore() <= 0.0)) {
      throw new IllegalStateException("Every reflog benchmark must report positive elapsed time");
    }
  }

  private static String operation(String benchmark) {
    return benchmark.substring(benchmark.lastIndexOf('.') + 1);
  }
}
