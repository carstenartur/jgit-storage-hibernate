/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

class ConsumerProfileBenchmarkTest {
  @Test
  void runsAllConsumerShapesAndRetainsRawMeasurements() throws Exception {
    assumeTrue(Boolean.getBoolean("consumer.profiles.run"), "opt-in JMH run");
    String profile = System.getProperty("consumer.profiles.matrix", "smoke");
    boolean postgresLarge = profile.equals("postgres-taxonomy-large");
    boolean postgresSmoke = profile.equals("postgres-taxonomy-smoke");
    boolean postgres = profile.equals("postgres-taxonomy") || postgresLarge || postgresSmoke;
    boolean audioConcurrent = profile.equals("audio-concurrent") || profile.equals("audio-concurrent-smoke");
    boolean audioLarge = profile.equals("audio-concurrent");
    boolean sandboxIndex = profile.equals("sandbox-index-smoke") || profile.equals("sandbox-index");
    boolean sandboxLarge = profile.equals("sandbox-index");
    if (!List.of("smoke", "large", "audio-concurrent-smoke", "audio-concurrent",
            "sandbox-index-smoke", "sandbox-index",
            "postgres-taxonomy-smoke", "postgres-taxonomy", "postgres-taxonomy-large")
        .contains(profile)) {
      throw new IllegalArgumentException("Unrecognized consumer benchmark profile");
    }
    String selectedShape = System.getProperty("consumer.profiles.shape");
    var shapes = postgres ? List.of("taxonomy")
        : audioConcurrent ? List.of("audio") : sandboxIndex ? List.of("sandbox")
        : List.of("audio", "taxonomy", "sandbox");
    int samples = profile.equals("large") || audioLarge || sandboxLarge ? 3 : 1;
    if (selectedShape != null && !shapes.contains(selectedShape)) {
      throw new IllegalArgumentException("Expected audio, taxonomy or sandbox shape");
    }
    Path root = Path.of(System.getProperty("consumer.profiles.output", "target/consumer-profiles"));
    Files.createDirectories(root);
    if (postgres) {
      writePostgresRoundTripCalibration(root);
    }

    for (var shape : selectedShape == null ? shapes : List.of(selectedShape)) {
      String coordinates =
          switch (shape) {
            case "audio" -> audioLarge ? "16777216"
                : profile.equals("large") ? "16777216,134217728" : "262144,2097152";
            case "taxonomy" ->
                postgresLarge
                    ? "1000,10000,100000"
                    : postgresSmoke
                        ? "1000"
                        : postgres
                        ? "1000,10000"
                        : profile.equals("smoke") ? "100" : "1000,10000,100000";
            case "sandbox" -> profile.equals("smoke") || profile.equals("sandbox-index-smoke") ? "10" : "250,1000";
            default -> throw new IllegalStateException(shape);
          };
      Path json = root.resolve(shape + ".json").toAbsolutePath();
      String heap =
          postgresLarge || (profile.equals("large") && !shape.equals("sandbox"))
              ? "-Xmx3g"
              : "-Xmx1g";
      var options =
          new OptionsBuilder()
              .include(
                  ".*\\." + (sandboxIndex ? "ConsumerSandboxIndexBenchmark" : "ConsumerShapeBenchmark") + "\\."
                      + (postgres ? "taxonomy.*" : shape + ".*"))
              .param("coordinate", coordinates.split(","))
              .warmupIterations(profile.equals("large") || audioLarge || sandboxLarge ? 1 : 0)
              .measurementIterations(samples)
              .forks(1)
              .addProfiler(GCProfiler.class)
              .shouldFailOnError(true)
              .resultFormat(ResultFormatType.JSON)
              .result(json.toString())
              .output(root.resolve(shape + ".log").toAbsolutePath().toString());
      if (postgresLarge) {
        options.timeout(TimeValue.minutes(30));
      }
      if (postgres) {
        String key = BenchmarkConnectionProperties.CONNECTION_PROPERTIES_FILE_PROPERTY;
        String file = System.getProperty(key);
        if (file == null || !Files.isRegularFile(Path.of(file))) {
          throw new IllegalArgumentException(
              "A disposable PostgreSQL connection properties file is required");
        }
        Path nativeEvidence = root.resolve("taxonomy-native.ndjson").toAbsolutePath();
        Files.deleteIfExists(nativeEvidence);
        options.jvmArgsAppend(
            "-Xms256m", heap, "-D" + key + "=" + Path.of(file).toAbsolutePath(),
            "-D" + DatabaseTelemetryCollectors.ENABLED_PROPERTY + "=true",
            "-D" + DatabaseTelemetryCollectors.OUTPUT_PROPERTY + "=" + nativeEvidence);
        options.param("deployment", "postgresql-rtt-" + Integer.parseInt(
            System.getProperty("consumer.profiles.taxonomy.rttMillis", "0")) + "ms");
        options.param("cacheState", System.getProperty("consumer.profiles.taxonomy.cache", "reopened"));
        options.param("indexBatch", System.getProperty("consumer.profiles.taxonomy.indexBatch", "50"));
        options.param("purgeBatch", System.getProperty("consumer.profiles.taxonomy.purgeBatch", "250"));
      } else {
        options.jvmArgsAppend("-Xms256m", heap)
            .exclude(".*\\.taxonomy(ProjectionRebuild|SqlPathSummary)$");
      }
      int variants = 1;
      if (shape.equals("audio")) {
        boolean large = profile.equals("large") || audioLarge;
        String[] writers = values("writer", large ? "stateful,stateless" : "auto");
        String[] pressure = values("pressure", audioConcurrent ? "bounded" : large ? "idle,bounded" : "idle");
        String[] batches = values("chunkBatch", audioLarge ? "8,16,32" : "16");
        String[] repositories = values("repositories", audioConcurrent ? "1,4" : "1");
        String[] analysisMiB = values("analysisMiB", "32");
        String[] analysisWorkers = values("analysisWorkers", "1");
        options.param("writer", writers).param("pressure", pressure).param("chunkBatch", batches)
            .param("repositories", repositories).param("analysisMiB", analysisMiB)
            .param("analysisWorkers", analysisWorkers);
        variants = writers.length * pressure.length * batches.length * repositories.length
            * analysisMiB.length * analysisWorkers.length;
      }
      String java = System.getProperty("consumer.profiles.java");
      if (java != null) {
        options.jvm(java);
      }
      var results = new Runner(options.build()).run();
      Set<String> expected =
          switch (shape) {
            case "audio" -> Set.of("audioPublish", "audioReadRecent");
            case "taxonomy" ->
                postgres
                    ? Set.of("taxonomyProjectionRebuild", "taxonomySqlPathSummary",
                        "taxonomyExactPathSummary", "taxonomyTextSummary", "taxonomyTextEntity")
                    : Set.of(
                        "taxonomyExactPathSummary", "taxonomyTextSummary", "taxonomyTextEntity");
            case "sandbox" -> sandboxIndex
                ? Set.of("sandboxPersistBodyEdit", "sandboxRebuildSemanticIndex",
                    "sandboxPathSymbols", "sandboxMethodReferences")
                : Set.of("sandboxAnalyzeBodyEdit", "sandboxRebuildSnapshot");
            default -> throw new IllegalStateException(shape);
          };
      assertEquals(
          expected.size() * coordinates.split(",").length * variants, results.size(), shape);
      assertEquals(
          expected,
          results.stream()
              .map(result -> result.getParams().getBenchmark().replaceFirst(".*\\.", ""))
              .collect(Collectors.toSet()));
      for (var result : results) {
        var measurements =
            result.getBenchmarkResults().stream()
                .flatMap(fork -> fork.getIterationResults().stream())
                .toList();
        assertEquals(samples, measurements.size());
        for (var measurement : measurements) {
          var metrics = measurement.getSecondaryResults();
          assertTrue(metrics.containsKey("gc.alloc.rate.norm"));
          assertTrue(metrics.containsKey("gc.count"));
          switch (shape) {
            case "audio" -> {
              assertEquals(
                  Double.parseDouble(result.getParams().getParam("coordinate")),
                  metrics.get("payloadBytes").getScore()
                      / Double.parseDouble(result.getParams().getParam("repositories")));
              assertEquals(Double.parseDouble(result.getParams().getParam("repositories")),
                  metrics.get("repositoriesProcessed").getScore());
              assertEquals(
                  result.getParams().getParam("pressure").equals("bounded")
                      ? Double.parseDouble(result.getParams().getParam("analysisMiB")) * 1024 * 1024
                      : 0,
                  metrics.get("retainedAnalysisBytes").getScore());
              if (result.getParams().getBenchmark().endsWith("audioPublish")) {
                assertTrue(metrics.get("databaseBytesWritten").getScore() > 0);
              }
            }
            case "taxonomy" -> {
              if (result.getParams().getBenchmark().endsWith("taxonomyProjectionRebuild")) {
                assertEquals(
                    Double.parseDouble(result.getParams().getParam("coordinate")),
                    metrics.get("resultCount").getScore());
              } else {
                assertTrue(metrics.get("resultCount").getScore() > 0);
              }
            }
            case "sandbox" -> {
              String operation = result.getParams().getBenchmark().replaceFirst(".*\\.", "");
              int files =
                  operation.equals("sandboxAnalyzeBodyEdit") || operation.equals("sandboxPersistBodyEdit")
                      ? 1 : operation.equals("sandboxPathSymbols") || operation.equals("sandboxMethodReferences")
                      ? 0 : Integer.parseInt(result.getParams().getParam("coordinate")) + 1;
              assertEquals((double) files, metrics.get("javaFilesAnalyzed").getScore());
              if (sandboxIndex) {
                assertTrue(metrics.get("indexBytes").getScore() > 0);
                assertTrue(metrics.get("resultCount").getScore() > 0);
                if (files > 0) {
                  assertEquals((double) files, metrics.get("transactions").getScore());
                }
              }
            }
          }
        }
      }
      assertTrue(results.stream().allMatch(result -> result.getPrimaryResult().getScore() > 0));
      assertTrue(Files.size(json) > 2);
      if (postgres) {
        Path nativeEvidence = root.resolve("taxonomy-native.ndjson");
        assertEquals(results.size() * samples, Files.readAllLines(nativeEvidence).size());
        DatabaseTelemetryJson.writeAggregate(nativeEvidence, root.resolve("taxonomy-native.json"));
      }
    }
  }

