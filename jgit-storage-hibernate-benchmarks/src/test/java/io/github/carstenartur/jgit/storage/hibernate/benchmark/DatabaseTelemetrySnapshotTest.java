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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTelemetrySnapshotTest {

  @TempDir Path temporaryDirectory;

  @Test
  void computesCounterDeltasAndMarksResetsFailClosed() {
    DatabaseTelemetrySnapshot before =
        snapshot(
            Instant.parse("2026-08-21T10:00:00Z"),
            Map.of("wal.bytes", 100L, "io.reads", 20L, "reset", 9L),
            Map.of());
    DatabaseTelemetrySnapshot after =
        snapshot(
            Instant.parse("2026-08-21T10:00:01Z"),
            Map.of("wal.bytes", 164L, "io.reads", 23L, "reset", 2L),
            Map.of("active.log.bytes", 4096L));

    DatabaseTelemetryDelta delta = before.deltaTo(after);

    assertEquals(64L, delta.counters().get("wal.bytes"));
    assertEquals(3L, delta.counters().get("io.reads"));
    assertFalse(delta.counters().containsKey("reset"));
    assertEquals("counter-reset", delta.unsupported().get("counter.reset"));
    assertEquals(4096L, delta.gauges().get("active.log.bytes"));
  }

  @Test
  void disabledCollectorHasNoDatabaseSideEffectContract() {
    DatabaseTelemetryCollector collector =
        DatabaseTelemetryCollectors.disabled("hsqldb", "unsupported-backend");

    DatabaseTelemetrySnapshot snapshot = collector.capture();

    assertFalse(collector.enabled());
    assertFalse(snapshot.enabled());
    assertTrue(snapshot.counters().isEmpty());
    assertEquals("unsupported-backend", snapshot.unsupported().get("collector"));
  }

  @Test
  void strictJsonContainsNoCredentialsOrNonStandardNumbers() throws Exception {
    String jdbcUrl = "jdbc:missing://secret-host/private-database";
    String username = "secret-user";
    String password = "secret-password";
    DatabaseTelemetryCollector collector =
        DatabaseTelemetryCollectors.create(
            "postgresql", true, jdbcUrl, username, password);
    DatabaseTelemetrySnapshot before = collector.capture();
    DatabaseTelemetrySnapshot after = collector.capture();
    DatabaseTelemetryObservation observation =
        new DatabaseTelemetryObservation(
            Map.of("backend", "postgresql", "operation", "write"),
            before.deltaTo(after));
    Path ndjson = temporaryDirectory.resolve("telemetry.ndjson");
    Path json = temporaryDirectory.resolve("telemetry.json");

    DatabaseTelemetryJson.appendNdjson(ndjson, observation);
    DatabaseTelemetryJson.writeAggregate(ndjson, json);

    String value = Files.readString(json);
    assertTrue(value.contains("\"schemaVersion\": 1"));
    assertTrue(value.contains("collector.connection"));
    assertFalse(value.contains(jdbcUrl));
    assertFalse(value.contains(username));
    assertFalse(value.contains(password));
    assertFalse(value.contains("NaN"));
    assertFalse(value.contains("Infinity"));
  }

  @Test
  void missingCounterOnEitherSideRemainsExplicit() {
    DatabaseTelemetrySnapshot before =
        snapshot(
            Instant.parse("2026-08-21T10:00:00Z"),
            Map.of("before-only", 1L),
            Map.of());
    DatabaseTelemetrySnapshot after =
        snapshot(
            Instant.parse("2026-08-21T10:00:01Z"),
            Map.of("after-only", 2L),
            Map.of());

    DatabaseTelemetryDelta delta = before.deltaTo(after);

    assertEquals(
        "missing-before-snapshot",
        delta.unsupported().get("counter.after-only"));
    assertEquals(
        "missing-after-snapshot",
        delta.unsupported().get("counter.before-only"));
  }

  private static DatabaseTelemetrySnapshot snapshot(
      Instant capturedAt, Map<String, Long> counters, Map<String, Long> gauges) {
    return new DatabaseTelemetrySnapshot(
        "postgresql",
        true,
        capturedAt,
        "17.10",
        counters,
        gauges,
        Map.of("scope", "test"),
        Map.of());
  }
}
