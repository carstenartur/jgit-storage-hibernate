/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryTransferDatabaseContract.DatabaseFixture;
import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class RepositoryTransferHsqlDbDatabaseIntegrationTest {

  @Test
  @Timeout(value = 120, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void preservesCloneFetchEngineRestartAndDeletionIsolation(@TempDir Path directory)
      throws Exception {
    String databasePath =
        directory.resolve("repository-transfer").toAbsolutePath().toString().replace('\\', '/');
    String url = "jdbc:hsqldb:file:" + databasePath;
    DatabaseFixture database =
        new DatabaseFixture(
            "hsqldb",
            url,
            "SA",
            "",
            "org.hsqldb.jdbc.JDBCDriver",
            "org.hibernate.dialect.HSQLDialect",
            CoreSchemaMigrations.HSQLDB_LOCATION,
            () -> shutdown(url));

    try {
      RepositoryTransferDatabaseContract.verify(database);
    } finally {
      shutdown(url);
    }
  }

  private static void shutdown(String url) {
    try (var connection = DriverManager.getConnection(url, "SA", "");
        var statement = connection.createStatement()) {
      statement.execute("SHUTDOWN");
    } catch (Exception ignored) {
      // A previous restart boundary may already have closed the file database.
    }
  }
}
