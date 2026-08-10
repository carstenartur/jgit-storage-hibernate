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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class RepositoryTransferH2DatabaseIntegrationTest {

  @Test
  @Timeout(value = 90, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  void preservesCloneFetchRestartAndDeletionIsolation(@TempDir Path directory) throws Exception {
    String databasePath =
        directory.resolve("repository-transfer").toAbsolutePath().toString().replace('\\', '/');
    DatabaseFixture database =
        new DatabaseFixture(
            "h2",
            "jdbc:h2:file:" + databasePath + ";DB_CLOSE_ON_EXIT=FALSE",
            "sa",
            "",
            "org.h2.Driver",
            "org.hibernate.dialect.H2Dialect",
            CoreSchemaMigrations.H2_LOCATION,
            () -> {});

    RepositoryTransferDatabaseContract.verify(database);
  }
}
