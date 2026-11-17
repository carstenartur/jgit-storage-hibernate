/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search;

import com.github.dockerjava.api.command.InspectContainerResponse;
import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import io.github.carstenartur.jgit.storage.hibernate.search.schema.SearchSchemaMigrations;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Explicitly selected by the history scroll/export workflow. A missing Docker daemon fails the run. */
class HistoryScrollExportBenchmarkIT {
  private static final String POSTGRES_START_TIME =
      "select cast(extract(epoch from pg_postmaster_start_time()) * 1000 as bigint)";
  private static final String SQL_SERVER_START_TIME =
      "select datediff_big(millisecond, convert(datetime2, '1970-01-01', 126), sqlserver_start_time)"
          + " from sys.dm_os_sys_info";

  @TempDir Path directory;

  @Test
  void comparesScrollWithOffsetOnTheSelectedDatabase() throws IOException {
    String backend = System.getProperty("benchmark.scroll.backend", "postgresql");
    switch (backend) {
      case "postgresql" -> run(
          new PostgreSQLContainer("postgres:17.10-alpine") {
            @Override
            public InspectContainerResponse getContainerInfo() {
              return getCurrentContainerInfo();
            }
          }.withDatabaseName("scroll_benchmark"),
          CoreSchemaMigrations.POSTGRESQL_LOCATION,
          SearchSchemaMigrations.POSTGRESQL_LOCATION,
          POSTGRES_START_TIME);
      case "sqlserver" -> run(
          new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-CU20-ubuntu-22.04") {
            @Override
            public InspectContainerResponse getContainerInfo() {
              return getCurrentContainerInfo();
            }
          }.acceptLicense(),
          CoreSchemaMigrations.SQL_SERVER_LOCATION,
          SearchSchemaMigrations.SQL_SERVER_LOCATION,
          SQL_SERVER_START_TIME);
      default -> throw new IllegalArgumentException("Unsupported scroll benchmark backend: " + backend);
    }
  }

  private void run(
      JdbcDatabaseContainer<?> container, String coreLocation, String searchLocation,
      String startTimeQuery) throws IOException {
    int documents = Integer.parseInt(System.getProperty("benchmark.scroll.documents", "10000"));
    List<Integer> chunks =
        Arrays.stream(System.getProperty("benchmark.scroll.chunkSizes", "25,100,500").split(",", -1))
            .map(String::strip)
            .map(Integer::parseInt)
            .toList();
    int repetitions = Integer.parseInt(System.getProperty("benchmark.scroll.repetitions", "3"));
    Path output = Path.of(System.getProperty("benchmark.scroll.output", "target/history-scroll"));

    try (container) {
      container.start();
      migrate(container, coreLocation, CoreSchemaMigrations.SCHEMA_HISTORY_TABLE);
      migrate(container, searchLocation, SearchSchemaMigrations.SCHEMA_HISTORY_TABLE);
      Properties properties = new Properties();
      properties.setProperty("hibernate.connection.url", container.getJdbcUrl());
      properties.setProperty("hibernate.connection.username", container.getUsername());
      properties.setProperty("hibernate.connection.password", container.getPassword());
      properties.setProperty("hibernate.connection.driver_class", container.getDriverClassName());
      properties.setProperty("hibernate.hbm2ddl.auto", "validate");
      HistoryScrollExportBenchmark.run(
          properties, directory.resolve("lucene"), output, documents, chunks, repetitions,
          afterRestart -> restart(container, startTimeQuery, afterRestart));
    }
  }

  private static HistoryScrollExportBenchmark.RestartObservation restart(
      JdbcDatabaseContainer<?> container, String startTimeQuery, Properties properties)
      throws IOException {
    try {
      long before = serverStartedAt(container, startTimeQuery);
      long start = System.nanoTime();
      container.getDockerClient().restartContainerCmd(container.getContainerId()).withTimeout(30).exec();
      long after = serverStartedAt(container, startTimeQuery);
      long readyElapsed = System.nanoTime() - start;
      if (after <= before) {
        throw new IOException("Database startup time did not advance after the container restart");
      }
      // Docker can assign a fresh host port. The container classes always inspect current bindings.
      properties.setProperty("hibernate.connection.url", container.getJdbcUrl());
      return new HistoryScrollExportBenchmark.RestartObservation(before, after, readyElapsed);
    } catch (SQLException failure) {
      throw new IOException("Could not verify the database startup time", failure);
    }
  }

  private static long serverStartedAt(JdbcDatabaseContainer<?> container, String query)
      throws SQLException {
    try (var connection = container.createConnection("");
        var statement = connection.createStatement()) {
      statement.setQueryTimeout(10);
      try (var result = statement.executeQuery(query)) {
        if (result.next()) {
          long startedAt = result.getLong(1);
          if (startedAt > 0) {
            return startedAt;
          }
        }
        throw new SQLException("Database did not report a valid startup timestamp");
      }
    }
  }

  private static void migrate(JdbcDatabaseContainer<?> container, String location, String table) {
    Flyway.configure()
        .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
        .locations(location)
        .table(table)
        .baselineOnMigrate(true)
        .baselineVersion("0")
        .load()
        .migrate();
  }
}
