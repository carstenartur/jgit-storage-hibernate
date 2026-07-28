/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationMetrics;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HibernateRepositoryMetricsH2Test {

  @Test
  void exposesOptInRepositoryMetricsWithoutChangingDefaultBehavior() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider(true);
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), "metrics-facade")) {
      StorageOperationMetrics before = repository.getStorageOperationMetrics();
      repository.inTransaction(session -> null);
      StorageOperationMetrics delta = repository.getStorageOperationMetrics().minus(before);

      assertEquals(1, delta.transactionsStarted());
      assertEquals(1, delta.transactionsCommitted());
      assertEquals(0, delta.transactionsRolledBack());
    }

    try (HibernateSessionFactoryProvider provider = provider(false);
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), "metrics-disabled")) {
      repository.inTransaction(session -> null);
      assertEquals(StorageOperationMetrics.ZERO, repository.getStorageOperationMetrics());
    }
  }

  private static HibernateSessionFactoryProvider provider(boolean metricsEnabled) {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:repository-metrics-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.connection.pool_size", "2");
    properties.put(
        HibernateTransactionContext.METRICS_ENABLED_PROPERTY,
        Boolean.toString(metricsEnabled));
    return new HibernateSessionFactoryProvider(properties);
  }
}
