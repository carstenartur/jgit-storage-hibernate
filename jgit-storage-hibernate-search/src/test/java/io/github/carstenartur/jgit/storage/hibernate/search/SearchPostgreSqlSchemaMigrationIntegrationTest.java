/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search;

import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import io.github.carstenartur.jgit.storage.hibernate.search.SearchSchemaMigrationIntegrationTest.TestDatabase;
import io.github.carstenartur.jgit.storage.hibernate.search.schema.SearchSchemaMigrations;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class SearchPostgreSqlSchemaMigrationIntegrationTest {

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();
  private static final String POSTGRESQL_LEGACY_SCHEMA =
      "/db/legacy/jgit-storage-hibernate/search/0.1.4/postgresql/schema.sql";

  @Container
  static final PostgreSQLContainer<?> POSTGRESQL =
      new PostgreSQLContainer<>("postgres:17.10-alpine")
          .withDatabaseName("jgit_storage_hibernate_search")
          .withUsername("postgres")
          .withPassword("postgres");

  @Test
  void migratesEmptyPostgreSqlDatabaseAndRestartsWithValidation() throws Exception {
    try (TestDatabase database = postgresDatabase("empty")) {
      SearchSchemaMigrationIntegrationTest.verifyEmptyMigrationAndRestart(database);
    }
  }

  @Test
  void upgradesImmutableLegacyPostgreSqlFixtureWithoutDataLoss() throws Exception {
    try (TestDatabase database = postgresDatabase("upgrade")) {
      SearchSchemaMigrationIntegrationTest.verifyLegacyUpgrade(database);
    }
  }

  private static TestDatabase postgresDatabase(String purpose) throws SQLException {
    String baseUrl = POSTGRESQL.getJdbcUrl();
    String username = POSTGRESQL.getUsername();
    String password = POSTGRESQL.getPassword();
    String schema = "search_migration_" + purpose + "_" + TEST_COUNTER.incrementAndGet();

    try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
        Statement statement = connection.createStatement()) {
      statement.execute("create schema " + schema);
    }

    String schemaUrl = appendParameter(baseUrl, "currentSchema", schema);
    return new TestDatabase(
        schemaUrl,
        username,
        password,
        "org.postgresql.Driver",
        "org.hibernate.dialect.PostgreSQLDialect",
        CoreSchemaMigrations.POSTGRESQL_LOCATION,
        SearchSchemaMigrations.POSTGRESQL_LOCATION,
        POSTGRESQL_LEGACY_SCHEMA,
        () -> {
          try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
              Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
          }
        });
  }

  private static String appendParameter(String url, String key, String value) {
    return url + (url.contains("?") ? "&" : "?") + key + "=" + value;
  }
}
