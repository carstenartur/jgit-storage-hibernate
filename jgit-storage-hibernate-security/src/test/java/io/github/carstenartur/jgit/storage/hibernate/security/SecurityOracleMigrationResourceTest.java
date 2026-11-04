/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.carstenartur.jgit.storage.hibernate.security.schema.SecuritySchemaMigrations;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

/** Exercises Flyway discovery of the packaged Oracle migration without emulating Oracle DDL. */
class SecurityOracleMigrationResourceTest {
  @Test
  void discoversTheSingleFreshInstallMigrationOnThePublishedClasspath() {
    Flyway flyway =
        Flyway.configure()
            .dataSource("jdbc:h2:mem:oracle-security-discovery", "sa", "")
            .locations(SecuritySchemaMigrations.ORACLE_LOCATION)
            .table(SecuritySchemaMigrations.SCHEMA_HISTORY_TABLE)
            .load();
    var pending = flyway.info().pending();
    assertEquals(1, pending.length, "Oracle Security stream must have one initial migration");
    assertEquals("0.12.0", pending[0].getVersion().getVersion());
    assertEquals("V0_12_0__create_security_schema.sql", pending[0].getScript());
  }
}
