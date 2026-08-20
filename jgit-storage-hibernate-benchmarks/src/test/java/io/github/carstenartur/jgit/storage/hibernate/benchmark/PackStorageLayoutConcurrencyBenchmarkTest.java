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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
import org.openjdk.jmh.runner.options.TimeValue;
import org.testcontainers.containers.PostgreSQLContainer;

/** Runs shared-schema pack-layout evidence with 1, 4 and 16 concurrent workers. */
class PackStorageLayoutConcurrencyBenchmarkTest {

  private static final String ENABLED_PROPERTY =
      "jgit.storage.benchmark.pack-layout.concurrency.enabled";
  private static final String PROFILE_PROPERTY =
      "jgit.storage.benchmark.pack-layout.concurrency.profile";
  private static final int[] CONCURRENCY_LEVELS = {1, 4, 16};

  @Test
  void writesRawConcurrencyEvidence() throws Exception {
    assumeTrue(Boolean.getBoolean(ENABLED_PROPERTY), "pack layout concurrency benchmark is opt-in");
    String profile = System.getProperty(PROFILE_PROPERTY, "smoke").toLowerCase();
    boolean smoke = switch (profile) {
      case "smoke" -> true;
      case "full" -> false;
      default ->
          throw new IllegalArgumentException(
              PROFILE_PROPERTY + " must be smoke or full but was " + profile);
    };

    Path resultFile =
        Path.of(
                System.getProperty(
                    "benchmark.resultFile",
                    "target/pack-storage-layout-concurrency/jmh-result.json"))
            .toAbsolutePath();
    Files.createDirectories(resultFile.getParent());
    Path rawDirectory = resultFile.getParent().resolve("raw");
    Files.createDirectories(rawDirectory);

    List<Path> rawResults = new ArrayList<>();
    List<Path> rawOutputs = new ArrayList<>();
    List<RunResult> allResults = new ArrayList<>();
    try (PostgreSQLContainer<?> postgresql =
        new PostgreSQLContainer<>("postgres:17.10-alpine")
            .withDatabaseName("jgit_storage_pack_layout_concurrency")
            .withUsername("benchmark")
            .withPassword("benchmark")) {
      postgresql.start();
      for (int concurrency : CONCURRENCY_LEVELS) {
        Path rawResult = rawDirectory.resolve("workers-" + concurrency + "-jmh-result.json");
        Path rawOutput = rawDirectory.resolve("workers-" + concurrency + "-jmh-output.txt");
        Collection<RunResult> results =
            new Runner(
                    options(
                        smoke,
                        concurrency,
                        postgresql,
                        rawResult,
                        rawOutput))
                .run();
        assertFalse(results.isEmpty(), concurrency + " workers produced no results");
        allResults.addAll(results);
        rawResults.add(rawResult);
        rawOutputs.add(rawOutput);
      }
    }

    mergeJsonArrays(rawResults, resultFile);
    mergeTextOutputs(rawOutputs, resultFile.resolveSibling("jmh-output.txt"));
    assertTrue(Files.isRegularFile(resultFile));
    assertTrue(Files.size(resultFile) > 2);
    assertEquals(
        Set.of("1", "4", "16"),
        allResults.stream()
            .map(result -> result.getParams().getParam("concurrency"))
            .collect(Collectors.toSet()));
    assertTrue(
        allResults.stream().allMatch(result -> result.getPrimaryResult().getScore() > 0.0),
        "Every concurrency sample must have positive latency");
  }

  private static Options options(
      boolean smoke,
      int concurrency,
      PostgreSQLContainer<?> postgresql,
      Path resultFile,
      Path outputFile) {
    return new OptionsBuilder()
        .include(PackStorageLayoutConcurrencyBenchmark.class.getName())
        .param("backend", HibernateRepositoryBenchmark.POSTGRESQL)
        .param(
            "operation",
            smoke
                ? new String[] {
                  PackStorageLayoutConcurrencyBenchmark.WRITE,
                  PackStorageLayoutConcurrencyBenchmark.SEQUENTIAL_READ,
                  PackStorageLayoutConcurrencyBenchmark.RANDOM_READ
                }
                : new String[] {
                  PackStorageLayoutConcurrencyBenchmark.WRITE,
                  PackStorageLayoutConcurrencyBenchmark.SEQUENTIAL_READ,
                  PackStorageLayoutConcurrencyBenchmark.SHORT_READ,
                  PackStorageLayoutConcurrencyBenchmark.RANDOM_READ
                })
        .param("payloadKiB", smoke ? "1024" : "16384")
        .param(
            "chunkKiB",
            smoke
                ? new String[] {"1024", "4096"}
                : new String[] {"256", "1024", "2048", "4096"})
        .param("inlineKiB", "256")
        .param("retainedMiB", "16")
        .param("readAheadKiB", "1024")
        .param("concurrency", Integer.toString(concurrency))
        .param("deployment", "postgresql-concurrency-" + concurrency)
        .threads(concurrency)
        .forks(1)
        .warmupIterations(smoke ? 0 : 1)
        .warmupTime(TimeValue.milliseconds(smoke ? 50 : 200))
        .measurementIterations(smoke ? 1 : 2)
        .measurementTime(TimeValue.milliseconds(smoke ? 150 : 400))
        .addProfiler(GCProfiler.class)
        .shouldFailOnError(true)
        .resultFormat(ResultFormatType.JSON)
        .result(resultFile.toString())
        .output(outputFile.toString())
        .jvmArgsAppend(
            "-Xms1g",
            smoke ? "-Xmx2g" : "-Xmx4g",
            RepositoryBackendBenchmarkIT.systemProperty(
                HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY,
                postgresql.getJdbcUrl()),
            RepositoryBackendBenchmarkIT.systemProperty(
                HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY,
                postgresql.getUsername()),
            RepositoryBackendBenchmarkIT.systemProperty(
                HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY,
                postgresql.getPassword()))
        .build();
  }

  private static void mergeJsonArrays(List<Path> sources, Path target) throws Exception {
    StringBuilder merged = new StringBuilder("[\n");
    boolean first = true;
    for (Path source : sources) {
      String json = Files.readString(source).trim();
      if (!json.startsWith("[") || !json.endsWith("]")) {
        throw new IllegalStateException("Unexpected JMH JSON structure in " + source);
      }
      String body = json.substring(1, json.length() - 1).trim();
      if (body.isEmpty()) {
        continue;
      }
      if (!first) {
        merged.append(",\n");
      }
      merged.append(body);
      first = false;
    }
    merged.append("\n]\n");
    Files.writeString(target, merged);
  }

  private static void mergeTextOutputs(List<Path> sources, Path target) throws Exception {
    StringBuilder merged = new StringBuilder();
    for (Path source : sources) {
      merged
          .append("===== ")
          .append(source.getFileName())
          .append(" =====\n")
          .append(Files.readString(source))
          .append('\n');
    }
    Files.writeString(target, merged);
  }
}
