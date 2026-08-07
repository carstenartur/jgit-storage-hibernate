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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.toxiproxy.ToxiproxyContainer;

/** Selects a bounded production chunk batch size under meaningful PostgreSQL network RTT. */
@Testcontainers(disabledWithoutDocker = true)
class NetworkChunkBatchSizeBenchmarkIT {

  private static final int POSTGRESQL_PORT = 5432;
  private static final int TOXIPROXY_DATA_PORT = 8666;
  private static final String POSTGRESQL_ALIAS = "postgresql-chunk-batch-size";
  private static final String PROXY_NAME = "jgit-postgresql-chunk-batch-size";
  private static final String TOXIPROXY_IMAGE = "ghcr.io/shopify/toxiproxy:2.12.0";
  private static final int[] REALISTIC_RTT_MILLIS = {10, 20, 50};
  private static final String[] CHUNK_BATCH_SIZES = {"8", "16", "32", "50"};
  private static final String PAYLOAD_MIB = "48";

  @Container
  static final PostgreSQLContainer<?> POSTGRESQL =
      new PostgreSQLContainer<>("postgres:17.10-alpine")
          .withDatabaseName("jgit_storage_chunk_batch_size")
          .withUsername("benchmark")
          .withPassword("benchmark")
          .withNetwork(Network.SHARED)
          .withNetworkAliases(POSTGRESQL_ALIAS);

  @Container
  static final ToxiproxyContainer TOXIPROXY =
      new ToxiproxyContainer(TOXIPROXY_IMAGE)
          .withNetwork(Network.SHARED)
          .dependsOn(POSTGRESQL);

  @Test
  void selectsBoundedChunkBatchSizeAcrossRealisticRoundTripTimes() throws Exception {
    ToxiproxyClient client =
        new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort());
    recreateProxy(client);

    String proxiedJdbcUrl = proxiedJdbcUrl();
    Path resultFile =
        Path.of(
                System.getProperty(
                    "benchmark.resultFile",
                    "target/network-batch-size/network-batch-size-jmh-result.json"))
            .toAbsolutePath();
    Files.createDirectories(resultFile.getParent());
    Path rawDirectory = resultFile.getParent().resolve("per-rtt");
    Files.createDirectories(rawDirectory);
    Path outputFile = resultFile.resolveSibling("network-batch-size-jmh-output.txt");
    Path calibrationFile = resultFile.resolveSibling("network-rtt-calibration.csv");

    List<Path> rawResults = new ArrayList<>();
    List<Path> rawOutputs = new ArrayList<>();
    List<RunResult> allResults = new ArrayList<>();
    List<LatencyCalibration> calibrations = new ArrayList<>();

    try {
      for (int requestedRttMillis : REALISTIC_RTT_MILLIS) {
        configureLatency(client, requestedRttMillis);
        LatencyCalibration calibration = calibrate(proxiedJdbcUrl, requestedRttMillis);
        calibrations.add(calibration);
        assertLatencyWasApplied(calibration);

        Path rawResult =
            rawDirectory.resolve("rtt-" + requestedRttMillis + "ms-jmh-result.json");
        Path rawOutput =
            rawDirectory.resolve("rtt-" + requestedRttMillis + "ms-jmh-output.txt");
        Collection<RunResult> results =
            new Runner(options(proxiedJdbcUrl, requestedRttMillis, rawResult, rawOutput)).run();

        assertEquals(
            CHUNK_BATCH_SIZES.length,
            results.size(),
            "Every chunk batch size must produce one result for RTT "
                + requestedRttMillis
                + " ms");
        rawResults.add(rawResult);
        rawOutputs.add(rawOutput);
        allResults.addAll(results);
      }
    } finally {
      client.reset();
    }

    mergeJsonArrays(rawResults, resultFile);
    mergeTextOutputs(rawOutputs, outputFile);
    writeCalibrations(calibrations, calibrationFile);

    assertEquals(CHUNK_BATCH_SIZES.length * REALISTIC_RTT_MILLIS.length, allResults.size());
    assertEquals(
        Set.copyOf(Arrays.asList(CHUNK_BATCH_SIZES)),
        allResults.stream()
            .map(result -> result.getParams().getParam("chunkBatchSize"))
            .collect(Collectors.toSet()));
    assertEquals(
        Arrays.stream(REALISTIC_RTT_MILLIS)
            .mapToObj(NetworkChunkBatchSizeBenchmarkIT::deploymentLabel)
            .collect(Collectors.toSet()),
        allResults.stream()
            .map(result -> result.getParams().getParam("deployment"))
            .collect(Collectors.toSet()));
    assertTrue(
        allResults.stream().allMatch(result -> result.getPrimaryResult().getScore() > 0.0),
        "Every measured publication must have a positive elapsed time");
    assertTrue(Files.isRegularFile(resultFile), "Combined JMH JSON was not written");
    assertTrue(Files.size(resultFile) > 2, "Combined JMH JSON is empty");
    assertTrue(Files.isRegularFile(outputFile), "Combined JMH output was not written");
    assertTrue(Files.isRegularFile(calibrationFile), "RTT calibration CSV was not written");
  }

  private static Options options(
      String proxiedJdbcUrl,
      int requestedRttMillis,
      Path resultFile,
      Path outputFile) {
    return new OptionsBuilder()
        .include(LargePackJdbcBatchBenchmark.class.getName())
        .param("writeMode", LargePackJdbcBatchBenchmark.STATEFUL_BATCHING)
        .param("backend", HibernateRepositoryBenchmark.POSTGRESQL_HIKARI)
        .param("payloadMiB", PAYLOAD_MIB)
        .param("chunkBatchSize", CHUNK_BATCH_SIZES)
        .param("deployment", deploymentLabel(requestedRttMillis))
        .warmupIterations(1)
        .measurementIterations(3)
        .forks(1)
        .addProfiler(GCProfiler.class)
        .shouldFailOnError(true)
        .resultFormat(ResultFormatType.JSON)
        .result(resultFile.toString())
        .output(outputFile.toString())
        .jvmArgsAppend(
            "-Xms1g",
            "-Xmx2g",
            RepositoryBackendBenchmarkIT.systemProperty(
                HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY, proxiedJdbcUrl),
            RepositoryBackendBenchmarkIT.systemProperty(
                HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY,
                POSTGRESQL.getUsername()),
            RepositoryBackendBenchmarkIT.systemProperty(
                HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY,
                POSTGRESQL.getPassword()))
        .build();
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

  private static LatencyCalibration calibrate(String jdbcUrl, int requestedRttMillis)
      throws Exception {
    long[] samples = new long[7];
    try (Connection connection =
            DriverManager.getConnection(
                jdbcUrl, POSTGRESQL.getUsername(), POSTGRESQL.getPassword());
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

  private static String proxiedJdbcUrl() {
    return "jdbc:postgresql://"
        + TOXIPROXY.getHost()
        + ":"
        + TOXIPROXY.getMappedPort(TOXIPROXY_DATA_PORT)
        + "/"
        + POSTGRESQL.getDatabaseName();
  }

  private static String deploymentLabel(int requestedRttMillis) {
    return "toxiproxy-chunk-batch-rtt-" + requestedRttMillis + "ms";
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
