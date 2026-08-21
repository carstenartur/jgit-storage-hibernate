/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.mssqlserver.MSSQLServerContainer;

class DatabaseNativeTelemetryIntegrationTest {

  private static final String ENABLED_PROPERTY =
      "jgit.storage.benchmark.database-telemetry.integration.enabled";
  private static final String BACKEND_PROPERTY =
      "jgit.storage.benchmark.database-telemetry.integration.backend";

  @TempDir Path temporaryDirectory;

  @Test
  void capturesNativeTelemetryWithoutLeakingConnectionDetails() throws Exception {
    assumeTrue(Boolean.getBoolean(ENABLED_PROPERTY), "native telemetry integration is opt-in");
    String backend = System.getProperty(BACKEND_PROPERTY, "postgresql");
    switch (backend) {
      case "postgresql" -> verifyPostgreSql();
      case "sqlserver" -> verifySqlServer();
      default -> throw new IllegalArgumentException("Unsupported telemetry backend " + backend);
    }
  }

  private void verifyPostgreSql() throws Exception {
    PostgreSQLContainer<?> container =
        new PostgreSQLContainer<>("postgres:17.10-alpine")
            .withDatabaseName("jgit_storage_native_telemetry")
            .withUsername("telemetry")
            .withPassword("telemetry-password");
    container.start();
    try {
      verify(
          "postgresql",
          container.getJdbcUrl(),
          container.getUsername(),
          container.getPassword(),
          "bytea",
          "postgresql.wal.insert_lsn_bytes",
          "postgresql.wal.bytes");
    } finally {
      container.stop();
    }
  }

  private void verifySqlServer() throws Exception {
    MSSQLServerContainer container =
        new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-CU20-ubuntu-22.04")
            .acceptLicense();
    container.start();
    try {
      verify(
          "sqlserver",
          container.getJdbcUrl(),
          container.getUsername(),
          container.getPassword(),
          "varbinary(max)",
          "sqlserver.io.log.bytes_written",
          "sqlserver.wait.writelog.time_ms");
    } finally {
      container.stop();
    }
  }

  private void verify(
      String backend,
      String jdbcUrl,
      String username,
      String password,
      String binaryType,
      String requiredCounter,
      String secondRequiredCounter)
      throws Exception {
    DatabaseTelemetryCollector collector =
        DatabaseTelemetryCollectors.create(backend, true, jdbcUrl, username, password);
    DatabaseTelemetrySnapshot before = collector.capture();

    try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        statement.execute("CREATE TABLE native_telemetry_probe (id bigint, payload " + binaryType + ")");
      }
      try (PreparedStatement insert =
          connection.prepareStatement(
              "INSERT INTO native_telemetry_probe (id, payload) VALUES (?, ?)")) {
        insert.setLong(1, 1L);
        insert.setBytes(2, deterministicPayload());
        insert.executeUpdate();
      }
      connection.commit();
      try (Statement statement = connection.createStatement()) {
        statement.executeQuery("SELECT COUNT(*) FROM native_telemetry_probe").close();
      }
    }

    DatabaseTelemetrySnapshot after = collector.capture();
    DatabaseTelemetryDelta delta = before.deltaTo(after);
    assertTrue(collector.enabled());
    assertTrue(after.enabled());
    assertFalse("unknown".equals(after.serverVersion()));
    assertFalse(after.unsupported().containsKey("collector.connection"));
    assertTrue(after.counters().containsKey(requiredCounter), after.unsupported().toString());
    assertTrue(
        after.counters().containsKey(secondRequiredCounter),
        after.unsupported().toString());
    assertTrue(delta.counters().containsKey(requiredCounter), delta.unsupported().toString());
    assertTrue(
        delta.counters().get(requiredCounter) > 0L,
        () -> "Expected positive " + requiredCounter + " delta but got " + delta.counters());

    Path ndjson = temporaryDirectory.resolve(backend + "-telemetry.ndjson");
    Path json = temporaryDirectory.resolve(backend + "-telemetry.json");
    DatabaseTelemetryJson.appendNdjson(
        ndjson,
        new DatabaseTelemetryObservation(
            Map.of("backend", backend, "operation", "integration-write"), delta));
    DatabaseTelemetryJson.writeAggregate(ndjson, json);
    String value = Files.readString(json);
    assertTrue(value.contains(requiredCounter));
    assertFalse(value.contains(jdbcUrl));
    assertFalse(value.contains("\"" + username + "\""));
    assertFalse(value.contains(password));
    assertFalse(value.contains("NaN"));
    assertFalse(value.contains("Infinity"));
  }

  private static byte[] deterministicPayload() {
    byte[] payload = new byte[1024 * 1024];
    int state = 0x54454c45;
    for (int index = 0; index < payload.length; index++) {
      state = state * 1664525 + 1013904223;
      payload[index] = (byte) (state >>> 24);
    }
    return payload;
  }
}
