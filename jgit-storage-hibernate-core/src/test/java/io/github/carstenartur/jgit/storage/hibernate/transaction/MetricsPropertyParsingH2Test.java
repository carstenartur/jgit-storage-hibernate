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

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MetricsPropertyParsingH2Test {

  @Test
  void treatsMixedCaseTrueAsEnabled() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider("TrUe")) {
      HibernateTransactionContext context =
          new HibernateTransactionContext(provider.getSessionFactory());
      context.execute(session -> null);
      assertEquals(1, context.metricsSnapshot().transactionsStarted());
    }
  }

  @Test
  void treatsOtherValuesAsDisabled() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider("yes")) {
      HibernateTransactionContext context =
          new HibernateTransactionContext(provider.getSessionFactory());
      context.execute(session -> null);
      assertEquals(StorageOperationMetrics.ZERO, context.metricsSnapshot());
    }
  }

  private static HibernateSessionFactoryProvider provider(String configuredValue) {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:metrics-property-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.connection.pool_size", "2");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, configuredValue);
    return new HibernateSessionFactoryProvider(properties);
  }
}
