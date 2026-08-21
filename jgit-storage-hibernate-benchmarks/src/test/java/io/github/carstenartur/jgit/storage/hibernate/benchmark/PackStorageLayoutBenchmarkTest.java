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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.mssqlserver.MSSQLServerContainer;

/** Runs the explicitly enabled pack-storage-layout benchmark matrix and retains raw JMH JSON. */
class PackStorageLayoutBenchmarkTest {

  private static final String ENABLED_PROPERTY =
      "jgit.storage.benchmark.pack-layout.enabled";
  private static final String BACKEND_PROPERTY =
      "jgit.storage.benchmark.pack-layout.backend";
  private static final String PROFILE_PROPERTY =
      "jgit.storage.benchmark.pack-layout.profile";

  @Test
  void writesRawLayoutEvidence() throws Exception {
    assumeTrue(Boolean.getBoolean(ENABLED_PROPERTY), "pack layout benchmark is opt-in");
    String backend =
        System.getProperty(BACKEND_PROPERTY, HibernateRepositoryBenchmark.HSQLDB);
    String profile = System.getProperty(PROFILE_PROPERTY, "smoke").toLowerCase();
    List<Scenario> scenarios = scenarios(profile);

    Path resultFile =
        Path.of(
                System.getProperty(
                    "benchmark.resultFile",
                    "target/pack-storage-layout/pack-storage-layout-jmh-result.json"))
            .toAbsolutePath();
    Files.createDirectories(resultFile.getParent());
    Path rawDirectory = resultFile.getParent().resolve("raw");
    Files.createDirectories(rawDirectory);
    Path telemetryNdjson = rawDirectory.resolve("database-telemetry.ndjson");
    Files.deleteIfExists(telemetryNdjson);

    List<Path> rawResults = new ArrayList<>();
    List<Path> rawOutputs = new ArrayList<>();
    int resultCount = 0;
    try (DatabaseTarget target = databaseTarget(backend)) {
      int index = 0;
      for (Scenario scenario : scenarios) {
        Path rawResult = rawDirectory.resolve("scenario-" + index + "-jmh-result.json");
        Path rawOutput = rawDirectory.resolve("scenario-" + index + "-jmh-output.txt");
        Collection<RunResult> results =
            new Runner(options(backend, profile, scenario, target, rawResult, rawOutput)).run();
        assertFalse(results.isEmpty(), "Pack layout scenario " + index + " produced no results");
        resultCount += results.size();
        rawResults.add(rawResult);
        rawOutputs.add(rawOutput);
        index++;
      }
    }

    mergeJsonArrays(rawResults, resultFile);
    mergeTextOutputs(
        rawOutputs, resultFile.resolveSibling("pack-storage-layout-jmh-output.txt"));
    if (Boolean.getBoolean(DatabaseTelemetryCollectors.ENABLED_PROPERTY)) {
      Path telemetry =
          resultFile.resolveSibling("pack-storage-layout-database-telemetry.json");
      DatabaseTelemetryJson.writeAggregate(telemetryNdjson, telemetry);
      assertTrue(Files.isRegularFile(telemetry));
      assertTrue(Files.size(telemetry) > 32);
    }
    assertTrue(resultCount > 0);
    assertTrue(Files.isRegularFile(resultFile));
    assertTrue(Files.size(resultFile) > 2);
    assertCredentialFreeEvidence(resultFile.getParent());
  }

  @Test
  void fullAndCapacityProfilesDoNotRepeatBenchmarkCoordinates() {
    assertUniqueCoordinates("full");
    assertUniqueCoordinates("capacity");
  }

