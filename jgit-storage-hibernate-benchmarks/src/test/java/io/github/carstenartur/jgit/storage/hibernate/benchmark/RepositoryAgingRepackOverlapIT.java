/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.PackRepackResult;
import io.github.carstenartur.jgit.storage.hibernate.benchmark.RepositoryAgingBenchmark.RepackReadSnapshot;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One warm reader on an aged repository before and during a real compact repack.
 *
 * <p>This evidence run uses a dedicated, disposable database schema. The timed operation reads
 * main and both endpoint blob payloads. All samples are checked against the deterministic fixture
 * outside the timed interval. No worker holds or slows the repack via a progress monitor.
 */
@EnabledIfSystemProperty(
    named = "jgit.storage.benchmark.repository-aging.overlap.enabled", matches = "true")
class RepositoryAgingRepackOverlapIT {

  private static final int PUSHES = 300;
  private static final int REPEATS = 3;
  private static final int READ_PAUSE_MILLIS = 2;
  private static final int SAMPLE_LIMIT = 60_000;
  private static final long QUIET_NANOS = SECONDS.toNanos(1);
  private static final long CONCURRENT_TIMEOUT_NANOS = MINUTES.toNanos(2);
  private static final String OUTPUT_PROPERTY =
      "jgit.storage.benchmark.repository-aging.overlap.output";

  @Test
  @Timeout(value = 10, unit = MINUTES, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void measuresWarmReadsDuringUnpausedCompactRepack() throws Exception {
    String backend =
        System.getProperty(
            PerformanceInvestigationParameters.REPOSITORY_AGING_BACKEND_PROPERTY,
            HibernateRepositoryBenchmark.HSQLDB);
    Path output =
        Path.of(System.getProperty(OUTPUT_PROPERTY, "target/repository-aging-overlap"));
    switch (backend) {
      case HibernateRepositoryBenchmark.HSQLDB -> measure(backend, "embedded", output);
      case HibernateRepositoryBenchmark.POSTGRESQL, HibernateRepositoryBenchmark.POSTGRESQL_HIKARI ->
          withDatabase(
              backend,
              output,
              HibernateRepositoryBenchmark.POSTGRESQL_URL_PROPERTY,
              HibernateRepositoryBenchmark.POSTGRESQL_USER_PROPERTY,
              HibernateRepositoryBenchmark.POSTGRESQL_PASSWORD_PROPERTY);
      case PackStorageLayoutBenchmark.SQL_SERVER ->
          withDatabase(
              backend,
              output,
              PackStorageLayoutBenchmark.SQL_SERVER_URL_PROPERTY,
              PackStorageLayoutBenchmark.SQL_SERVER_USER_PROPERTY,
              PackStorageLayoutBenchmark.SQL_SERVER_PASSWORD_PROPERTY);
      default -> throw new IllegalArgumentException("Unsupported repack overlap backend " + backend);
    }
  }

  private static void withDatabase(
      String backend, Path output, String urlKey, String userKey, String passwordKey)
      throws Exception {
    if (System.getProperty(urlKey) != null
        || System.getProperty(PackStorageLayoutBenchmark.CONNECTION_PROPERTIES_FILE_PROPERTY)
            != null) {
      throw new IllegalArgumentException(
          "Repack overlap requires a fresh Testcontainers database. Clear external JDBC settings.");
    }
    try (JdbcDatabaseContainer<?> database =
        PackStorageLayoutBenchmark.SQL_SERVER.equals(backend)
            ? new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-CU20-ubuntu-22.04")
                .acceptLicense()
                .withUrlParam("socketTimeout", "30000")
            : new PostgreSQLContainer("postgres:17.10-alpine")
                .withDatabaseName("jgit_storage_repack_overlap")
                .withUrlParam("socketTimeout", "30")) {
      database.start();
      String oldUrl = System.setProperty(urlKey, database.getJdbcUrl());
      String oldUser = System.setProperty(userKey, database.getUsername());
      String oldPassword = System.setProperty(passwordKey, database.getPassword());
      try {
        measure(backend, "local-testcontainers", output);
      } finally {
        restoreProperty(urlKey, oldUrl);
        restoreProperty(userKey, oldUser);
        restoreProperty(passwordKey, oldPassword);
      }
    }
  }

  private static void restoreProperty(String key, String value) {
    if (value == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, value);
    }
  }

  private static void measure(String backend, String deployment, Path output) throws Exception {
    Files.createDirectories(output);
    try (BufferedWriter raw = Files.newBufferedWriter(output.resolve("repack-read-samples.csv"));
        BufferedWriter summary =
            Files.newBufferedWriter(output.resolve("repack-read-summary.csv"))) {
      raw.write("backend,deployment,pushes,repeat,phase,classification,start_ns,end_ns,"
          + "repack_start_ns,repack_end_ns\n");
      summary.write("backend,deployment,pushes,repeat,phase,samples,p50_ns,p95_ns,p99_ns,"
          + "packs_before,packs_after,repack_ns,collector_ns,collector_contained_samples\n");
      for (int repeat = 1; repeat <= REPEATS; repeat++) {
        RepositoryAgingBenchmark fixture = new RepositoryAgingBenchmark();
        fixture.backend = backend;
        fixture.deployment = deployment;
        fixture.pushes = PUSHES;
        fixture.maintenanceMode = RepositoryAgingBenchmark.NONE;
        fixture.cacheState = RepositoryAgingBenchmark.WARM;
        fixture.providerLifecycle = RepositoryAgingBenchmark.SAME_PROVIDER;
        fixture.evidenceRepeat = repeat;
        try {
          fixture.setupTrial();
          String coordinate = backend + "," + deployment + "," + PUSHES + "," + repeat;
          measureRepeat(fixture, coordinate, raw, summary);
          raw.flush();
          summary.flush();
        } finally {
          fixture.tearDownTrial();
        }
      }
    }
  }

