/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.schema.LegacyCoreSchemaAdoption;
import io.github.carstenartur.jgit.storage.hibernate.schema.LegacyCoreSchemaAdoptionException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class LegacyCoreSqlServerSchemaPreflightIntegrationTest {

  @Container
  static final MSSQLServerContainer<?> SQL_SERVER =
      new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-CU20-ubuntu-22.04")
          .acceptLicense();

  @Test
  void validatesSqlServerLegacySchemaWithoutUsingDatabaseSpecificLengthSql() throws Exception {
    try (Connection connection =
        DriverManager.getConnection(
            SQL_SERVER.getJdbcUrl(), SQL_SERVER.getUsername(), SQL_SERVER.getPassword())) {
      createLegacySchema(connection);
      insertPack(connection, "pack");

      LegacyCoreSchemaAdoption.LegacySchemaReport report =
          LegacyCoreSchemaAdoption.requireSafeToAdopt(connection);

      assertTrue(report.requiresAdoption());
      assertEquals(1, report.packRows());
      assertEquals(1, countPacks(connection));

      updatePackExtension(connection, "x".repeat(33));
      LegacyCoreSchemaAdoptionException exception =
          assertThrows(
              LegacyCoreSchemaAdoptionException.class,
              () -> LegacyCoreSchemaAdoption.requireSafeToAdopt(connection));
      assertTrue(exception.getMessage().contains("longer than 32"));
      assertEquals(1, countPacks(connection));
    }
  }

  private static void createLegacySchema(Connection connection) throws Exception {
    try (Statement statement = connection.createStatement()) {
      statement.execute(
          """
          create table git_packs (
            id bigint identity(1,1) primary key,
            repository_name varchar(255) not null,
            pack_name varchar(255) not null,
            pack_extension varchar(255) not null,
            data varbinary(max) not null,
            file_size bigint not null,
            created_at datetime2(6) not null
          )
          """);
    }
  }

  private static void insertPack(Connection connection, String extension) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            """
            insert into git_packs
              (repository_name, pack_name, pack_extension, data, file_size, created_at)
            values (?, ?, ?, ?, ?, ?)
            """)) {
      byte[] data = {1, 2, 3};
      statement.setString(1, "demo");
      statement.setString(2, "pack-a");
      statement.setString(3, extension);
      statement.setBytes(4, data);
      statement.setLong(5, data.length);
      statement.setTimestamp(6, Timestamp.from(Instant.parse("2026-07-28T00:00:00Z")));
      statement.executeUpdate();
    }
  }

  private static void updatePackExtension(Connection connection, String extension) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement("update git_packs set pack_extension = ?")) {
      statement.setString(1, extension);
      statement.executeUpdate();
    }
  }

  private static long countPacks(Connection connection) throws Exception {
    try (Statement statement = connection.createStatement();
        ResultSet resultSet = statement.executeQuery("select count(*) from git_packs")) {
      resultSet.next();
      return resultSet.getLong(1);
    }
  }
}
