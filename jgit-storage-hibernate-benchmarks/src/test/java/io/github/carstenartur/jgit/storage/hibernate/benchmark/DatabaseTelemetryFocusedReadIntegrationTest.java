/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static io.github.carstenartur.jgit.storage.hibernate.benchmark.DatabaseTelemetryFocusedRead.FIXTURE_TABLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

class DatabaseTelemetryFocusedReadIntegrationTest {

  private static final String PROPERTY = "jgit.storage.benchmark.database-telemetry.focused-read.";
  private static final int PACKS = 32;
  private static final int CHUNKS_PER_PACK = 16;
  private static final int CHUNK_BYTES = 256;

  @TempDir Path temporaryDirectory;

  @Test
  void retainsOnlySanitizedActualReadEvidence() throws Exception {
    assumeTrue(Boolean.getBoolean(PROPERTY + "enabled"), "focused read plan is opt-in");
    var backend =
        DatabaseTelemetryFocusedRead.Backend.valueOf(
            System.getProperty(PROPERTY + "backend", "postgresql").toUpperCase(Locale.ROOT));
    String localUrl = System.getenv("JGIT_FOCUSED_READ_DISPOSABLE_JDBC_URL");
    if (localUrl != null && !localUrl.isBlank()) {
      verify(
          backend,
          localUrl,
          requiredEnvironment("JGIT_FOCUSED_READ_DISPOSABLE_JDBC_USER"),
          requiredEnvironment("JGIT_FOCUSED_READ_DISPOSABLE_JDBC_PASSWORD"));
      return;
    }

    try (JdbcDatabaseContainer<?> container =
        switch (backend) {
          case POSTGRESQL ->
              new PostgreSQLContainer("postgres:17.10-alpine")
                  .withDatabaseName("jgit_storage_native_telemetry")
                  .withUsername("telemetry")
                  .withPassword("telemetry-password");
          case SQLSERVER ->
              new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-CU20-ubuntu-22.04")
                  .acceptLicense();
        }) {
      container.start();
      verify(backend, container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }
  }

  private void verify(
      DatabaseTelemetryFocusedRead.Backend backend, String url, String user, String password)
      throws Exception {
    try (Connection fixture = backend.connect(url, user, password);
        Statement ddl = fixture.createStatement()) {
      ddl.setQueryTimeout(4);
      String binaryType =
          switch (backend) {
            case POSTGRESQL -> "bytea";
            case SQLSERVER -> "varbinary(256)";
          };
      ddl.execute(
          "CREATE TABLE "
              + FIXTURE_TABLE
              + " (pack_id bigint NOT NULL, chunk_index int NOT NULL,"
              + " chunk_data "
              + binaryType
              + " NOT NULL, chunk_size int NOT NULL,"
              + " PRIMARY KEY (pack_id, chunk_index))");
      try {
        seedFixture(fixture);
        ddl.execute(
            (backend == DatabaseTelemetryFocusedRead.Backend.POSTGRESQL
                    ? "ANALYZE "
                    : "UPDATE STATISTICS ")
                + FIXTURE_TABLE);

        var evidence = DatabaseTelemetryFocusedRead.capture(backend, url, user, password);
        assertNull(evidence.unsupported(), "controlled database must support the plan probe");
        assertTrue(evidence.serverVersion().matches("[0-9]+(?:\\.[0-9]+)+"));
        assertEquals(4, evidence.metrics().get("actualRows").intValueExact());
        String readMetric =
            backend == DatabaseTelemetryFocusedRead.Backend.POSTGRESQL
                ? "sharedHitBlocks"
                : "logicalReads";
        BigDecimal reads = evidence.metrics().get(readMetric);
        assertNotNull(reads, "missing " + readMetric);
        assertTrue(reads.signum() > 0, "expected a positive " + readMetric);
        if (backend == DatabaseTelemetryFocusedRead.Backend.SQLSERVER) {
          assertEquals(0, evidence.metrics().get("spillWarnings").intValueExact());
        }

        String json = evidence.json();
        for (String forbidden :
            new String[] {
              url, password, FIXTURE_TABLE, "chunk_index", "pack_id", "SELECT ", "jdbc:"
            }) {
          assertTrue(
              forbidden.isBlank() || !json.contains(forbidden),
              "focused read evidence contains a forbidden value");
        }
        Path output =
            Path.of(
                    System.getProperty(
                        PROPERTY + "output",
                        temporaryDirectory.resolve("focused-read-plan.json").toString()))
                .toAbsolutePath();
        Files.createDirectories(output.getParent());
        Files.writeString(output, json);
      } finally {
        ddl.execute("DROP TABLE " + FIXTURE_TABLE);
      }
    } catch (SQLException failure) {
      // Surefire and CI logs are part of the evidence path. Do not attach the raw JDBC cause.
      throw new AssertionError(
          "Focused read fixture failed: "
              + DatabaseTelemetryFocusedRead.failed(backend, failure).unsupported());
    }
  }

  private static void seedFixture(Connection connection) throws SQLException {
    try (var insert =
        connection.prepareStatement(
            "INSERT INTO "
                + FIXTURE_TABLE
                + " (pack_id, chunk_index, chunk_data, chunk_size) VALUES (?, ?, ?, ?)")) {
      insert.setQueryTimeout(4);
      byte[] payload = new byte[CHUNK_BYTES];
      for (long pack = 1; pack <= PACKS; pack++) {
        for (int chunk = 0; chunk < CHUNKS_PER_PACK; chunk++) {
          Arrays.fill(payload, (byte) (pack + chunk));
          insert.setLong(1, pack);
          insert.setInt(2, chunk);
          insert.setBytes(3, payload);
          insert.setInt(4, CHUNK_BYTES);
          insert.addBatch();
        }
      }
      insert.executeBatch();
    }
  }

  private static String requiredEnvironment(String name) {
    String value = System.getenv(name);
    if (value == null) {
      throw new IllegalArgumentException("Missing " + name);
    }
    return value;
  }
}
