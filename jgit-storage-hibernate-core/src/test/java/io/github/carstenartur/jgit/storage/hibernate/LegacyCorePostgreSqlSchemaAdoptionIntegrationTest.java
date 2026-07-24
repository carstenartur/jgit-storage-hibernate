/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import io.github.carstenartur.jgit.storage.hibernate.LegacyCoreSchemaAdoptionIntegrationTest.AdoptionDatabase;
import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class LegacyCorePostgreSqlSchemaAdoptionIntegrationTest {

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();

  @Container
  static final PostgreSQLContainer<?> POSTGRESQL =
      new PostgreSQLContainer<>("postgres:17.10-alpine")
          .withDatabaseName("jgit_storage_hibernate_adoption")
          .withUsername("postgres")
          .withPassword("postgres");

  @Test
  void adoptsPreLibraryPostgreSqlSchemaWithoutChangingPackBlobs() throws Exception {
    String baseUrl = POSTGRESQL.getJdbcUrl();
    String username = POSTGRESQL.getUsername();
    String password = POSTGRESQL.getPassword();
    String schema = "legacy_adoption_" + TEST_COUNTER.incrementAndGet();
    try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
        Statement statement = connection.createStatement()) {
      statement.execute("create schema " + schema);
    }

    AdoptionDatabase database =
        new AdoptionDatabase(
            appendParameter(baseUrl, "currentSchema", schema),
            username,
            password,
            "org.postgresql.Driver",
            "org.hibernate.dialect.PostgreSQLDialect",
            CoreSchemaMigrations.POSTGRESQL_LOCATION,
            CoreSchemaMigrations.POSTGRESQL_LEGACY_ADOPTION_LOCATION);
    try {
      LegacyCoreSchemaAdoptionIntegrationTest.verifyAdoption(database);
    } finally {
      try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
          Statement statement = connection.createStatement()) {
        statement.execute("drop schema if exists " + schema + " cascade");
      }
    }
  }

  private static String appendParameter(String url, String key, String value) {
    return url + (url.contains("?") ? "&" : "?") + key + "=" + value;
  }
}
