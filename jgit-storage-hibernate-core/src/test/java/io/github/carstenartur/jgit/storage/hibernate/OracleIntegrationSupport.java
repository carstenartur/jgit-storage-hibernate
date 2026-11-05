/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.testcontainers.oracle.OracleContainer;

/** Shared, deliberately pinned Oracle 23+ qualification fixture. */
final class OracleIntegrationSupport {
  static final String IMAGE = "gvenzl/oracle-free:23.26.3-slim-faststart";

  private OracleIntegrationSupport() {}

  static OracleContainer container() {
    return new OracleContainer(IMAGE).withStartupTimeout(Duration.ofMinutes(5));
  }

  static Connection connection(OracleContainer oracle) throws Exception {
    return DriverManager.getConnection(
        oracle.getJdbcUrl(), oracle.getUsername(), oracle.getPassword());
  }

  static Flyway flyway(OracleContainer oracle) {
    return Flyway.configure()
        .dataSource(oracle.getJdbcUrl(), oracle.getUsername(), oracle.getPassword())
        .locations(CoreSchemaMigrations.ORACLE_LOCATION)
        .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
        .baselineOnMigrate(true)
        .baselineVersion(CoreSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION)
        .baselineDescription(CoreSchemaMigrations.PRE_MIGRATION_BASELINE_DESCRIPTION)
        .load();
  }

  static Properties properties(OracleContainer oracle) {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", oracle.getJdbcUrl());
    properties.put("hibernate.connection.username", oracle.getUsername());
    properties.put("hibernate.connection.password", oracle.getPassword());
    properties.put("hibernate.connection.driver_class", "oracle.jdbc.OracleDriver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.OracleDialect");
    properties.put("hibernate.hbm2ddl.auto", "validate");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.connection.pool_size", "6");
    return properties;
  }
}
