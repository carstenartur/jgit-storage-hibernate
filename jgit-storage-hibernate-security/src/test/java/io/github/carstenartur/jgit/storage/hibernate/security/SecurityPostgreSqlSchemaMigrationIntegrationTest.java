/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package io.github.carstenartur.jgit.storage.hibernate.security;

import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import io.github.carstenartur.jgit.storage.hibernate.security.SecuritySchemaMigrationIntegrationTest.TestDatabase;
import io.github.carstenartur.jgit.storage.hibernate.security.schema.SecuritySchemaMigrations;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
class SecurityPostgreSqlSchemaMigrationIntegrationTest {

  @Container
  static final PostgreSQLContainer POSTGRESQL =
      new PostgreSQLContainer("postgres:17.10-alpine");

  @Test
  void migratesEmptyPostgreSqlDatabaseAndRestartsWithValidation() throws Exception {
    try (TestDatabase database =
        new TestDatabase(
            POSTGRESQL.getJdbcUrl(),
            POSTGRESQL.getUsername(),
            POSTGRESQL.getPassword(),
            "org.postgresql.Driver",
            "org.hibernate.dialect.PostgreSQLDialect",
            CoreSchemaMigrations.POSTGRESQL_LOCATION,
            SecuritySchemaMigrations.POSTGRESQL_LOCATION,
            () -> {})) {
      SecuritySchemaMigrationIntegrationTest.verifyEmptyMigrationAndRestart(database);
    }
  }
}
