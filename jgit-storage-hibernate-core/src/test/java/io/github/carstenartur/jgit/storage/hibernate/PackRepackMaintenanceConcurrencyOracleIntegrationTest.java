/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

@Testcontainers(disabledWithoutDocker = true)
class PackRepackMaintenanceConcurrencyOracleIntegrationTest {
  @Container static final OracleContainer ORACLE = OracleIntegrationSupport.container();

  @Test
  @Timeout(value = 600, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void preservesReadersIndependentProgressAndProviderReopen() throws Exception {
    PackRepackMaintenanceConcurrencyContract.DatabaseFixture database =
        PackRepackMaintenanceConcurrencyContract.DatabaseFixture.jdbc(
            "oracle",
            ORACLE.getJdbcUrl(),
            ORACLE.getUsername(),
            ORACLE.getPassword(),
            "oracle.jdbc.OracleDriver",
            "org.hibernate.dialect.OracleDialect");
    PackRepackMaintenanceConcurrencyContract.verifyReaderVisibility(database);
    PackRepackMaintenanceConcurrencyContract.verifyIndependentRepositoryMaintenance(database);
    PackRepackMaintenanceConcurrencyContract.verifyProviderRestartAfterMaintenance(database);
  }
}
