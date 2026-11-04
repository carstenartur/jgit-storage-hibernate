/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.search;

import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import io.github.carstenartur.jgit.storage.hibernate.search.schema.SearchSchemaMigrations;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.testcontainers.oracle.OracleContainer;

final class OracleSearchTestSupport {
  static OracleContainer container() {
    return new OracleContainer("gvenzl/oracle-free:23.26.3-slim-faststart")
        .withStartupTimeout(Duration.ofMinutes(5));
  }

  static Flyway core(OracleContainer oracle) {
    return migrations(
        oracle, CoreSchemaMigrations.ORACLE_LOCATION, CoreSchemaMigrations.SCHEMA_HISTORY_TABLE);
  }

  static Flyway search(OracleContainer oracle) {
    return migrations(
        oracle,
        SearchSchemaMigrations.ORACLE_LOCATION,
        SearchSchemaMigrations.SCHEMA_HISTORY_TABLE);
  }

  private static Flyway migrations(OracleContainer oracle, String location, String history) {
    return Flyway.configure()
        .dataSource(oracle.getJdbcUrl(), oracle.getUsername(), oracle.getPassword())
        .locations(location)
        .table(history)
        .failOnMissingLocations(true)
        .baselineOnMigrate(true)
        .baselineVersion("0")
        .load();
  }

  static Properties properties(OracleContainer oracle, Path lucene) {
    var properties = new Properties();
    properties.put("hibernate.connection.url", oracle.getJdbcUrl());
    properties.put("hibernate.connection.username", oracle.getUsername());
    properties.put("hibernate.connection.password", oracle.getPassword());
    properties.put("hibernate.connection.driver_class", "oracle.jdbc.OracleDriver");
    properties.put("hibernate.hbm2ddl.auto", "validate");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-filesystem");
    properties.put("hibernate.search.backend.directory.root", lucene.toAbsolutePath().toString());
    properties.put("hibernate.search.indexing.plan.synchronization.strategy", "sync");
    properties.put("jgit.storage.hibernate.search.index_batch_size", "2");
    return properties;
  }

  private OracleSearchTestSupport() {}
}
