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
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
    ScheduledExecutorService watchdog =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "hsqldb-restart-watchdog");
              thread.setDaemon(true);
              return thread;
            });
    ScheduledFuture<?> timeout =
        watchdog.schedule(CoreHsqlDbSchemaMigrationIntegrationTest::dumpThreadsAndHalt, 45, TimeUnit.SECONDS);
    try (TestDatabase database = fileDatabase(directory, "restart")) {
      CoreSchemaMigrationIntegrationTest.verifyEmptyMigrationAndRestart(database);
    } finally {
      timeout.cancel(false);
      watchdog.shutdownNow();
    }
  }

  private static void dumpThreadsAndHalt() {
    System.err.println("=== HSQLDB restart watchdog thread dump ===");
    Thread.getAllStackTraces().entrySet().stream()
        .sorted(Comparator.comparing(entry -> entry.getKey().getName()))
        .forEach(CoreHsqlDbSchemaMigrationIntegrationTest::printThread);
    System.err.flush();
    Runtime.getRuntime().halt(124);
  }

  private static void printThread(Map.Entry<Thread, StackTraceElement[]> entry) {
    Thread thread = entry.getKey();
    System.err.printf("\n\"%s\" id=%d state=%s%n", thread.getName(), thread.threadId(), thread.getState());
    for (StackTraceElement frame : entry.getValue()) {
      System.err.println("    at " + frame);
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
    return database("jdbc:hsqldb:file:" + path + ";hsqldb.tx=mvcc");
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
