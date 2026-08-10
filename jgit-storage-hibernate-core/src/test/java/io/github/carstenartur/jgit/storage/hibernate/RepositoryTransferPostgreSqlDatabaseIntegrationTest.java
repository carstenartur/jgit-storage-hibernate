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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class RepositoryTransferPostgreSqlDatabaseIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRESQL =
      new PostgreSQLContainer<>("postgres:17.10-alpine")
          .withDatabaseName("repository_transfer")
          .withUsername("transfer")
          .withPassword("transfer");

  @Test
  @Timeout(value = 180, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void preservesCloneFetchRestartAndDeletionIsolation() throws Exception {
    DatabaseFixture database =
        new DatabaseFixture(
            "postgresql",
            POSTGRESQL.getJdbcUrl(),
            POSTGRESQL.getUsername(),
            POSTGRESQL.getPassword(),
            "org.postgresql.Driver",
            "org.hibernate.dialect.PostgreSQLDialect",
            CoreSchemaMigrations.POSTGRESQL_LOCATION,
            () -> {});

    RepositoryTransferDatabaseContract.verify(database);
  }
}