  @Test
  void recoversInterruptedTaxonomyProjection() throws Exception {
    assumeTrue(
        Boolean.getBoolean("consumer.profiles.taxonomy.recovery"), "opt-in PostgreSQL contract");
    var fixture = new ConsumerShapeBenchmark.Taxonomy();
    fixture.coordinate = 1000;
    ConsumerShapeBenchmark.TaxonomyRecovery recovery;
    try {
      fixture.setup();
      recovery = fixture.verifyInterruptedRebuild();
    } finally {
      fixture.close();
    }
    assertEquals(100, recovery.original());
    assertEquals(50, recovery.partial());
    assertEquals(50, recovery.partialAfterRestart());
    assertEquals(100, recovery.recovered());
    assertTrue(recovery.verifiedHits() > 0);
    Path root = Path.of(System.getProperty("consumer.profiles.output", "target/consumer-profiles"));
    Files.createDirectories(root);
    Files.writeString(
        root.resolve("taxonomy-recovery.json"),
        "{\"fixtureCommits\":1000,\"affectedWorkspaceCommits\":"
            + recovery.original()
            + ",\"partialProjections\":"
            + recovery.partial()
            + ",\"projectionsAfterProviderRestart\":"
            + recovery.partialAfterRestart()
            + ",\"recoveredProjections\":"
            + recovery.recovered()
            + ",\"gitVerifiedHits\":"
            + recovery.verifiedHits()
            + "}\n");
  }

