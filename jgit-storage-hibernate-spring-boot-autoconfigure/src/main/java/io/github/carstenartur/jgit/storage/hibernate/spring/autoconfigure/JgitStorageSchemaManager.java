/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.spring.autoconfigure;

import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import io.github.carstenartur.jgit.storage.hibernate.search.schema.SearchSchemaMigrations;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;

/** Applies or validates module-owned Flyway streams before Hibernate schema validation begins. */
public final class JgitStorageSchemaManager {

  private final DataSource dataSource;
  private final JgitStorageHibernateProperties properties;
  private final AtomicBoolean initialized = new AtomicBoolean();

  public JgitStorageSchemaManager(
      DataSource dataSource, JgitStorageHibernateProperties properties) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  /** Execute the configured schema action exactly once. */
  public void initialize() {
    if (!initialized.compareAndSet(false, true)) {
      return;
    }
    if (properties.getSchemaAction() == JgitStorageHibernateProperties.SchemaAction.NONE) {
      return;
    }

    DatabaseKind database = detectDatabase();
    run(
        coreLocation(database),
        CoreSchemaMigrations.SCHEMA_HISTORY_TABLE,
        properties.isBaselineOnMigrate(),
        CoreSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION,
        CoreSchemaMigrations.PRE_MIGRATION_BASELINE_DESCRIPTION);

    if (properties.getSearch().isEnabled()) {
      run(
          searchLocation(database),
          SearchSchemaMigrations.SCHEMA_HISTORY_TABLE,
          true,
          SearchSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION,
          SearchSchemaMigrations.PRE_MIGRATION_BASELINE_DESCRIPTION);
    }
  }

  private void run(
      String location,
      String historyTable,
      boolean baselineOnMigrate,
      String baselineVersion,
      String baselineDescription) {
    FluentConfiguration configuration =
        Flyway.configure()
            .dataSource(dataSource)
            .locations(location)
            .table(historyTable)
            .baselineOnMigrate(baselineOnMigrate);
    if (baselineOnMigrate) {
      configuration
          .baselineVersion(baselineVersion)
          .baselineDescription(baselineDescription);
    }
    Flyway flyway = configuration.load();
    switch (properties.getSchemaAction()) {
      case MIGRATE -> flyway.migrate();
      case VALIDATE -> flyway.validate();
      case NONE -> throw new IllegalStateException("NONE is handled before Flyway configuration");
    }
  }

  private DatabaseKind detectDatabase() {
    try (Connection connection = dataSource.getConnection()) {
      String product =
          connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
      if (product.contains("postgresql")) {
        return DatabaseKind.POSTGRESQL;
      }
      if (product.contains("microsoft sql server")) {
        return DatabaseKind.SQL_SERVER;
      }
      if (product.equals("h2")) {
        return DatabaseKind.H2;
      }
      if (product.contains("hsql")) {
        return DatabaseKind.HSQLDB;
      }
      throw new IllegalStateException(
          "Unsupported database for jgit-storage-hibernate migrations: " + product);
    } catch (SQLException exception) {
      throw new IllegalStateException(
          "Could not identify the jgit-storage-hibernate database", exception);
    }
  }

  private static String coreLocation(DatabaseKind database) {
    return switch (database) {
      case H2 -> CoreSchemaMigrations.H2_LOCATION;
      case HSQLDB -> CoreSchemaMigrations.HSQLDB_LOCATION;
      case POSTGRESQL -> CoreSchemaMigrations.POSTGRESQL_LOCATION;
      case SQL_SERVER -> CoreSchemaMigrations.SQL_SERVER_LOCATION;
    };
  }

  private static String searchLocation(DatabaseKind database) {
    return switch (database) {
      case H2 -> SearchSchemaMigrations.H2_LOCATION;
      case POSTGRESQL -> SearchSchemaMigrations.POSTGRESQL_LOCATION;
      case SQL_SERVER -> SearchSchemaMigrations.SQL_SERVER_LOCATION;
      case HSQLDB ->
          throw new IllegalStateException(
              "Hibernate Search persistence is not supported on HSQLDB; disable search or use H2, PostgreSQL or SQL Server");
    };
  }

  private enum DatabaseKind {
    H2,
    HSQLDB,
    POSTGRESQL,
    SQL_SERVER
  }
}
