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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Strict, credential-free JSON serialization for database-native telemetry. */
final class DatabaseTelemetryJson {

  static final int SCHEMA_VERSION = 1;

  private DatabaseTelemetryJson() {}

  static void appendNdjson(Path target, DatabaseTelemetryObservation observation)
      throws IOException {
    Path absolute = target.toAbsolutePath();
    Path parent = absolute.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    byte[] bytes = (observationJson(observation) + "\n").getBytes(StandardCharsets.UTF_8);
    try (FileChannel channel =
            FileChannel.open(
                absolute,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);
        FileLock ignored = channel.lock()) {
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
    }
  }

  static void writeAggregate(Path ndjson, Path target) throws IOException {
    List<String> observations = new ArrayList<>();
    if (Files.isRegularFile(ndjson)) {
      for (String line : Files.readAllLines(ndjson, StandardCharsets.UTF_8)) {
        String value = line.trim();
        if (value.isEmpty()) {
          continue;
        }
        if (!value.startsWith("{") || !value.endsWith("}")) {
          throw new IOException("Malformed database telemetry NDJSON record");
        }
        observations.add(value);
      }
    }

    StringBuilder json = new StringBuilder();
    json.append("{\n  \"schemaVersion\": ").append(SCHEMA_VERSION);
    json.append(",\n  \"observations\": [");
    for (int index = 0; index < observations.size(); index++) {
      json.append(index == 0 ? "\n    " : ",\n    ");
      json.append(observations.get(index));
    }
    if (!observations.isEmpty()) {
      json.append('\n');
    }
    json.append("  ]\n}\n");
    String value = json.toString();
    if (value.contains("NaN") || value.contains("Infinity")) {
      throw new IOException("Database telemetry contains non-standard JSON numbers");
    }
    Path absolute = target.toAbsolutePath();
    Path parent = absolute.getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    Files.writeString(absolute, value, StandardCharsets.UTF_8);
  }

  static String observationJson(DatabaseTelemetryObservation observation) {
    StringBuilder json = new StringBuilder();
    DatabaseTelemetryDelta telemetry = observation.telemetry();
    json.append('{');
    field(json, "coordinate", observation.coordinate(), false);
    field(json, "backend", telemetry.backend(), true);
    field(json, "enabled", telemetry.enabled(), true);
    field(json, "startedAt", telemetry.startedAt().toString(), true);
    field(json, "completedAt", telemetry.completedAt().toString(), true);
    field(json, "serverVersion", telemetry.serverVersion(), true);
    field(json, "counters", telemetry.counters(), true);
    field(json, "gauges", telemetry.gauges(), true);
    field(json, "metadata", telemetry.metadata(), true);
    field(json, "unsupported", telemetry.unsupported(), true);
    json.append('}');
    return json.toString();
  }

  private static void field(
      StringBuilder json, String name, Map<String, ?> value, boolean comma) {
    separator(json, comma);
    string(json, name);
    json.append(':');
    map(json, value);
  }

  private static void field(StringBuilder json, String name, String value, boolean comma) {
    separator(json, comma);
    string(json, name);
    json.append(':');
    string(json, value);
  }

  private static void field(StringBuilder json, String name, boolean value, boolean comma) {
    separator(json, comma);
    string(json, name);
    json.append(':').append(value);
  }

  private static void separator(StringBuilder json, boolean comma) {
    if (comma) {
      json.append(',');
    }
  }

  private static void map(StringBuilder json, Map<String, ?> value) {
    json.append('{');
    boolean comma = false;
    for (Map.Entry<String, ?> entry : value.entrySet()) {
      if (comma) {
        json.append(',');
      }
      string(json, entry.getKey());
      json.append(':');
      Object item = entry.getValue();
      if (item instanceof Number number) {
        json.append(number.longValue());
      } else if (item instanceof Boolean bool) {
        json.append(bool);
      } else {
        string(json, String.valueOf(item));
      }
      comma = true;
    }
    json.append('}');
  }

  private static void string(StringBuilder json, String value) {
    json.append('"');
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '"' -> json.append("\\\"");
        case '\\' -> json.append("\\\\");
        case '\b' -> json.append("\\b");
        case '\f' -> json.append("\\f");
        case '\n' -> json.append("\\n");
        case '\r' -> json.append("\\r");
        case '\t' -> json.append("\\t");
        default -> {
          if (character < 0x20) {
            json.append(String.format("\\u%04x", (int) character));
          } else {
            json.append(character);
          }
        }
      }
    }
    json.append('"');
  }
}
