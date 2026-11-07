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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.hibernate.search.engine.Version;
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

/** Runs the Hibernate Search runtime tuning matrix through PostgreSQL Testcontainers. */
@Testcontainers(disabledWithoutDocker = true)
class HibernateSearchRuntimeTuningBenchmarkIT {

  private static final Set<String> EXPECTED_OPERATIONS =
      Set.of(
          "incrementalBurstSubmission",
          "incrementalBurstReady",
          "steadyQueriesDuringBurst",
          "projectionRebuildReady");

  @Container
  static final PostgreSQLContainer<?> POSTGRESQL =
      new PostgreSQLContainer<>("postgres:17.10-alpine")
          .withDatabaseName("jgit_storage_search_runtime")
          .withUsername("benchmark")
          .withPassword("benchmark");

  @Test
  void recordsRuntimeCostVisibilityAndConcurrentQueryEvidence() throws Exception {
    SearchRuntimeRun run = SearchRuntimeRun.from(System.getProperties());
    String affinity = cpuAffinity();
    run.resources().verifyAffinity(affinity);
    List<String> scenarios = run.scenarios();
    Path resultFile =
        Path.of(System.getProperty("benchmark.resultFile", "target/search-runtime/jmh-result.json"))
            .toAbsolutePath();
    Files.createDirectories(resultFile.getParent());
    Path outputFile = resultFile.resolveSibling("jmh-output.txt");

    writeMetadata(resultFile.resolveSibling("environment.properties"), run, affinity);

    Collection<RunResult> results;
    Path connectionFile = Files.createTempFile("jgit-search-runtime-connection-", ".properties");
    try {
      writeConnectionProperties(connectionFile);
      List<String> jvmArguments = new ArrayList<>(run.resources().jvmArguments());
      jvmArguments.add(
          RepositoryBackendBenchmarkIT.systemProperty(
              PackStorageLayoutBenchmark.CONNECTION_PROPERTIES_FILE_PROPERTY,
              connectionFile.toString()));
      Options options =
          new OptionsBuilder()
              .include(HibernateSearchRuntimeTuningBenchmark.class.getName())
              .param("commitCount", Integer.toString(run.commitCount()))
              .param("burstCount", Integer.toString(run.burstCount()))
              .param("runtimeScenario", scenarios.toArray(String[]::new))
              .warmupIterations(run.warmupIterations())
              .measurementIterations(run.measurementIterations())
              .addProfiler(GCProfiler.class)
              .shouldFailOnError(true)
              .resultFormat(ResultFormatType.JSON)
              .result(resultFile.toString())
              .output(outputFile.toString())
              .jvmArgsAppend(jvmArguments.toArray(String[]::new))
              .build();
      results = new Runner(options).run();
    } finally {
      Files.deleteIfExists(connectionFile);
    }
    for (RunResult result : results) {
      assertEquals(Integer.toString(run.commitCount()), result.getParams().getParam("commitCount"));
      assertEquals(Integer.toString(run.burstCount()), result.getParams().getParam("burstCount"));
      for (var fork : result.getBenchmarkResults()) {
        for (var iteration : fork.getIterationResults()) {
          var metrics = iteration.getSecondaryResults();
          var documents = metrics.get("luceneCommittedDocuments");
          assertNotNull(documents, "Missing persistent Lucene document count");
          assertEquals(run.commitCount(), documents.getScore(), "Lucene documents after close");
          for (String counter :
              List.of(
                  "luceneCommitGenerations",
                  "luceneCommittedSegments",
                  "luceneCommittedBytes",
                  "peakHeapPoolBytes")) {
            var measurement = metrics.get(counter);
            assertNotNull(measurement, "Missing Search runtime observation " + counter);
            assertTrue(measurement.getScore() > 0.0, "Empty Search runtime observation " + counter);
          }
        }
      }
    }
    assertEquals(EXPECTED_OPERATIONS.size() * scenarios.size(), results.size());
    assertEquals(
        EXPECTED_OPERATIONS,
        results.stream()
            .map(result -> operation(result.getParams().getBenchmark()))
            .collect(Collectors.toSet()));
    assertEquals(
        Set.copyOf(scenarios),
        results.stream()
            .map(result -> result.getParams().getParam("runtimeScenario"))
            .collect(Collectors.toSet()));
    assertTrue(
        results.stream().allMatch(result -> result.getPrimaryResult().getScore() > 0.0),
        "Every Search runtime benchmark must report a positive elapsed time");
    assertTrue(Files.isRegularFile(resultFile), "Search runtime JMH JSON was not written");
    assertTrue(Files.size(resultFile) > 2, "Search runtime JMH JSON is empty");
    assertTrue(Files.isRegularFile(outputFile), "Search runtime JMH output was not written");
  }

