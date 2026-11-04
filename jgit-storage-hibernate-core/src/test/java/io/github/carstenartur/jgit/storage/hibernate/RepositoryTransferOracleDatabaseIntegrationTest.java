/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryTransferDatabaseContract.DatabaseFixture;
import io.github.carstenartur.jgit.storage.hibernate.schema.CoreSchemaMigrations;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

@Testcontainers(disabledWithoutDocker = true)
class RepositoryTransferOracleDatabaseIntegrationTest {
  @Container static final OracleContainer ORACLE = OracleIntegrationSupport.container();

  @Test
  @Timeout(value = 420, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void preservesCloneFetchProviderReopenAndDeletionIsolation() throws Exception {
    RepositoryTransferDatabaseContract.verify(
        new DatabaseFixture(
            "oracle",
            ORACLE.getJdbcUrl(),
            ORACLE.getUsername(),
            ORACLE.getPassword(),
            "oracle.jdbc.OracleDriver",
            "org.hibernate.dialect.OracleDialect",
            CoreSchemaMigrations.ORACLE_LOCATION,
            () -> {})); // The contract reopens SessionFactories; the database container stays
                        // running.
  }
}