  static void writePostgresRoundTripCalibration(Path root) throws Exception {
    String url =
        BenchmarkConnectionProperties.requiredSystemProperty(
            HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY);
    Properties connections = new Properties();
    try (var input = Files.newInputStream(Path.of(System.getProperty(
        BenchmarkConnectionProperties.CONNECTION_PROPERTIES_FILE_PROPERTY)))) {
      connections.load(input);
    }
    url = connections.getProperty("jgit.storage.benchmark.postgresql.measurement-url", url);
    String user =
        BenchmarkConnectionProperties.requiredSystemProperty(
            HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY);
    String password =
        BenchmarkConnectionProperties.requiredSystemProperty(
            HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY);
    long[] samples = new long[20];
    try (var connection = DriverManager.getConnection(url, user, password);
        var query = connection.prepareStatement("SELECT 1")) {
      query.setQueryTimeout(5);
      for (int i = -5; i < samples.length; i++) {
        long start = System.nanoTime();
        try (var result = query.executeQuery()) {
          assertTrue(result.next());
          assertEquals(1, result.getInt(1));
        }
        if (i >= 0) {
          samples[i] = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start);
        }
      }
    }
    long[] sorted = samples.clone();
    Arrays.sort(sorted);
    long medianMicros = (sorted[9] + sorted[10]) / 2;
    int requestedRtt = Integer.parseInt(System.getProperty("consumer.profiles.taxonomy.rttMillis", "0"));
    assertTrue(requestedRtt >= 0);
    assertTrue(medianMicros >= requestedRtt * 800L,
        "Measured PostgreSQL RTT is below the configured proxy delay");
    Files.writeString(
        root.resolve("taxonomy-rtt-calibration.json"),
        "{\"probe\":\"jdbc-select-one\",\"requestedRttMillis\":" + requestedRtt
            + ",\"sampleCount\":20,\"medianMicros\":"
            + medianMicros
            + ",\"p95Micros\":"
            + sorted[18]
            + ",\"samplesMicros\":" + Arrays.toString(samples) + "}\n");
  }

  private static String[] values(String parameter, String defaults) {
    return System.getProperty("consumer.profiles.audio." + parameter, defaults).split(",");
  }
}