  private static void measureRepeat(
      RepositoryAgingBenchmark fixture, String coordinate, BufferedWriter raw, BufferedWriter summary)
      throws Exception {
    RepackReadSnapshot expected = fixture.expectedRepackSnapshot();
    for (int warmup = 0; warmup < 25; warmup++) {
      verifySnapshot(expected, fixture.readRepackSnapshot());
    }
    long origin = System.nanoTime();
    List<Sample> baseline = new ArrayList<>();
    while (System.nanoTime() - origin < QUIET_NANOS) {
      baseline.add(read(fixture, expected, origin));
      Thread.sleep(READ_PAUSE_MILLIS);
    }

    FutureTask<Repack> task =
        new FutureTask<>(
            () -> {
              long start = System.nanoTime() - origin;
              PackRepackResult result = fixture.compactAgedRepository();
              return new Repack(start, System.nanoTime() - origin, result);
            });
    Thread worker = Thread.ofPlatform().daemon().name("repository-aging-repack").unstarted(task);
    List<Sample> concurrent = new ArrayList<>();
    long deadline = System.nanoTime() + CONCURRENT_TIMEOUT_NANOS;
    worker.start();
    Repack repack;
    try {
      while (!task.isDone() && System.nanoTime() - deadline < 0
          && concurrent.size() < SAMPLE_LIMIT) {
        concurrent.add(read(fixture, expected, origin));
        if (!task.isDone()) {
          Thread.sleep(READ_PAUSE_MILLIS);
        }
      }
      assertTrue(concurrent.size() < SAMPLE_LIMIT, "Concurrent read sample limit exceeded");
      repack = task.get(Math.max(0, deadline - System.nanoTime()), NANOSECONDS);
    } finally {
      task.cancel(true);
      worker.join(10_000);
      assertFalse(worker.isAlive(), "Repack worker did not stop");
    }

    assertTrue(repack.result().successful(), "Compact repack did not succeed");
    assertTrue(repack.result().packReduction() > 0, "Compact repack did not reduce active packs");
    verifySnapshot(expected, fixture.readRepackSnapshot());
    verifySnapshot(expected, fixture.reopenAndReadRepackSnapshot());

    assertFalse(baseline.isEmpty(), "No warm baseline samples");
    List<Sample> overlapping = concurrent.stream().filter(repack::overlaps).toList();
    assertTrue(overlapping.stream().anyMatch(repack::insideCollector),
        "No read finished within the guaranteed collector interval");
    for (Sample sample : baseline) {
      assertFalse(repack.overlaps(sample), "Baseline sample overlapped repack");
      writeSample(raw, coordinate, "baseline", "quiet", sample, repack);
    }
    for (Sample sample : concurrent) {
      String classification =
          repack.contains(sample) ? "contained" : repack.overlaps(sample) ? "boundary" : "outside";
      writeSample(raw, coordinate, "concurrent", classification, sample, repack);
    }
    writeSummary(summary, coordinate, "baseline", baseline, repack);
    writeSummary(summary, coordinate, "overlap", overlapping, repack);
  }

  private static Sample read(
      RepositoryAgingBenchmark fixture, RepackReadSnapshot expected, long origin)
      throws IOException {
    long start = System.nanoTime() - origin;
    RepackReadSnapshot actual = fixture.readRepackSnapshot();
    long end = System.nanoTime() - origin;
    verifySnapshot(expected, actual);
    return new Sample(start, end);
  }

  private static void verifySnapshot(RepackReadSnapshot expected, RepackReadSnapshot actual) {
    assertEquals(expected.main(), actual.main(), "main ref");
    assertArrayEquals(expected.oldest(), actual.oldest(), "oldest blob payload");
    assertArrayEquals(expected.newest(), actual.newest(), "newest blob payload");
  }

  private static void writeSample(
      BufferedWriter writer, String coordinate, String phase, String classification,
      Sample sample, Repack repack) throws IOException {
    writer.write(coordinate + "," + phase + "," + classification + "," + sample.start() + ","
        + sample.end() + "," + repack.start() + "," + repack.end());
    writer.newLine();
  }

  private static void writeSummary(
      BufferedWriter writer, String coordinate, String phase, List<Sample> samples, Repack repack)
      throws IOException {
    long[] durations = samples.stream().mapToLong(Sample::duration).sorted().toArray();
    writer.write(coordinate + "," + phase + "," + durations.length + "," + rank(durations, 50)
        + "," + rank(durations, 95) + "," + rank(durations, 99) + "," + repack.result().packsBefore()
        + "," + repack.result().packsAfter() + "," + (repack.end() - repack.start())
        + "," + repack.result().elapsedNanos() + ","
        + samples.stream().filter(repack::insideCollector).count());
    writer.newLine();
  }

  private static long rank(long[] sorted, int percentile) {
    return sorted[(sorted.length * percentile + 99) / 100 - 1];
  }

  private record Sample(long start, long end) {
    long duration() {
      return end - start;
    }
  }

  private record Repack(long start, long end, PackRepackResult result) {
    boolean overlaps(Sample sample) {
      return sample.start() < end && sample.end() > start;
    }

    boolean contains(Sample sample) {
      return overlaps(sample) && sample.start() >= start && sample.end() <= end;
    }

    boolean insideCollector(Sample sample) {
      // All non-collector work could be before or after the collector. Trim both ends.
      long otherWork = end - start - result.elapsedNanos();
      return contains(sample) && otherWork >= 0
          && sample.start() >= start + otherWork && sample.end() <= end - otherWork;
    }
  }
}
