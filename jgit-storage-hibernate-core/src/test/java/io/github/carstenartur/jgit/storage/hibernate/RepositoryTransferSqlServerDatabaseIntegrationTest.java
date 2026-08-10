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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mssqlserver.MSSQLServerContainer;

@Testcontainers(disabledWithoutDocker = true)
class RepositoryTransferSqlServerDatabaseIntegrationTest {

  @Container
  static final MSSQLServerContainer SQL_SERVER =
      new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-CU20-ubuntu-22.04")
          .acceptLicense();

  @Test
  @Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void preservesCloneFetchRestartAndDeletionIsolation() throws Exception {
    DatabaseFixture database =
        new DatabaseFixture(
            "sqlserver",
            SQL_SERVER.getJdbcUrl(),
            SQL_SERVER.getUsername(),
            SQL_SERVER.getPassword(),
            "com.microsoft.sqlserver.jdbc.SQLServerDriver",
            "org.hibernate.dialect.SQLServerDialect",
            CoreSchemaMigrations.SQL_SERVER_LOCATION,
            () -> {});

    RepositoryTransferDatabaseContract.verify(database);
  }
}
