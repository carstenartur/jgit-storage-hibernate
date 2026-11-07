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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;

class ConsumerProfileBenchmarkTest {
  @Test
  void runsAllConsumerShapesAndRetainsRawMeasurements() throws Exception {
    assumeTrue(Boolean.getBoolean("consumer.profiles.run"), "opt-in JMH run");
    String profile = System.getProperty("consumer.profiles.matrix", "smoke");
    boolean postgres = profile.equals("postgres-taxonomy");
    if (!List.of("smoke", "large", "postgres-taxonomy").contains(profile)) {
      throw new IllegalArgumentException("Expected smoke, large or postgres-taxonomy profile");
    }
    String selectedShape = System.getProperty("consumer.profiles.shape");
    var shapes = postgres ? List.of("taxonomy") : List.of("audio", "taxonomy", "sandbox");
    int samples = profile.equals("large") ? 3 : 1;
    if (selectedShape != null && !shapes.contains(selectedShape)) {
      throw new IllegalArgumentException("Expected audio, taxonomy or sandbox shape");
    }
    Path root = Path.of(System.getProperty("consumer.profiles.output", "target/consumer-profiles"));
    Files.createDirectories(root);

    for (var shape : selectedShape == null ? shapes : List.of(selectedShape)) {
      String coordinates =
          switch (shape) {
            case "audio" -> profile.equals("smoke") ? "262144,2097152" : "16777216,134217728";
            case "taxonomy" ->
                postgres ? "1000,10000" : profile.equals("smoke") ? "100" : "1000,10000,100000";
            case "sandbox" -> profile.equals("smoke") ? "10" : "250,1000";
            default -> throw new IllegalStateException(shape);
          };
      Path json = root.resolve(shape + ".json").toAbsolutePath();
      String heap = profile.equals("large") && !shape.equals("sandbox") ? "-Xmx3g" : "-Xmx1g";
      var options =
          new OptionsBuilder()
              .include(
                  ".*\\.ConsumerShapeBenchmark\\."
                      + (postgres ? "taxonomyProjectionRebuild$" : shape + ".*"))
              .param("coordinate", coordinates.split(","))
              .warmupIterations(profile.equals("large") ? 1 : 0)
              .measurementIterations(samples)
              .forks(1)
              .addProfiler(GCProfiler.class)
              .shouldFailOnError(true)
              .resultFormat(ResultFormatType.JSON)
              .result(json.toString())
              .output(root.resolve(shape + ".log").toAbsolutePath().toString());
      if (postgres) {
        String key = BenchmarkConnectionProperties.CONNECTION_PROPERTIES_FILE_PROPERTY;
        String file = System.getProperty(key);
        if (file == null || !Files.isRegularFile(Path.of(file))) {
          throw new IllegalArgumentException(
              "A disposable PostgreSQL connection properties file is required");
        }
        options.jvmArgsAppend("-Xms256m", heap, "-D" + key + "=" + Path.of(file).toAbsolutePath());
      } else {
        options.jvmArgsAppend("-Xms256m", heap).exclude(".*\\.taxonomyProjectionRebuild$");
      }
      int variants = 1;
      if (shape.equals("audio")) {
        boolean large = profile.equals("large");
        String[] writers = values("writer", large ? "stateful,stateless" : "auto");
        String[] pressure = values("pressure", large ? "idle,bounded" : "idle");
        String[] batches = values("chunkBatch", "16");
        options.param("writer", writers).param("pressure", pressure).param("chunkBatch", batches);
        variants = writers.length * pressure.length * batches.length;
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
                    ? Set.of("taxonomyProjectionRebuild")
                    : Set.of(
                        "taxonomyExactPathSummary", "taxonomyTextSummary", "taxonomyTextEntity");
            case "sandbox" -> Set.of("sandboxAnalyzeBodyEdit", "sandboxRebuildSnapshot");
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
                  metrics.get("payloadBytes").getScore());
              assertEquals(
                  result.getParams().getParam("pressure").equals("bounded")
                      ? 32.0 * 1024 * 1024
                      : 0,
                  metrics.get("retainedAnalysisBytes").getScore());
              if (result.getParams().getBenchmark().endsWith("audioPublish")) {
                assertTrue(metrics.get("databaseBytesWritten").getScore() > 0);
              }
            }
            case "taxonomy" -> {
              if (postgres) {
                assertEquals(
                    Double.parseDouble(result.getParams().getParam("coordinate")),
                    metrics.get("resultCount").getScore());
              } else {
                assertTrue(metrics.get("resultCount").getScore() > 0);
              }
            }
            case "sandbox" -> {
              int files =
                  result.getParams().getBenchmark().endsWith("sandboxAnalyzeBodyEdit")
                      ? 1
                      : Integer.parseInt(result.getParams().getParam("coordinate")) + 1;
              assertEquals((double) files, metrics.get("javaFilesAnalyzed").getScore());
            }
          }
        }
      }
      assertTrue(results.stream().allMatch(result -> result.getPrimaryResult().getScore() > 0));
      assertTrue(Files.size(json) > 2);
    }
  }

  private static String[] values(String parameter, String defaults) {
    return System.getProperty("consumer.profiles.audio." + parameter, defaults).split(",");
  }
}
