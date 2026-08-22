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

class PackRepackMaintenanceConcurrencyH2Test {

  @Test
  @Timeout(value = 180, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void readersObserveACompleteGenerationWhileRepackIsRunning() throws Exception {
    PackRepackMaintenanceConcurrencyContract.verifyReaderVisibility(
        PackRepackMaintenanceConcurrencyContract.DatabaseFixture.h2());
  }

  @Test
  @Timeout(value = 180, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void maintenanceOfIndependentRepositoriesDoesNotUseAGlobalLock() throws Exception {
    PackRepackMaintenanceConcurrencyContract.verifyIndependentRepositoryMaintenance(
        PackRepackMaintenanceConcurrencyContract.DatabaseFixture.h2());
  }

  @Test
  @Timeout(value = 180, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void providerRestartRetainsThePublishedMaintenanceGeneration() throws Exception {
    PackRepackMaintenanceConcurrencyContract.verifyProviderRestartAfterMaintenance(
        PackRepackMaintenanceConcurrencyContract.DatabaseFixture.h2());
  }
}
