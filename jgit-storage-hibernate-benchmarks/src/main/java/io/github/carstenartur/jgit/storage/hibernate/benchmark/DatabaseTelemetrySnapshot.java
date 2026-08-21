/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Immutable database-native telemetry captured at one point in time. */
record DatabaseTelemetrySnapshot(
    String backend,
    boolean enabled,
    Instant capturedAt,
    String serverVersion,
    Map<String, Long> counters,
    Map<String, Long> gauges,
    Map<String, String> metadata,
    Map<String, String> unsupported) {

  DatabaseTelemetrySnapshot {
    backend = requireNotBlank(backend, "backend");
    capturedAt = Objects.requireNonNull(capturedAt, "capturedAt");
    serverVersion = serverVersion == null ? "unknown" : serverVersion;
    counters = immutableLongMap(counters, "counters");
    gauges = immutableLongMap(gauges, "gauges");
    metadata = immutableStringMap(metadata, "metadata");
    unsupported = immutableStringMap(unsupported, "unsupported");
  }

  static DatabaseTelemetrySnapshot disabled(String backend, String reason) {
    return new DatabaseTelemetrySnapshot(
        backend,
        false,
        Instant.now(),
        "unknown",
        Map.of(),
        Map.of(),
        Map.of("mode", "disabled"),
        Map.of("collector", requireNotBlank(reason, "reason")));
  }

  DatabaseTelemetryDelta deltaTo(DatabaseTelemetrySnapshot after) {
    Objects.requireNonNull(after, "after");
    if (!backend.equals(after.backend)) {
      throw new IllegalArgumentException(
          "Cannot compare telemetry from " + backend + " and " + after.backend);
    }

    TreeMap<String, Long> deltas = new TreeMap<>();
    TreeMap<String, String> combinedUnsupported = new TreeMap<>(unsupported);
    combinedUnsupported.putAll(after.unsupported);
    for (Map.Entry<String, Long> entry : after.counters.entrySet()) {
      Long beforeValue = counters.get(entry.getKey());
      if (beforeValue == null) {
        combinedUnsupported.putIfAbsent(
            "counter." + entry.getKey(), "missing-before-snapshot");
        continue;
      }
      long afterValue = entry.getValue();
      if (afterValue < beforeValue) {
        combinedUnsupported.put("counter." + entry.getKey(), "counter-reset");
        continue;
      }
      deltas.put(entry.getKey(), afterValue - beforeValue);
    }
    for (String missingAfter : counters.keySet()) {
      if (!after.counters.containsKey(missingAfter)) {
        combinedUnsupported.putIfAbsent(
            "counter." + missingAfter, "missing-after-snapshot");
      }
    }

    TreeMap<String, String> combinedMetadata = new TreeMap<>(metadata);
    combinedMetadata.putAll(after.metadata);
    return new DatabaseTelemetryDelta(
        backend,
        enabled && after.enabled,
        capturedAt,
        after.capturedAt,
        after.serverVersion,
        deltas,
        after.gauges,
        combinedMetadata,
        combinedUnsupported);
  }

  private static Map<String, Long> immutableLongMap(Map<String, Long> value, String name) {
    Objects.requireNonNull(value, name);
    TreeMap<String, Long> result = new TreeMap<>();
    value.forEach(
        (key, item) -> {
          result.put(requireNotBlank(key, name + " key"), Objects.requireNonNull(item, key));
        });
    return Map.copyOf(result);
  }

  private static Map<String, String> immutableStringMap(
      Map<String, String> value, String name) {
    Objects.requireNonNull(value, name);
    TreeMap<String, String> result = new TreeMap<>();
    value.forEach(
        (key, item) ->
            result.put(
                requireNotBlank(key, name + " key"),
                requireNotBlank(item, name + " value for " + key)));
    return Map.copyOf(result);
  }

  private static String requireNotBlank(String value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}

/** Delta between two snapshots around one benchmark invocation. */
record DatabaseTelemetryDelta(
    String backend,
    boolean enabled,
    Instant startedAt,
    Instant completedAt,
    String serverVersion,
    Map<String, Long> counters,
    Map<String, Long> gauges,
    Map<String, String> metadata,
    Map<String, String> unsupported) {

  DatabaseTelemetryDelta {
    backend = Objects.requireNonNull(backend, "backend");
    startedAt = Objects.requireNonNull(startedAt, "startedAt");
    completedAt = Objects.requireNonNull(completedAt, "completedAt");
    serverVersion = Objects.requireNonNull(serverVersion, "serverVersion");
    counters = Map.copyOf(new TreeMap<>(Objects.requireNonNull(counters, "counters")));
    gauges = Map.copyOf(new TreeMap<>(Objects.requireNonNull(gauges, "gauges")));
    metadata = Map.copyOf(new TreeMap<>(Objects.requireNonNull(metadata, "metadata")));
    unsupported =
        Map.copyOf(new TreeMap<>(Objects.requireNonNull(unsupported, "unsupported")));
  }
}

/** One exact benchmark coordinate paired with its native database delta. */
record DatabaseTelemetryObservation(
    Map<String, String> coordinate, DatabaseTelemetryDelta telemetry) {

  DatabaseTelemetryObservation {
    coordinate =
        Map.copyOf(new TreeMap<>(Objects.requireNonNull(coordinate, "coordinate")));
    telemetry = Objects.requireNonNull(telemetry, "telemetry");
  }
}
