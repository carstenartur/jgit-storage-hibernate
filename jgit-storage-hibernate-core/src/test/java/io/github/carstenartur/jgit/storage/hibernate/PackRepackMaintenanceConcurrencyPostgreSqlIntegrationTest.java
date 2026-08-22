/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class PackRepackMaintenanceConcurrencyPostgreSqlIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRESQL =
      new PostgreSQLContainer<>("postgres:17.10-alpine")
          .withDatabaseName("jgit_storage_maintenance_concurrency")
          .withUsername("postgres")
          .withPassword("postgres");

  @Test
  @Timeout(value = 420, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void preservesReadersIndependentProgressAndProviderRestart() throws Exception {
    PackRepackMaintenanceConcurrencyContract.DatabaseFixture database =
        PackRepackMaintenanceConcurrencyContract.DatabaseFixture.jdbc(
            "postgresql",
            POSTGRESQL.getJdbcUrl(),
            POSTGRESQL.getUsername(),
            POSTGRESQL.getPassword(),
            "org.postgresql.Driver",
            "org.hibernate.dialect.PostgreSQLDialect");

    PackRepackMaintenanceConcurrencyContract.verifyReaderVisibility(database);
    PackRepackMaintenanceConcurrencyContract.verifyIndependentRepositoryMaintenance(database);
    PackRepackMaintenanceConcurrencyContract.verifyProviderRestartAfterMaintenance(database);
  }
}
