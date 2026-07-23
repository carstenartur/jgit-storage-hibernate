/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import io.github.carstenartur.jgit.storage.hibernate.CoreSchemaMigrationIntegrationTest.TestDatabase;
import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreHsqlDbSchemaMigrationIntegrationTest {

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();
  private static final String HSQLDB_LEGACY_SCHEMA =
      "/db/legacy/jgit-storage-hibernate/core/0.1.4/hsqldb/schema.sql";

  @Test
  void migratesEmptyInMemoryDatabaseAndRestartsWithValidation() throws Exception {
    try (TestDatabase database = inMemoryDatabase("empty")) {
      CoreSchemaMigrationIntegrationTest.verifyEmptyMigrationAndRestart(database);
    }
  }

  @Test
  void upgradesImmutableLegacyInMemoryFixtureWithoutDataLoss() throws Exception {
    try (TestDatabase database = inMemoryDatabase("upgrade")) {
      CoreSchemaMigrationIntegrationTest.verifyLegacyUpgrade(database);
    }
  }

  @Test
  void persistsRepositoryAcrossFileBackedDatabaseRestart(@TempDir Path directory)
      throws Exception {
    try (TestDatabase database = fileDatabase(directory, "restart")) {
      CoreSchemaMigrationIntegrationTest.verifyEmptyMigrationAndRestart(database);
    }
  }

  private static TestDatabase inMemoryDatabase(String purpose) {
    String databaseName = "core_hsqldb_" + purpose + "_" + TEST_COUNTER.incrementAndGet();
    String url = "jdbc:hsqldb:mem:" + databaseName;
    return database(url);
  }

  private static TestDatabase fileDatabase(Path directory, String purpose) {
    String databaseName = "core_hsqldb_" + purpose + "_" + TEST_COUNTER.incrementAndGet();
    String path = directory.resolve(databaseName).toAbsolutePath().toString().replace('\\', '/');
    return database("jdbc:hsqldb:file:" + path);
  }

  private static TestDatabase database(String url) {
    return new TestDatabase(
        url,
        "SA",
        "",
        "org.hsqldb.jdbc.JDBCDriver",
        "org.hibernate.dialect.HSQLDialect",
        CoreSchemaMigrations.HSQLDB_LOCATION,
        HSQLDB_LEGACY_SCHEMA,
        () -> shutdown(url));
  }

  private static void shutdown(String url) {
    try (Connection connection = DriverManager.getConnection(url, "SA", "");
        Statement statement = connection.createStatement()) {
      statement.execute("SHUTDOWN");
    } catch (Exception ignored) {
      // A file database may already have shut down when its last connection closed.
    }
  }
}