  @Test
  void capacityProfileIncludesSparseReadsForEveryChunkSize() {
    Set<String> coordinates = new HashSet<>();
    for (Scenario scenario : scenarios("capacity")) {
      for (String operation : scenario.operations()) {
        if (!PackStorageLayoutBenchmark.SHORT_READ.equals(operation)
            && !PackStorageLayoutBenchmark.RANDOM_READ.equals(operation)) {
          continue;
        }
        for (String payloadKiB : scenario.payloadKiB()) {
          if (!"524288".equals(payloadKiB)) {
            continue;
          }
          for (String readAheadKiB : scenario.readAheadKiB()) {
            if (!"1024".equals(readAheadKiB)) {
              continue;
            }
            for (String chunkKiB : scenario.chunkKiB()) {
              coordinates.add(operation + ":" + chunkKiB);
            }
          }
        }
      }
    }

    for (String chunkKiB : chunkSizes()) {
      assertTrue(
          coordinates.contains(PackStorageLayoutBenchmark.SHORT_READ + ":" + chunkKiB),
          () -> "Capacity profile is missing short-read evidence for chunk " + chunkKiB);
      assertTrue(
          coordinates.contains(PackStorageLayoutBenchmark.RANDOM_READ + ":" + chunkKiB),
          () -> "Capacity profile is missing random-read evidence for chunk " + chunkKiB);
    }
  }

  private static void assertUniqueCoordinates(String profile) {
    Set<String> coordinates = new HashSet<>();
    for (Scenario scenario : scenarios(profile)) {
      for (String operation : scenario.operations()) {
        for (String payloadKiB : scenario.payloadKiB()) {
          for (String chunkKiB : scenario.chunkKiB()) {
            for (String inlineKiB : scenario.inlineKiB()) {
              for (String retainedMiB : scenario.retainedMiB()) {
                for (String readAheadKiB : scenario.readAheadKiB()) {
                  String coordinate =
                      String.join(
                          ":",
                          operation,
                          payloadKiB,
                          chunkKiB,
                          inlineKiB,
                          retainedMiB,
                          readAheadKiB);
                  assertTrue(
                      coordinates.add(coordinate),
                      () -> profile + " repeats benchmark coordinate " + coordinate);
                }
              }
            }
          }
        }
      }
    }
  }

  private static Options options(
      String backend,
      String profile,
      Scenario scenario,
      DatabaseTarget target,
      Path resultFile,
      Path outputFile) {
    List<String> jvmArguments = new ArrayList<>();
    jvmArguments.add("-Xms512m");
    jvmArguments.add("capacity".equals(profile) ? "-Xmx3g" : "-Xmx2g");
    jvmArguments.addAll(target.jvmArguments());
    if (Boolean.getBoolean(DatabaseTelemetryCollectors.ENABLED_PROPERTY)
        && !HibernateRepositoryBenchmark.HSQLDB.equals(backend)) {
      jvmArguments.add(
          RepositoryBackendBenchmarkIT.systemProperty(
              DatabaseTelemetryCollectors.ENABLED_PROPERTY, "true"));
      jvmArguments.add(
          RepositoryBackendBenchmarkIT.systemProperty(
              DatabaseTelemetryCollectors.OUTPUT_PROPERTY,
              resultFile
                  .getParent()
                  .resolve("database-telemetry.ndjson")
                  .toString()));
    }

    return new OptionsBuilder()
        .include(PackStorageLayoutBenchmark.class.getName())
        .param("backend", backend)
        .param("operation", scenario.operations())
        .param("payloadKiB", scenario.payloadKiB())
        .param("chunkKiB", scenario.chunkKiB())
        .param("inlineKiB", scenario.inlineKiB())
        .param("retainedMiB", scenario.retainedMiB())
        .param("readAheadKiB", scenario.readAheadKiB())
        .param("deployment", backend + "-" + profile)
        .forks(1)
        .warmupIterations("smoke".equals(profile) ? 0 : 1)
        .warmupTime(TimeValue.milliseconds("smoke".equals(profile) ? 50 : 200))
        .measurementIterations("smoke".equals(profile) ? 1 : 2)
        .measurementTime(TimeValue.milliseconds("smoke".equals(profile) ? 100 : 300))
        .addProfiler(GCProfiler.class)
        .shouldFailOnError(true)
        .resultFormat(ResultFormatType.JSON)
        .result(resultFile.toString())
        .output(outputFile.toString())
        .jvmArgsAppend(jvmArguments.toArray(String[]::new))
        .build();
  }

