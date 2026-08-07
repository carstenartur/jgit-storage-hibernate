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

/** Selects a large-pack stateful/stateless threshold at the production chunk batch size. */
@Testcontainers(disabledWithoutDocker = true)
class StatelessWriterThresholdBenchmarkIT {

  private static final String[] WRITE_MODES = {
    LargePackJdbcBatchBenchmark.STATEFUL_BATCHING,
    LargePackJdbcBatchBenchmark.STATELESS
  };
  private static final String[] PAYLOAD_SIZES_MIB = {"16", "128", "512"};
  private static final String CHUNK_BATCH_SIZE = "16";
  private static final String DEPLOYMENT = "local-testcontainers-threshold";

  @Container
  static final PostgreSQLContainer<?> POSTGRESQL =
      new PostgreSQLContainer<>("postgres:17.10-alpine")
          .withDatabaseName("jgit_storage_stateless_threshold")
          .withUsername("benchmark")
          .withPassword("benchmark");

  @Test
  void comparesStatefulAndStatelessAtSixteenOneHundredTwentyEightAndFiveHundredTwelveMiB()
      throws Exception {
    Path resultFile =
        Path.of(
                System.getProperty(
                    "benchmark.resultFile",
                    "target/stateless-threshold/stateless-threshold-jmh-result.json"))
            .toAbsolutePath();
    Files.createDirectories(resultFile.getParent());
    Path outputFile = resultFile.resolveSibling("stateless-threshold-jmh-output.txt");

    Options options =
        new OptionsBuilder()
            .include(LargePackJdbcBatchBenchmark.class.getName())
            .param("writeMode", WRITE_MODES)
            .param("backend", HibernateRepositoryBenchmark.POSTGRESQL_HIKARI)
            .param("payloadMiB", PAYLOAD_SIZES_MIB)
            .param("chunkBatchSize", CHUNK_BATCH_SIZE)
            .param("deployment", DEPLOYMENT)
            .warmupIterations(1)
            .measurementIterations(3)
            .forks(1)
            .addProfiler(GCProfiler.class)
            .shouldFailOnError(true)
            .resultFormat(ResultFormatType.JSON)
            .result(resultFile.toString())
            .output(outputFile.toString())
            .jvmArgsAppend(
                "-Xms3g",
                "-Xmx3g",
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

    assertEquals(
        WRITE_MODES.length * PAYLOAD_SIZES_MIB.length,
        results.size(),
        "Every writer and payload combination must produce one result");
    assertEquals(
        Set.copyOf(Arrays.asList(WRITE_MODES)),
        results.stream()
            .map(result -> result.getParams().getParam("writeMode"))
            .collect(Collectors.toSet()));
    assertEquals(
        Set.copyOf(Arrays.asList(PAYLOAD_SIZES_MIB)),
        results.stream()
            .map(result -> result.getParams().getParam("payloadMiB"))
            .collect(Collectors.toSet()));
    assertEquals(
        Set.of(CHUNK_BATCH_SIZE),
        results.stream()
            .map(result -> result.getParams().getParam("chunkBatchSize"))
            .collect(Collectors.toSet()));
    assertTrue(
        results.stream().allMatch(result -> result.getPrimaryResult().getScore() > 0.0),
        "Every publication must have a positive elapsed time");
    assertTrue(Files.isRegularFile(resultFile), "JMH JSON result was not written");
    assertTrue(Files.size(resultFile) > 2, "JMH JSON result is empty");
    assertTrue(Files.isRegularFile(outputFile), "JMH text output was not written");
  }
}
