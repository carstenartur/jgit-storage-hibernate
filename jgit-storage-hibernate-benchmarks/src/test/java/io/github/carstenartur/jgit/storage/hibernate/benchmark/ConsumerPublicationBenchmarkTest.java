/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/** Explicit native manual follow-up; ordinary builds execute only the functional fixture tests. */
class ConsumerPublicationBenchmarkTest {
  @Test
  void measuresBoundedNativePublicationMatrix() throws Exception {
    assumeTrue(Boolean.getBoolean("consumer.publication.run"), "opt-in native publication benchmark");
    String key = BenchmarkConnectionProperties.CONNECTION_PROPERTIES_FILE_PROPERTY;
    String file = System.getProperty(key);
    assertNotNull(file, "A disposable PostgreSQL connection file is required");
    assertTrue(Files.isRegularFile(Path.of(file)));
    String profile = System.getProperty("consumer.publication.profile", "all");
    assertTrue(List.of("all", "taxonomy", "audio").contains(profile));
    Path root = Path.of(System.getProperty("consumer.publication.output", "target/consumer-publication"));
    Files.createDirectories(root);
    ConsumerProfileBenchmarkTest.writePostgresRoundTripCalibration(root);
    for (String shape : profile.equals("all") ? List.of("taxonomy", "audio") : List.of(profile)) {
      boolean taxonomy = shape.equals("taxonomy");
      String operation = taxonomy ? "taxonomyPublishDelta" : "audioPublishWithWal";
      Path nativeEvidence = root.resolve(shape + "-publication-native.ndjson").toAbsolutePath();
      Files.deleteIfExists(nativeEvidence);
      var options = new OptionsBuilder()
          .include(ConsumerPublicationBenchmark.class.getName() + "\\." + operation + "$")
          .forks(1).warmupIterations(1).measurementIterations(3)
          .warmupBatchSize(1).measurementBatchSize(1)
          .timeout(TimeValue.minutes(15)).shouldFailOnError(true)
          .addProfiler(GCProfiler.class)
          .jvmArgsAppend("-Xms256m", "-Xmx1g", "-D" + key + "=" + Path.of(file).toAbsolutePath(),
              "-D" + DatabaseTelemetryCollectors.OUTPUT_PROPERTY + "=" + nativeEvidence)
          .resultFormat(ResultFormatType.JSON).result(root.resolve(shape + "-publication.json").toString());
      if (taxonomy) options.param("coordinate", "1000").param("parallelism", "1", "4");
      else options.param("coordinate", "2097152", "16777216").param("analysisMiB", "32");
      String java = System.getProperty("consumer.profiles.java");
      if (java != null) options.jvm(java);
      var results = new Runner(options.build()).run();
      assertEquals(2, results.size());
      for (var result : results) {
        var iterations = result.getBenchmarkResults().stream()
            .flatMap(fork -> fork.getIterationResults().stream()).toList();
        assertEquals(3, iterations.size());
        for (var iteration : iterations) {
          var counters = iteration.getSecondaryResults();
          assertTrue(counters.containsKey("gc.alloc.rate.norm"));
          assertTrue(counters.containsKey("gc.count"));
          assertTrue(counters.get("preparedStatements").getScore() > 0);
          assertEquals(taxonomy ? 4.0 : 1.0, counters.get("workspacesPublished").getScore());
          if (taxonomy) {
            assertEquals(4.0, counters.get("commitsIndexed").getScore());
            assertEquals(Double.parseDouble(result.getParams().getParam("parallelism")),
                counters.get("publicationWorkers").getScore());
          }
          else {
            double bytes = Double.parseDouble(result.getParams().getParam("coordinate"));
            assertEquals(bytes, counters.get("payloadBytes").getScore());
            assertTrue(counters.get("databaseBytesWritten").getScore() >= bytes);
            assertEquals(32.0 * 1024 * 1024, counters.get("retainedAnalysisBytes").getScore());
          }
        }
      }
      assertEquals(6, Files.readAllLines(nativeEvidence).size());
      DatabaseTelemetryJson.writeAggregate(nativeEvidence, root.resolve(shape + "-publication-native.json"));
    }
  }
}
