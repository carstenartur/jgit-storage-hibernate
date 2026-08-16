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
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExternalPrincipalBindingEmbeddedDatabaseIntegrationTest {

  @Test
  void bindsMultipleExternalPrincipalsAcrossH2Restart() throws Exception {
    try (TestDatabase database = h2Database()) {
      SecuritySchemaMigrationIntegrationTest.verifyEmptyMigrationAndRestart(database);
      ExternalPrincipalBindingDatabaseContract.verify(database);
    }
  }

  @Test
  void bindsMultipleExternalPrincipalsAcrossHsqldbRestart() throws Exception {
    try (TestDatabase database = hsqldbDatabase()) {
      SecuritySchemaMigrationIntegrationTest.verifyEmptyMigrationAndRestart(database);
      ExternalPrincipalBindingDatabaseContract.verify(database);
    }
  }

  private static TestDatabase h2Database() {
    String name = "external-principal-binding-" + UUID.randomUUID();
    return new TestDatabase(
        "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1",
        "sa",
        "",
        "org.h2.Driver",
        "org.hibernate.dialect.H2Dialect",
        CoreSchemaMigrations.H2_LOCATION,
        SecuritySchemaMigrations.H2_LOCATION,
        () -> {});
  }

  private static TestDatabase hsqldbDatabase() {
    String name = "external_principal_binding_" + UUID.randomUUID().toString().replace('-', '_');
    String url = "jdbc:hsqldb:mem:" + name;
    return new TestDatabase(
        url,
        "sa",
        "",
        "org.hsqldb.jdbc.JDBCDriver",
        "org.hibernate.dialect.HSQLDialect",
        CoreSchemaMigrations.HSQLDB_LOCATION,
        SecuritySchemaMigrations.HSQLDB_LOCATION,
        () -> {
          try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            connection.createStatement().execute("shutdown");
          }
        });
  }
}