  private static List<Scenario> scenarios(String profile) {
    return switch (profile) {
      case "smoke" ->
          List.of(
              new Scenario(
                  operations(),
                  new String[] {"64", "1024"},
                  new String[] {"256", "1024"},
                  new String[] {"64", "256"},
                  new String[] {"8"},
                  new String[] {"1024"}));
      case "full" ->
          List.of(
              new Scenario(
                  operations(),
                  new String[] {"64", "256"},
                  new String[] {"1024"},
                  new String[] {"64", "256", "1024"},
                  new String[] {"16"},
                  new String[] {"1024"}),
              new Scenario(
                  readOperations(),
                  new String[] {"1024"},
                  new String[] {"1024"},
                  new String[] {"64", "256", "1024"},
                  new String[] {"16"},
                  new String[] {"1024"}),
              new Scenario(
                  new String[] {PackStorageLayoutBenchmark.WRITE},
                  new String[] {"1024", "16384", "131072"},
                  chunkSizes(),
                  new String[] {"256"},
                  retainedBudgets(),
                  new String[] {"1024"}),
              new Scenario(
                  readOperations(),
                  new String[] {"16384"},
                  chunkSizes(),
                  new String[] {"256"},
                  new String[] {"16"},
                  readAheadSizes()),
              new Scenario(
                  new String[] {PackStorageLayoutBenchmark.SEQUENTIAL_READ},
                  new String[] {"131072"},
                  chunkSizes(),
                  new String[] {"256"},
                  new String[] {"16"},
                  new String[] {"1024", "4096", "16384"}));
      case "capacity" ->
          List.of(
              new Scenario(
                  new String[] {PackStorageLayoutBenchmark.WRITE},
                  new String[] {"524288"},
                  chunkSizes(),
                  new String[] {"256"},
                  retainedBudgets(),
                  new String[] {"1024"}),
              new Scenario(
                  new String[] {PackStorageLayoutBenchmark.SEQUENTIAL_READ},
                  new String[] {"524288"},
                  chunkSizes(),
                  new String[] {"256"},
                  new String[] {"16"},
                  new String[] {"1024", "4096", "16384"}),
              new Scenario(
                  new String[] {
                    PackStorageLayoutBenchmark.SHORT_READ,
                    PackStorageLayoutBenchmark.RANDOM_READ
                  },
                  new String[] {"524288"},
                  chunkSizes(),
                  new String[] {"256"},
                  new String[] {"16"},
                  new String[] {"1024"}));
      default ->
          throw new IllegalArgumentException(
              PROFILE_PROPERTY + " must be smoke, full or capacity but was " + profile);
    };
  }

  private static String[] operations() {
    return new String[] {
      PackStorageLayoutBenchmark.WRITE,
      PackStorageLayoutBenchmark.SEQUENTIAL_READ,
      PackStorageLayoutBenchmark.SHORT_READ,
      PackStorageLayoutBenchmark.RANDOM_READ
    };
  }

  private static String[] readOperations() {
    return new String[] {
      PackStorageLayoutBenchmark.SEQUENTIAL_READ,
      PackStorageLayoutBenchmark.SHORT_READ,
      PackStorageLayoutBenchmark.RANDOM_READ
    };
  }

  private static String[] chunkSizes() {
    return new String[] {"256", "1024", "2048", "4096"};
  }

  private static String[] retainedBudgets() {
    return new String[] {"8", "16", "32"};
  }

  private static String[] readAheadSizes() {
    return new String[] {"256", "1024", "4096", "16384"};
  }

