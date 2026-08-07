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

/** Runs the focused stateful-versus-stateless large-pack threshold comparison. */
@Testcontainers(disabledWithoutDocker = true)
class LargePackJdbcBatchBenchmarkIT {

  private static final String FULL_PROFILE = "full";
  private static final String PRODUCTION_CHUNK_BATCH_SIZE = "16";
  private static final Set<String> EXPECTED_BACKENDS =
      Set.of(
          HibernateRepositoryBenchmark.POSTGRESQL,
          HibernateRepositoryBenchmark.POSTGRESQL_HIKARI);

  @Container
  static final PostgreSQLContainer<?> POSTGRESQL =
      new PostgreSQLContainer<>("postgres:17.10-alpine")
          .withDatabaseName("jgit_storage_batch_benchmark")
          .withUsername("benchmark")
          .withPassword("benchmark");

  @Test
  void recordsWriterModePoolPayloadAndProductionBatchSizeInRawArtifacts() throws Exception {
    boolean full =
        FULL_PROFILE.equalsIgnoreCase(
            System.getProperty("jgit.storage.benchmark.large_pack.profile", "smoke"));
    String[] modes =
        full
            ? new String[] {
              LargePackJdbcBatchBenchmark.STATEFUL_BATCHING_DISABLED,
              LargePackJdbcBatchBenchmark.STATEFUL_BATCHING,
              LargePackJdbcBatchBenchmark.STATEFUL_BATCHING_REWRITE,
              LargePackJdbcBatchBenchmark.STATELESS
            }
            : new String[] {
              LargePackJdbcBatchBenchmark.STATEFUL_BATCHING,
              LargePackJdbcBatchBenchmark.STATELESS
            };
    String[] payloadSizes = full ? new String[] {"16", "128", "512"} : new String[] {"16"};
    String deployment =
        System.getProperty(
            "jgit.storage.benchmark.deployment",
            LargePackJdbcBatchBenchmark.LOCAL_TESTCONTAINERS);

    Path resultFile =
        Path.of(
                System.getProperty(
                    "benchmark.resultFile", "target/benchmarks/jdbc-batch-jmh-result.json"))
            .toAbsolutePath();
    Files.createDirectories(resultFile.getParent());
    Path outputFile = resultFile.resolveSibling("jdbc-batch-jmh-output.txt");

    Options options =
        new OptionsBuilder()
            .include(LargePackJdbcBatchBenchmark.class.getName())
            .param("writeMode", modes)
            .param(
                "backend",
                HibernateRepositoryBenchmark.POSTGRESQL,
                HibernateRepositoryBenchmark.POSTGRESQL_HIKARI)
            .param("payloadMiB", payloadSizes)
            .param("chunkBatchSize", PRODUCTION_CHUNK_BATCH_SIZE)
            .param("deployment", deployment)
            .addProfiler(GCProfiler.class)
            .shouldFailOnError(true)
            .resultFormat(ResultFormatType.JSON)
            .result(resultFile.toString())
            .output(outputFile.toString())
            .jvmArgsAppend(
                "-Xms1g",
                full ? "-Xmx3g" : "-Xmx1536m",
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
        modes.length * EXPECTED_BACKENDS.size() * payloadSizes.length,
        results.size(),
        "Every writer/pool/payload combination must produce a result");
    assertEquals(
        Set.copyOf(Arrays.asList(modes)),
        results.stream()
            .map(result -> result.getParams().getParam("writeMode"))
            .collect(Collectors.toSet()));
    assertEquals(
        EXPECTED_BACKENDS,
        results.stream()
            .map(result -> result.getParams().getParam("backend"))
            .collect(Collectors.toSet()));
    assertEquals(
        Set.copyOf(Arrays.asList(payloadSizes)),
        results.stream()
            .map(result -> result.getParams().getParam("payloadMiB"))
            .collect(Collectors.toSet()));
    assertEquals(
        Set.of(PRODUCTION_CHUNK_BATCH_SIZE),
        results.stream()
            .map(result -> result.getParams().getParam("chunkBatchSize"))
            .collect(Collectors.toSet()));
    assertEquals(
        Set.of(deployment),
        results.stream()
            .map(result -> result.getParams().getParam("deployment"))
            .collect(Collectors.toSet()));
    assertTrue(Files.isRegularFile(resultFile), "JMH JSON result was not written");
    assertTrue(Files.isRegularFile(outputFile), "JMH text output was not written");
  }
}
