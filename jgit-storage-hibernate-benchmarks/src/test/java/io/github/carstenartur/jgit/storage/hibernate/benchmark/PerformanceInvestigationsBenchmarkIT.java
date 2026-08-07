/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.objects.ReadAheadPolicyBenchmark;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Executes one explicitly selected performance investigation and retains unmodified JMH JSON. */
@Testcontainers(disabledWithoutDocker = true)
class PerformanceInvestigationsBenchmarkIT {

  private static final String INVESTIGATION_PROPERTY =
      "jgit.storage.benchmark.investigation";
  private static final String PROFILE_PROPERTY =
      "jgit.storage.benchmark.investigation.profile";

  @Container
  static final PostgreSQLContainer<?> POSTGRESQL =
      new PostgreSQLContainer<>("postgres:17.10-alpine")
          .withDatabaseName("jgit_storage_investigations")
          .withUsername("benchmark")
          .withPassword("benchmark");

  @Test
  void writesRawResultsForTheSelectedInvestigation() throws Exception {
    String investigation = System.getProperty(INVESTIGATION_PROPERTY, "read-ahead");
    boolean full = "full".equalsIgnoreCase(System.getProperty(PROFILE_PROPERTY, "smoke"));
    String deployment =
        System.getProperty(
            "jgit.storage.benchmark.deployment",
            LargePackJdbcBatchBenchmark.LOCAL_TESTCONTAINERS);
    int threads = Integer.getInteger("jgit.storage.benchmark.threads", 4);

    Path resultFile =
        Path.of(
                System.getProperty(
                    "benchmark.resultFile",
                    "target/benchmarks/" + investigation + "-jmh-result.json"))
            .toAbsolutePath();
    Files.createDirectories(resultFile.getParent());
    Path outputFile = resultFile.resolveSibling(investigation + "-jmh-output.txt");

    OptionsBuilder builder = baseOptions(resultFile, outputFile, full);
    builder.jvmArgsAppend(
        "-Xms1g",
        full ? "-Xmx3g" : "-Xmx1536m",
        RepositoryBackendBenchmarkIT.systemProperty(
            HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY, POSTGRESQL.getJdbcUrl()),
        RepositoryBackendBenchmarkIT.systemProperty(
            HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY, POSTGRESQL.getUsername()),
        RepositoryBackendBenchmarkIT.systemProperty(
            HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY,
            POSTGRESQL.getPassword()));

    configure(builder, investigation, full, deployment, threads);
    Collection<RunResult> results = new Runner(builder.build()).run();

    assertFalse(results.isEmpty(), "Selected investigation produced no JMH results");
    assertTrue(Files.isRegularFile(resultFile), "JMH JSON result was not written");
    assertTrue(Files.size(resultFile) > 2, "JMH JSON result is empty");
    assertTrue(Files.isRegularFile(outputFile), "JMH text output was not written");
  }

  private static OptionsBuilder baseOptions(Path resultFile, Path outputFile, boolean full) {
    OptionsBuilder builder = new OptionsBuilder();
    builder
        .shouldFailOnError(true)
        .forks(1)
        .warmupIterations(full ? 1 : 0)
        .warmupTime(TimeValue.milliseconds(full ? 500 : 100))
        .measurementIterations(full ? 3 : 1)
        .measurementTime(TimeValue.milliseconds(full ? 750 : 200))
        .resultFormat(ResultFormatType.JSON)
        .result(resultFile.toString())
        .output(outputFile.toString());
    return builder;
  }

  private static void configure(
      OptionsBuilder builder,
      String investigation,
      boolean full,
      String deployment,
      int threads) {
    switch (investigation) {
      case "write-queue" ->
          builder
              .include(DurableWriteQueueBenchmark.class.getName())
              .threads(threads)
              .param(
                  "backend",
                  HibernateRepositoryBenchmark.POSTGRESQL,
                  HibernateRepositoryBenchmark.POSTGRESQL_HIKARI)
              .param(
                  "executionMode",
                  DurableWriteQueueBenchmark.DIRECT,
                  DurableWriteQueueBenchmark.QUEUE_1,
                  DurableWriteQueueBenchmark.QUEUE_4,
                  DurableWriteQueueBenchmark.QUEUE_8)
              .param("payloadKiB", full ? new String[] {"64", "384"} : new String[] {"64"});
      case "read-ahead" ->
          builder
              .include(ReadAheadPolicyBenchmark.class.getName())
              .threads(1)
              .param(
                  "backend",
                  full
                      ? new String[] {"hsqldb", "postgresql", "postgresql-hikari"}
                      : new String[] {"hsqldb"})
              .param("readAheadChunks", "1", "4", "16");
      case "repository-aging" ->
          builder
              .include(RepositoryAgingBenchmark.class.getName())
              .threads(1)
              .param(
                  "backend",
                  full
                      ? new String[] {
                        HibernateRepositoryBenchmark.HSQLDB,
                        HibernateRepositoryBenchmark.POSTGRESQL,
                        HibernateRepositoryBenchmark.POSTGRESQL_HIKARI
                      }
                      : new String[] {HibernateRepositoryBenchmark.HSQLDB})
              .param(
                  "pushes",
                  full ? new String[] {"1", "10", "100", "1000"} : new String[] {"1", "10"})
              .param(
                  "maintenanceMode",
                  RepositoryAgingBenchmark.NONE,
                  RepositoryAgingBenchmark.COMPACT_ONLY,
                  RepositoryAgingBenchmark.READ_OPTIMIZED)
              .param("deployment", deployment);
      case "concurrent-large-pack" ->
          builder
              .include(ConcurrentPublicationBenchmark.class.getName())
              .threads(threads)
              .param(
                  "backend",
                  HibernateRepositoryBenchmark.POSTGRESQL,
                  HibernateRepositoryBenchmark.POSTGRESQL_HIKARI)
              .param(
                  "writeMode",
                  ConcurrentPublicationBenchmark.STATEFUL,
                  ConcurrentPublicationBenchmark.STATELESS)
              .param(
                  "payloadMiB", full ? new String[] {"16", "128"} : new String[] {"16"})
              .param("deployment", deployment)
              .addProfiler(GCProfiler.class);
      default ->
          throw new IllegalArgumentException(
              "Unsupported "
                  + INVESTIGATION_PROPERTY
                  + " value '"
                  + investigation
                  + "'; expected write-queue, read-ahead, repository-aging or concurrent-large-pack");
    }
  }
}
