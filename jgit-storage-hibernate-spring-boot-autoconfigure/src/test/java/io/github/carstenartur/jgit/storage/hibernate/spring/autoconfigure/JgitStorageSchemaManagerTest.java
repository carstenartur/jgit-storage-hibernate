/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.spring.autoconfigure;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class JgitStorageSchemaManagerTest {

  @Test
  void noneSkipsDatabaseAccessAndRemainsIdempotent() {
    JgitStorageHibernateProperties properties = new JgitStorageHibernateProperties();
    properties.setSchemaAction(JgitStorageHibernateProperties.SchemaAction.NONE);
    JgitStorageSchemaManager schemaManager =
        new JgitStorageSchemaManager(new RejectingDataSource(), properties);

    assertDoesNotThrow(schemaManager::initialize);
    assertDoesNotThrow(schemaManager::initialize);
  }

  private static final class RejectingDataSource implements DataSource {

    @Override
    public Connection getConnection() {
      throw new AssertionError("schema-action=NONE must not obtain a database connection");
    }

    @Override
    public Connection getConnection(String username, String password) {
      throw new AssertionError("schema-action=NONE must not obtain a database connection");
    }

    @Override
    public PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {}

    @Override
    public void setLoginTimeout(int seconds) {}

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() {
      return Logger.getGlobal();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      throw new SQLException("not a wrapper");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return false;
    }
  }
}