  private static DatabaseTarget databaseTarget(String backend) {
    return switch (backend) {
      case HibernateRepositoryBenchmark.HSQLDB -> DatabaseTarget.local();
      case HibernateRepositoryBenchmark.POSTGRESQL -> DatabaseTarget.postgresql();
      case PackStorageLayoutBenchmark.SQL_SERVER -> DatabaseTarget.sqlServer();
      default -> throw new IllegalArgumentException("Unsupported layout backend " + backend);
    };
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

  private static void assertCredentialFreeEvidence(Path root) throws IOException {
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
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(Files::isRegularFile).toList()) {
        String content = Files.readString(file);
        for (String token : forbidden) {
          assertFalse(
              content.contains(token),
              () -> "Retained evidence " + file + " contains " + token);
        }
      }
    }
  }

  private record Scenario(
      String[] operations,
      String[] payloadKiB,
      String[] chunkKiB,
      String[] inlineKiB,
      String[] retainedMiB,
      String[] readAheadKiB) {}

  private static final class DatabaseTarget implements AutoCloseable {
    private final PostgreSQLContainer<?> postgresql;
    private final MSSQLServerContainer sqlServer;
    private final Path connectionPropertiesFile;
    private final List<String> jvmArguments;

    private DatabaseTarget(
        PostgreSQLContainer<?> postgresql,
        MSSQLServerContainer sqlServer,
        Path connectionPropertiesFile,
        List<String> jvmArguments) {
      this.postgresql = postgresql;
      this.sqlServer = sqlServer;
      this.connectionPropertiesFile = connectionPropertiesFile;
      this.jvmArguments = List.copyOf(jvmArguments);
    }

    private static DatabaseTarget local() {
      return new DatabaseTarget(null, null, null, List.of());
    }

    private static DatabaseTarget postgresql() {
      PostgreSQLContainer<?> container =
          new PostgreSQLContainer<>("postgres:17.10-alpine")
              .withDatabaseName("jgit_storage_pack_layout")
              .withUsername("benchmark")
              .withPassword("benchmark");
      container.start();
      try {
        Path connectionPropertiesFile =
            writeConnectionProperties(
                Map.of(
                    HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY,
                    container.getJdbcUrl(),
                    HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY,
                    container.getUsername(),
                    HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY,
                    container.getPassword()));
        return new DatabaseTarget(
            container,
            null,
            connectionPropertiesFile,
            List.of(
                RepositoryBackendBenchmarkIT.systemProperty(
                    PackStorageLayoutBenchmark.CONNECTION_PROPERTIES_FILE_PROPERTY,
                    connectionPropertiesFile.toString())));
      } catch (RuntimeException failure) {
        container.stop();
        throw failure;
      }
    }

    private static DatabaseTarget sqlServer() {
      MSSQLServerContainer container =
          new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-CU20-ubuntu-22.04")
              .acceptLicense();
      container.start();
      try {
        Path connectionPropertiesFile =
            writeConnectionProperties(
                Map.of(
                    PackStorageLayoutBenchmark.SQL_SERVER_URL_PROPERTY,
                    container.getJdbcUrl(),
                    PackStorageLayoutBenchmark.SQL_SERVER_USER_PROPERTY,
                    container.getUsername(),
                    PackStorageLayoutBenchmark.SQL_SERVER_PASSWORD_PROPERTY,
                    container.getPassword()));
        return new DatabaseTarget(
            null,
            container,
            connectionPropertiesFile,
            List.of(
                RepositoryBackendBenchmarkIT.systemProperty(
                    PackStorageLayoutBenchmark.CONNECTION_PROPERTIES_FILE_PROPERTY,
                    connectionPropertiesFile.toString())));
      } catch (RuntimeException failure) {
        container.stop();
        throw failure;
      }
    }

    private static Path writeConnectionProperties(Map<String, String> values) {
      Path target = null;
      try {
        target =
            Files.createTempFile(
                "jgit-storage-benchmark-connection-", ".properties");
        try {
          Files.setPosixFilePermissions(
              target, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
          // The CI and supported production benchmark runners use POSIX filesystems.
        }
        Properties properties = new Properties();
        values.forEach(properties::setProperty);
        try (OutputStream output = Files.newOutputStream(target)) {
          properties.store(output, "ephemeral benchmark connection properties");
        }
        return target;
      } catch (IOException failure) {
        if (target != null) {
          try {
            Files.deleteIfExists(target);
          } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
          }
        }
        throw new IllegalStateException(
            "Cannot create temporary benchmark connection properties", failure);
      }
    }

    private List<String> jvmArguments() {
      return jvmArguments;
    }

    @Override
    public void close() {
      RuntimeException stopFailure = null;
      try {
        if (postgresql != null) {
          postgresql.stop();
        }
        if (sqlServer != null) {
          sqlServer.stop();
        }
      } catch (RuntimeException failure) {
        stopFailure = failure;
      }
      try {
        if (connectionPropertiesFile != null) {
          Files.deleteIfExists(connectionPropertiesFile);
        }
      } catch (IOException cleanupFailure) {
        if (stopFailure != null) {
          stopFailure.addSuppressed(cleanupFailure);
        } else {
          throw new IllegalStateException(
              "Cannot delete temporary benchmark connection properties",
              cleanupFailure);
        }
      }
      if (stopFailure != null) {
        throw stopFailure;
      }
    }
  }
}
