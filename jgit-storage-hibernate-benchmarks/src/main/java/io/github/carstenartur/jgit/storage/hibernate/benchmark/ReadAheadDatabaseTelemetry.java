/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.infra.IterationParams;
import org.openjdk.jmh.runner.IterationType;

/** Native telemetry for the pack-channel benchmark, whose channel is package private. */
public final class ReadAheadDatabaseTelemetry implements AutoCloseable {

  private final DatabaseTelemetryCollector collector;
  private final Path output;
  private final String method;
  private final String backend;
  private final String readAheadChunks;
  private DatabaseTelemetrySnapshot before;
  private int measurementInvocation;

  private ReadAheadDatabaseTelemetry(
      DatabaseTelemetryCollector collector, Path output, BenchmarkParams params) {
    this.collector = collector;
    this.output = output;
    String benchmark = params.getBenchmark();
    method = benchmark.substring(benchmark.lastIndexOf('.') + 1);
    backend = params.getParam("backend");
    readAheadChunks = params.getParam("readAheadChunks");
  }

  /** Create the trial recorder for PostgreSQL when native telemetry is explicitly enabled. */
  public static ReadAheadDatabaseTelemetry create(BenchmarkParams params, Properties jdbc) {
    String backend = params.getParam("backend");
    if (!Boolean.getBoolean(DatabaseTelemetryCollectors.ENABLED_PROPERTY)
        || !("postgresql".equals(backend) || "postgresql-hikari".equals(backend))) {
      return null;
    }
    if (params.getMode() != Mode.SingleShotTime
        || params.getThreads() != 1
        || params.getForks() != 1
        || params.getMeasurement().getBatchSize() != 1) {
      throw new IllegalArgumentException(
          "Read-ahead telemetry requires one fork, one thread, and one operation per measurement");
    }
    String value = System.getProperty(DatabaseTelemetryCollectors.OUTPUT_PROPERTY);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "Missing benchmark system property " + DatabaseTelemetryCollectors.OUTPUT_PROPERTY);
    }
    DatabaseTelemetryCollector collector =
        DatabaseTelemetryCollectors.create(
            "postgresql",
            true,
            jdbc.getProperty("hibernate.connection.url"),
            jdbc.getProperty("hibernate.connection.username"),
            jdbc.getProperty("hibernate.connection.password"));
    return new ReadAheadDatabaseTelemetry(collector, Path.of(value), params);
  }

  /** Capture only measured invocations, after trial fixture creation. */
  public void start(IterationParams iteration) {
    if (iteration.getType() == IterationType.MEASUREMENT) {
      before = collector.capture();
    }
  }

  /** Retain the native delta before the fixture is dropped. */
  public void finish() throws IOException {
    if (before == null) {
      return;
    }
    DatabaseTelemetrySnapshot after = collector.capture();
    DatabaseTelemetrySnapshot start = before;
    before = null;
    DatabaseTelemetryJson.appendNdjson(
        output,
        new DatabaseTelemetryObservation(
            Map.of(
                "benchmarkMethod", method,
                "backend", backend,
                "readAheadChunks", readAheadChunks,
                "measurementInvocation", Integer.toString(++measurementInvocation)),
            start.deltaTo(after)));
  }

  @Override
  public void close() {
    collector.close();
  }
}
