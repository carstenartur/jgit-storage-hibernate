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
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Executes one explicitly selected performance investigation and retains unmodified JMH JSON. */
@Testcontainers(disabledWithoutDocker = true)
class PerformanceInvestigationsBenchmarkIT {

  private static final String INVESTIGATION_PROPERTY =
      "jgit.storage.benchmark.investigation";
  private static final String PROFILE_PROPERTY =
      "jgit.storage.benchmark.investigation.profile";
  private static final String REPOSITORY_AGING_NATIVE_SMOKE_PROPERTY =
      "jgit.storage.benchmark.repository-aging.native-smoke";
  private static final String REPOSITORY_AGING_DATABASE_BACKEND_PROPERTY =
      "jgit.storage.benchmark.repository-aging.database-backend";
  private static final String REPOSITORY_AGING_BACKEND_PROPERTY =
      "jgit.storage.benchmark.repository-aging.backend";
  private static final String REPOSITORY_AGING_CACHE_STATE_PROPERTY =
      "jgit.storage.benchmark.repository-aging.cache-state";

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
    boolean repositoryAging = "repository-aging".equals(investigation);
    boolean repositoryAgingNativeSmoke =
        repositoryAging
            && Boolean.getBoolean(REPOSITORY_AGING_NATIVE_SMOKE_PROPERTY);
    String repositoryAgingDatabaseBackend =
        repositoryAgingNativeSmoke
            ? repositoryAgingDatabaseBackend()
            : HibernateRepositoryBenchmark.POSTGRESQL;
    boolean telemetryEnabled =
        (writeQueue || repositoryAging)
            && Boolean.getBoolean(DatabaseTelemetryCollectors.ENABLED_PROPERTY);
    String telemetryPrefix = writeQueue ? "write-queue" : "repository-aging";
    Path telemetryNdjson =
        resultFile.resolveSibling(telemetryPrefix + "-database-telemetry.ndjson");
    Path telemetryJson =
        resultFile.resolveSibling(telemetryPrefix + "-database-telemetry.json");
    Path connectionPropertiesFile = null;
    MSSQLServerContainer sqlServer = null;
    Collection<RunResult> results;
    try {
      if (repositoryAgingNativeSmoke
          && PackStorageLayoutBenchmark.SQL_SERVER.equals(
              repositoryAgingDatabaseBackend)) {
        sqlServer =
            new MSSQLServerContainer(
                    "mcr.microsoft.com/mssql/server:2022-CU20-ubuntu-22.04")
                .acceptLicense();
        sqlServer.start();
      }

      List<String> jvmArguments = new ArrayList<>();
      jvmArguments.add("-Xms1g");
      jvmArguments.add(full ? "-Xmx3g" : "-Xmx1536m");
      if (writeQueue || repositoryAging) {
        connectionPropertiesFile =
            writeConnectionProperties(repositoryAgingDatabaseBackend, sqlServer);
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

      configure(
          builder,
          investigation,
          full,
          deployment,
          threads,
          repositoryAgingNativeSmoke,
          repositoryAgingDatabaseBackend);
      results = new Runner(builder.build()).run();
    } finally {
      if (connectionPropertiesFile != null) {
        Files.deleteIfExists(connectionPropertiesFile);
      }
      if (sqlServer != null) {
        sqlServer.stop();
      }
    }

    if (telemetryEnabled) {
      DatabaseTelemetryJson.writeAggregate(telemetryNdjson, telemetryJson);
      assertTrue(
          Files.isRegularFile(telemetryJson),
          investigation + " telemetry JSON was not written");
      assertTrue(
          Files.size(telemetryJson) > 32,
          investigation + " telemetry JSON is empty");
    }
    assertFalse(results.isEmpty(), "Selected investigation produced no JMH results");
    assertTrue(Files.isRegularFile(resultFile), "JMH JSON result was not written");
    assertTrue(Files.size(resultFile) > 2, "JMH JSON result is empty");
    assertTrue(Files.isRegularFile(outputFile), "JMH text output was not written");
    if (writeQueue || repositoryAging) {
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

  private static String repositoryAgingDatabaseBackend() {
    String value =
        System.getProperty(
            REPOSITORY_AGING_DATABASE_BACKEND_PROPERTY,
            HibernateRepositoryBenchmark.POSTGRESQL);
    if (!HibernateRepositoryBenchmark.POSTGRESQL.equals(value)
        && !PackStorageLayoutBenchmark.SQL_SERVER.equals(value)) {
      throw new IllegalArgumentException(
          REPOSITORY_AGING_DATABASE_BACKEND_PROPERTY
              + " must be postgresql or sqlserver but was "
              + value);
    }
    return value;
  }

  static String[] selectParameterValues(
      String propertyName,
      String configuredValue,
      String[] defaultValues,
      String... allowedValues) {
    if (configuredValue == null || configuredValue.isBlank()) {
      return defaultValues.clone();
    }
    String value = configuredValue.trim();
    if (!List.of(allowedValues).contains(value)) {
      throw new IllegalArgumentException(
          propertyName
              + " must be one of "
              + String.join(", ", allowedValues)
              + " but was "
              + value);
    }
    return new String[] {value};
  }

  private static Path writeConnectionProperties(
      String databaseBackend, MSSQLServerContainer sqlServer) throws IOException {
    Map<String, String> connectionProperties =
        switch (databaseBackend) {
          case HibernateRepositoryBenchmark.POSTGRESQL ->
              Map.of(
                  HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY,
                  POSTGRESQL.getJdbcUrl(),
                  HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY,
                  POSTGRESQL.getUsername(),
                  HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY,
                  POSTGRESQL.getPassword());
          case PackStorageLayoutBenchmark.SQL_SERVER -> {
            if (sqlServer == null || !sqlServer.isRunning()) {
              throw new IllegalStateException("SQL Server benchmark target is not running");
            }
            yield Map.of(
                PackStorageLayoutBenchmark.SQL_SERVER_URL_PROPERTY,
                sqlServer.getJdbcUrl(),
                PackStorageLayoutBenchmark.SQL_SERVER_USER_PROPERTY,
                sqlServer.getUsername(),
                PackStorageLayoutBenchmark.SQL_SERVER_PASSWORD_PROPERTY,
                sqlServer.getPassword());
          }
          default ->
              throw new IllegalArgumentException(
                  "Unsupported benchmark connection backend " + databaseBackend);
        };

    Path target = null;
    try {
      target =
          Files.createTempFile(
              "jgit-storage-benchmark-connection-", ".properties");
      try {
        Files.setPosixFilePermissions(
            target, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException ignored) {
        // Supported CI performance runners use POSIX filesystems.
      }
      Properties properties = new Properties();
      connectionProperties.forEach(properties::setProperty);
      try (OutputStream output = Files.newOutputStream(target)) {
        properties.store(output, "ephemeral benchmark connection properties");
      }
      return target;
    } catch (IOException | RuntimeException failure) {
      if (target != null) {
        try {
          Files.deleteIfExists(target);
        } catch (IOException cleanupFailure) {
          failure.addSuppressed(cleanupFailure);
        }
      }
      throw failure;
    }
  }

  private static void assertCredentialFreeEvidence(Collection<Path> files) throws IOException {
    List<String> forbidden =
        List.of(
            HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY + "=",
            HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY + "=",
            HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY + "=",
            PackStorageLayoutBenchmark.SQL_SERVER_URL_PROPERTY + "=",
            PackStorageLayoutBenchmark.SQL_SERVER_USER_PROPERTY + "=",
            PackStorageLayoutBenchmark.SQL_SERVER_PASSWORD_PROPERTY + "=",
            "jdbc:postgresql://",
            "jdbc:sqlserver://",
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
      int threads,
      boolean repositoryAgingNativeSmoke,
      String repositoryAgingDatabaseBackend) {
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
      case "repository-aging" -> {
        String[] fullBackends = {
          HibernateRepositoryBenchmark.HSQLDB,
          HibernateRepositoryBenchmark.POSTGRESQL,
          HibernateRepositoryBenchmark.POSTGRESQL_HIKARI
        };
        String[] fullCacheStates = {
          RepositoryAgingBenchmark.COLD, RepositoryAgingBenchmark.WARM
        };
        String[] backends =
            repositoryAgingNativeSmoke
                ? new String[] {repositoryAgingDatabaseBackend}
                : full
                    ? selectParameterValues(
                        REPOSITORY_AGING_BACKEND_PROPERTY,
                        System.getProperty(REPOSITORY_AGING_BACKEND_PROPERTY),
                        fullBackends,
                        fullBackends)
                    : new String[] {HibernateRepositoryBenchmark.HSQLDB};
        String[] cacheStates =
            full
                ? selectParameterValues(
                    REPOSITORY_AGING_CACHE_STATE_PROPERTY,
                    System.getProperty(REPOSITORY_AGING_CACHE_STATE_PROPERTY),
                    fullCacheStates,
                    fullCacheStates)
                : new String[] {RepositoryAgingBenchmark.COLD};
        builder
            .include(
                repositoryAgingNativeSmoke
                    ? RepositoryAgingBenchmark.class.getName()
                        + ".(lookupOldestObject|cloneStyleTraversal|reopenAndLookupOldest)"
                    : RepositoryAgingBenchmark.class.getName())
            .threads(1)
            .param("backend", backends)
            .param(
                "pushes",
                repositoryAgingNativeSmoke
                    ? new String[] {"10"}
                    : full
                        ? new String[] {"1", "10", "32", "100", "300", "1000"}
                        : new String[] {"1", "10"})
            .param(
                "maintenanceMode",
                RepositoryAgingBenchmark.NONE,
                RepositoryAgingBenchmark.COMPACT_ONLY,
                RepositoryAgingBenchmark.READ_OPTIMIZED)
            .param("cacheState", cacheStates)
            .param("deployment", deployment);
      }
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
