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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

/** Retains pack-layout evidence at calibrated 5/20/50-ms PostgreSQL round-trip times. */
class PackStorageLayoutNetworkBenchmarkTest {

  private static final String ENABLED_PROPERTY =
      "jgit.storage.benchmark.pack-layout.network.enabled";
  private static final String PROFILE_PROPERTY =
      "jgit.storage.benchmark.pack-layout.network.profile";
  private static final int POSTGRESQL_PORT = 5432;
  private static final int TOXIPROXY_DATA_PORT = 8666;
  private static final String POSTGRESQL_ALIAS = "postgresql-pack-layout-network";
  private static final String PROXY_NAME = "jgit-postgresql-pack-layout-network";
  private static final String TOXIPROXY_IMAGE = "ghcr.io/shopify/toxiproxy:2.12.0";

  @Test
  void writesCalibratedNetworkLayoutEvidence() throws Exception {
    assumeTrue(Boolean.getBoolean(ENABLED_PROPERTY), "pack layout network benchmark is opt-in");
    String profile = System.getProperty(PROFILE_PROPERTY, "smoke").toLowerCase(Locale.ROOT);
    int[] roundTripTimes = roundTripTimes(profile);

    Path resultFile =
        Path.of(
                System.getProperty(
                    "benchmark.resultFile",
                    "target/pack-storage-layout-network/jmh-result.json"))
            .toAbsolutePath();
    Files.createDirectories(resultFile.getParent());
    Path rawDirectory = resultFile.getParent().resolve("raw");
    Files.createDirectories(rawDirectory);
    Path calibrationFile = resultFile.resolveSibling("network-rtt-calibration.csv");

    List<Path> rawResults = new ArrayList<>();
    List<Path> rawOutputs = new ArrayList<>();
    List<LatencyCalibration> calibrations = new ArrayList<>();
    int resultCount = 0;

    try (Network network = Network.newNetwork();
        PostgreSQLContainer<?> postgresql =
            new PostgreSQLContainer<>("postgres:17.10-alpine")
                .withDatabaseName("jgit_storage_pack_layout_network")
                .withUsername("benchmark")
                .withPassword("benchmark")
                .withNetwork(network)
                .withNetworkAliases(POSTGRESQL_ALIAS);
        ToxiproxyContainer toxiproxy =
            new ToxiproxyContainer(TOXIPROXY_IMAGE)
                .withNetwork(network)
                .dependsOn(postgresql)) {
      postgresql.start();
      toxiproxy.start();
      ToxiproxyClient client =
          new ToxiproxyClient(toxiproxy.getHost(), toxiproxy.getControlPort());
      recreateProxy(client);
      String proxiedJdbcUrl = proxiedJdbcUrl(toxiproxy, postgresql);

      try {
        for (int requestedRttMillis : roundTripTimes) {
          configureLatency(client, requestedRttMillis);
          LatencyCalibration calibration =
              calibrate(
                  proxiedJdbcUrl,
                  postgresql.getUsername(),
                  postgresql.getPassword(),
                  requestedRttMillis);
          assertLatencyWasApplied(calibration);
          calibrations.add(calibration);

          Path rawResult =
              rawDirectory.resolve("rtt-" + requestedRttMillis + "ms-jmh-result.json");
          Path rawOutput =
              rawDirectory.resolve("rtt-" + requestedRttMillis + "ms-jmh-output.txt");
          Collection<RunResult> results =
              new Runner(
                      options(
                          profile,
                          requestedRttMillis,
                          proxiedJdbcUrl,
                          postgresql.getUsername(),
                          postgresql.getPassword(),
                          rawResult,
                          rawOutput))
                  .run();
          assertFalse(results.isEmpty(), "RTT " + requestedRttMillis + " ms produced no results");
          resultCount += results.size();
          rawResults.add(rawResult);
          rawOutputs.add(rawOutput);
        }
      } finally {
        client.reset();
      }
    }

    mergeJsonArrays(rawResults, resultFile);
    mergeTextOutputs(rawOutputs, resultFile.resolveSibling("jmh-output.txt"));
    writeCalibrations(calibrations, calibrationFile);
    assertTrue(resultCount > 0);
    assertTrue(Files.isRegularFile(resultFile));
    assertTrue(Files.size(resultFile) > 2);
    assertTrue(Files.isRegularFile(calibrationFile));
  }

  private static Options options(
      String profile,
      int requestedRttMillis,
      String jdbcUrl,
      String username,
      String password,
      Path resultFile,
      Path outputFile) {
    boolean smoke = "smoke".equals(profile);
    return new OptionsBuilder()
        .include(PackStorageLayoutBenchmark.class.getName())
        .param("backend", HibernateRepositoryBenchmark.POSTGRESQL)
        .param(
            "operation",
            smoke
                ? new String[] {
                  PackStorageLayoutBenchmark.WRITE,
                  PackStorageLayoutBenchmark.SEQUENTIAL_READ,
                  PackStorageLayoutBenchmark.RANDOM_READ
                }
                : new String[] {
                  PackStorageLayoutBenchmark.WRITE,
                  PackStorageLayoutBenchmark.SEQUENTIAL_READ,
                  PackStorageLayoutBenchmark.SHORT_READ,
                  PackStorageLayoutBenchmark.RANDOM_READ
                })
        .param("payloadKiB", smoke ? new String[] {"1024"} : new String[] {"16384"})
        .param(
            "chunkKiB",
            smoke
                ? new String[] {"1024", "4096"}
                : new String[] {"256", "1024", "2048", "4096"})
        .param("inlineKiB", "256")
        .param("retainedMiB", "16")
        .param("readAheadKiB", "1024")
        .param("deployment", "postgresql-rtt-" + requestedRttMillis + "ms")
        .threads(1)
        .forks(1)
        .warmupIterations(smoke ? 0 : 1)
        .warmupTime(TimeValue.milliseconds(smoke ? 50 : 200))
        .measurementIterations(smoke ? 1 : 2)
        .measurementTime(TimeValue.milliseconds(smoke ? 100 : 300))
        .addProfiler(GCProfiler.class)
        .shouldFailOnError(true)
        .resultFormat(ResultFormatType.JSON)
        .result(resultFile.toString())
        .output(outputFile.toString())
        .jvmArgsAppend(
            "-Xms512m",
            smoke ? "-Xmx1536m" : "-Xmx2g",
            RepositoryBackendBenchmarkIT.systemProperty(
                HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY, jdbcUrl),
            RepositoryBackendBenchmarkIT.systemProperty(
                HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY, username),
            RepositoryBackendBenchmarkIT.systemProperty(
                HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY, password))
        .build();
  }

