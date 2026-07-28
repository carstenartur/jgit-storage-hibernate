/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import java.io.IOException;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MissingRepositoryLockMetricsH2Test {

  @Test
  void countsTheTransactionButNotALockWhenTheRepositoryRowIsMissing() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider()) {
      HibernateTransactionContext context =
          new HibernateTransactionContext(provider.getSessionFactory());
      StorageOperationMetrics before = context.metricsSnapshot();

      assertThrows(
          IOException.class,
          () -> context.executeWithRepositoryLock("missing-repository", session -> null));

      StorageOperationMetrics delta = context.metricsSnapshot().minus(before);
      assertEquals(1, delta.transactionsStarted());
      assertEquals(0, delta.transactionsCommitted());
      assertEquals(1, delta.transactionsRolledBack());
      assertEquals(0, delta.repositoryLocksAcquired());
      assertTrue(delta.repositoryLockAcquisitionNanos() >= 0);
    }
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:missing-lock-metrics-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.connection.pool_size", "2");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    return new HibernateSessionFactoryProvider(properties);
  }
}