  private static void writeMetadata(Path target, SearchRuntimeRun run, String affinity)
      throws Exception {
    Properties metadata = new Properties();
    metadata.setProperty("schema.version", "1");
    metadata.setProperty("workload.commits", Integer.toString(run.commitCount()));
    metadata.setProperty("workload.burst.commits", Integer.toString(run.burstCount()));
    metadata.setProperty("matrix", run.full() ? "full" : "smoke");
    metadata.setProperty("warmup.iterations", Integer.toString(run.warmupIterations()));
    metadata.setProperty("measurement.iterations", Integer.toString(run.measurementIterations()));
    metadata.setProperty("runtime.scenarios", String.join(",", run.scenarios()));
    metadata.setProperty("resources", run.resources().id());
    metadata.setProperty("cpu.affinity", affinity);
    metadata.setProperty(
        "launcher.available.processors",
        Integer.toString(Runtime.getRuntime().availableProcessors()));
    metadata.setProperty(
        "fork.resource.arguments", String.join(" ", run.resources().jvmArguments()));
    metadata.setProperty("java.runtime.version", System.getProperty("java.runtime.version"));
    metadata.setProperty("java.vm.name", System.getProperty("java.vm.name"));
    metadata.setProperty("hibernate.search.version", Version.versionString());
    metadata.setProperty("lucene.version", org.apache.lucene.util.Version.LATEST.toString());
    metadata.setProperty("os.name", System.getProperty("os.name"));
    metadata.setProperty("os.arch", System.getProperty("os.arch"));
    metadata.setProperty("os.version", System.getProperty("os.version"));
    metadata.setProperty("runner", System.getenv().getOrDefault("RUNNER_NAME", "local"));
    metadata.setProperty(
        "runner.image.version", System.getenv().getOrDefault("ImageVersion", "local"));
    metadata.setProperty("commit", System.getProperty("benchmark.commit", "unknown"));
    metadata.setProperty("database.image", POSTGRESQL.getDockerImageName());
    try (var connection =
        DriverManager.getConnection(
            POSTGRESQL.getJdbcUrl(), POSTGRESQL.getUsername(), POSTGRESQL.getPassword())) {
      var database = connection.getMetaData();
      metadata.setProperty("database.version", database.getDatabaseProductVersion());
      metadata.setProperty("jdbc.driver.version", database.getDriverVersion());
    }
    try (var writer = Files.newBufferedWriter(target)) {
      metadata.store(writer, "Hibernate Search runtime benchmark environment");
    }
  }

  private static String cpuAffinity() throws IOException {
    Path status = Path.of("/proc/self/status");
    if (!Files.isReadable(status)) {
      return "unavailable";
    }
    String field = "Cpus_allowed_list:";
    try (var lines = Files.lines(status)) {
      return lines
          .filter(line -> line.startsWith(field))
          .map(line -> line.substring(field.length()).strip())
          .findFirst()
          .orElse("unavailable");
    }
  }

  private static void writeConnectionProperties(Path target) throws IOException {
    try {
      Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"));
    } catch (UnsupportedOperationException ignored) {
      // The hosted performance runner uses a POSIX filesystem.
    }
    Properties connection = new Properties();
    connection.setProperty(
        HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY, POSTGRESQL.getJdbcUrl());
    connection.setProperty(
        HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY, POSTGRESQL.getUsername());
    connection.setProperty(
        HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY, POSTGRESQL.getPassword());
    try (var writer = Files.newBufferedWriter(target)) {
      connection.store(writer, "ephemeral Search runtime connection properties");
    }
  }

  private static String operation(String benchmark) {
    return benchmark.substring(benchmark.lastIndexOf('.') + 1);
  }
}