  private static int[] roundTripTimes(String profile) {
    return switch (profile) {
      case "smoke" -> new int[] {5};
      case "full" -> new int[] {5, 20, 50};
      default ->
          throw new IllegalArgumentException(
              PROFILE_PROPERTY + " must be smoke or full but was " + profile);
    };
  }

  private static void recreateProxy(ToxiproxyClient client) throws Exception {
    client.reset();
    Proxy existing = client.getProxyOrNull(PROXY_NAME);
    if (existing != null) {
      existing.delete();
    }
    client.createProxy(
        PROXY_NAME,
        "0.0.0.0:" + TOXIPROXY_DATA_PORT,
        POSTGRESQL_ALIAS + ":" + POSTGRESQL_PORT);
  }

  private static void configureLatency(ToxiproxyClient client, int requestedRttMillis)
      throws Exception {
    client.reset();
    Proxy proxy = client.getProxy(PROXY_NAME);
    long upstreamMillis = requestedRttMillis / 2L;
    long downstreamMillis = requestedRttMillis - upstreamMillis;
    if (upstreamMillis > 0) {
      proxy.toxics().latency("rtt-upstream", ToxicDirection.UPSTREAM, upstreamMillis);
    }
    if (downstreamMillis > 0) {
      proxy.toxics().latency("rtt-downstream", ToxicDirection.DOWNSTREAM, downstreamMillis);
    }
  }

  private static String proxiedJdbcUrl(
      ToxiproxyContainer toxiproxy, PostgreSQLContainer<?> postgresql) {
    return "jdbc:postgresql://"
        + toxiproxy.getHost()
        + ":"
        + toxiproxy.getMappedPort(TOXIPROXY_DATA_PORT)
        + "/"
        + postgresql.getDatabaseName();
  }

  private static LatencyCalibration calibrate(
      String jdbcUrl,
      String username,
      String password,
      int requestedRttMillis)
      throws Exception {
    long[] samples = new long[7];
    try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
        Statement statement = connection.createStatement()) {
      executeCalibrationQuery(statement);
      executeCalibrationQuery(statement);
      for (int index = 0; index < samples.length; index++) {
        long started = System.nanoTime();
        executeCalibrationQuery(statement);
        samples[index] = System.nanoTime() - started;
      }
    }
    Arrays.sort(samples);
    return new LatencyCalibration(
        requestedRttMillis,
        samples[samples.length / 2],
        samples[0],
        samples[samples.length - 1]);
  }

  private static void executeCalibrationQuery(Statement statement) throws Exception {
    try (ResultSet result = statement.executeQuery("SELECT 1")) {
      assertTrue(result.next(), "PostgreSQL calibration query returned no row");
      assertEquals(1, result.getInt(1), "Unexpected PostgreSQL calibration value");
      assertFalse(result.next(), "PostgreSQL calibration query returned extra rows");
    }
  }

  private static void assertLatencyWasApplied(LatencyCalibration calibration) {
    double minimumExpectedMillis = calibration.requestedRttMillis() * 0.60;
    assertTrue(
        calibration.medianMillis() >= minimumExpectedMillis,
        () ->
            "Toxiproxy RTT calibration was too small: requested="
                + calibration.requestedRttMillis()
                + " ms, median="
                + String.format(Locale.ROOT, "%.3f", calibration.medianMillis())
                + " ms");
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

  private static void writeCalibrations(
      List<LatencyCalibration> calibrations, Path target) throws Exception {
    StringBuilder csv =
        new StringBuilder("requested_rtt_ms,median_select_ms,min_select_ms,max_select_ms\n");
    for (LatencyCalibration calibration : calibrations) {
      csv.append(
          String.format(
              Locale.ROOT,
              "%d,%.3f,%.3f,%.3f%n",
              calibration.requestedRttMillis(),
              calibration.medianMillis(),
              calibration.minMillis(),
              calibration.maxMillis()));
    }
    Files.writeString(target, csv);
  }

  private record LatencyCalibration(
      int requestedRttMillis, long medianNanos, long minNanos, long maxNanos) {

    private double medianMillis() {
      return medianNanos / (double) TimeUnit.MILLISECONDS.toNanos(1);
    }

    private double minMillis() {
      return minNanos / (double) TimeUnit.MILLISECONDS.toNanos(1);
    }

    private double maxMillis() {
      return maxNanos / (double) TimeUnit.MILLISECONDS.toNanos(1);
    }
  }
}
