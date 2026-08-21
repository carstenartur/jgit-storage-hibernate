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
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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
    boolean writeQueue = "write-queue".equals(investigation);
    boolean telemetryEnabled =
        writeQueue
            && Boolean.getBoolean(DatabaseTelemetryCollectors.ENABLED_PROPERTY);
    Path telemetryNdjson =
        resultFile.resolveSibling("write-queue-database-telemetry.ndjson");
    Path telemetryJson =
        resultFile.resolveSibling("write-queue-database-telemetry.json");
    Path connectionPropertiesFile = null;
    List<String> jvmArguments = new ArrayList<>();
    jvmArguments.add("-Xms1g");
    jvmArguments.add(full ? "-Xmx3g" : "-Xmx1536m");
    if (writeQueue) {
      connectionPropertiesFile = writeConnectionProperties();
      jvmArguments.add(
          RepositoryBackendBenchmarkIT.systemProperty(
              PackStorageLayoutBenchmark.CONNECTION_PROPERTIES_FILE_PROPERTY,
              connectionPropertiesFile.toString()));
    } else {
      jvmArguments.add(
          RepositoryBackendBenchmarkIT.systemProperty(
              HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY,
              POSTGRESQL.getJdbcUrl()));
      jvmArguments.add(
          RepositoryBackendBenchmarkIT.systemProperty(
              HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY,
              POSTGRESQL.getUsername()));
      jvmArguments.add(
          RepositoryBackendBenchmarkIT.systemProperty(
              HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY,
              POSTGRESQL.getPassword()));
    }
    if (telemetryEnabled) {
      Files.deleteIfExists(telemetryNdjson);
      Files.deleteIfExists(telemetryJson);
      jvmArguments.add(
          RepositoryBackendBenchmarkIT.systemProperty(
              DatabaseTelemetryCollectors.ENABLED_PROPERTY, "true"));
      jvmArguments.add(
          RepositoryBackendBenchmarkIT.systemProperty(
              DatabaseTelemetryCollectors.OUTPUT_PROPERTY,
              telemetryNdjson.toString()));
    }
    builder.jvmArgsAppend(jvmArguments.toArray(String[]::new));

    configure(builder, investigation, full, deployment, threads);
    Collection<RunResult> results;
    try {
      results = new Runner(builder.build()).run();
    } finally {
      if (connectionPropertiesFile != null) {
        Files.deleteIfExists(connectionPropertiesFile);
      }
    }

    if (telemetryEnabled) {
      DatabaseTelemetryJson.writeAggregate(telemetryNdjson, telemetryJson);
      assertTrue(
          Files.isRegularFile(telemetryJson),
          "Write-queue telemetry JSON was not written");
      assertTrue(
          Files.size(telemetryJson) > 32,
          "Write-queue telemetry JSON is empty");
    }
    assertFalse(results.isEmpty(), "Selected investigation produced no JMH results");
    assertTrue(Files.isRegularFile(resultFile), "JMH JSON result was not written");
    assertTrue(Files.size(resultFile) > 2, "JMH JSON result is empty");
    assertTrue(Files.isRegularFile(outputFile), "JMH text output was not written");
    if (writeQueue) {
      List<Path> retainedEvidence = new ArrayList<>();
      retainedEvidence.add(resultFile);
      retainedEvidence.add(outputFile);
      if (telemetryEnabled) {
        retainedEvidence.add(telemetryNdjson);
        retainedEvidence.add(telemetryJson);
      }
      assertCredentialFreeEvidence(retainedEvidence);
    }
  }

  private static Path writeConnectionProperties() throws IOException {
    Path target =
        Files.createTempFile(
            "jgit-storage-benchmark-connection-", ".properties");
    try {
      Files.setPosixFilePermissions(
          target, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException ignored) {
      // Supported CI performance runners use POSIX filesystems.
    }
    Properties properties = new Properties();
    Map.of(
            HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY,
            POSTGRESQL.getJdbcUrl(),
            HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY,
            POSTGRESQL.getUsername(),
            HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY,
            POSTGRESQL.getPassword())
        .forEach(properties::setProperty);
    try (OutputStream output = Files.newOutputStream(target)) {
      properties.store(output, "ephemeral benchmark connection properties");
    } catch (IOException failure) {
      try {
        Files.deleteIfExists(target);
      } catch (IOException cleanupFailure) {
        failure.addSuppressed(cleanupFailure);
      }
      throw failure;
    }
    return target;
  }

  private static void assertCredentialFreeEvidence(Collection<Path> files) throws IOException {
    List<String> forbidden =
        List.of(
            HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY + "=",
            HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY + "=",
            HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY + "=",
            "jdbc:postgresql://",
            "Database JDBC URL",
            "Default catalog/schema");
    for (Path file : files) {
      String content =
          new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
      for (String token : forbidden) {
        assertFalse(
            content.contains(token),
            () -> "Retained evidence " + file + " contains " + token);
      }
    }
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
                  full
                      ? new String[] {"1", "10", "32", "100", "300", "1000"}
                      : new String[] {"1", "10"})
              .param(
                  "maintenanceMode",
                  RepositoryAgingBenchmark.NONE,
                  RepositoryAgingBenchmark.COMPACT_ONLY,
                  RepositoryAgingBenchmark.READ_OPTIMIZED)
              .param(
                  "cacheState",
                  full
                      ? new String[] {RepositoryAgingBenchmark.COLD, RepositoryAgingBenchmark.WARM}
                      : new String[] {RepositoryAgingBenchmark.COLD})
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
