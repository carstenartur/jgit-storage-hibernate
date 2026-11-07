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
    if (!List.of("smoke", "large").contains(profile)) {
      throw new IllegalArgumentException("Expected smoke or large profile");
    }
    String selectedShape = System.getProperty("consumer.profiles.shape");
    var shapes = List.of("audio", "taxonomy", "sandbox");
    if (selectedShape != null && !shapes.contains(selectedShape)) {
      throw new IllegalArgumentException("Expected audio, taxonomy or sandbox shape");
    }
    Path root = Path.of(System.getProperty("consumer.profiles.output", "target/consumer-profiles"));
    Files.createDirectories(root);

    for (var shape : selectedShape == null ? shapes : List.of(selectedShape)) {
      String coordinates =
          switch (shape) {
            case "audio" -> profile.equals("smoke") ? "262144,2097152" : "16777216,134217728";
            case "taxonomy" -> profile.equals("smoke") ? "100" : "1000,10000,100000";
            case "sandbox" -> profile.equals("smoke") ? "10" : "250,1000";
            default -> throw new IllegalStateException(shape);
          };
      Path json = root.resolve(shape + ".json").toAbsolutePath();
      var options =
          new OptionsBuilder()
              .include(".*\\.ConsumerShapeBenchmark\\." + shape + ".*")
              .param("coordinate", coordinates.split(","))
              .warmupIterations(profile.equals("smoke") ? 0 : 1)
              .measurementIterations(profile.equals("smoke") ? 1 : 3)
              .forks(1)
              .jvmArgsAppend(
                  "-Xms256m",
                  profile.equals("large") && !shape.equals("sandbox") ? "-Xmx3g" : "-Xmx1g")
              .addProfiler(GCProfiler.class)
              .shouldFailOnError(true)
              .resultFormat(ResultFormatType.JSON)
              .result(json.toString())
              .output(root.resolve(shape + ".log").toAbsolutePath().toString());
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
                Set.of("taxonomyExactPathSummary", "taxonomyTextSummary", "taxonomyTextEntity");
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
        assertEquals(profile.equals("smoke") ? 1 : 3, measurements.size());
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
            case "taxonomy" -> assertTrue(metrics.get("resultCount").getScore() > 0);
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
