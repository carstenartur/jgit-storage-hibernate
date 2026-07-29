/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class CoreH2StorageIndexMigrationIntegrationTest {

  @Test
  void createsOnlyRequiredSecondaryIndexes() throws Exception {
    String url = "jdbc:h2:mem:index-contract-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    Flyway.configure()
        .dataSource(url, "sa", "")
        .locations(CoreSchemaMigrations.H2_LOCATION)
        .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
        .baselineOnMigrate(true)
        .baselineVersion(CoreSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION)
        .baselineDescription(CoreSchemaMigrations.PRE_MIGRATION_BASELINE_DESCRIPTION)
        .load()
        .migrate();

    try (var connection = DriverManager.getConnection(url, "sa", "")) {
      StorageIndexContract.assertPortableOptimizedIndexes(connection);
    }
  }
}
