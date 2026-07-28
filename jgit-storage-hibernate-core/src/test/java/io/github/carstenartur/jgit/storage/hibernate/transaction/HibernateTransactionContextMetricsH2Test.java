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
import io.github.carstenartur.jgit.storage.hibernate.entity.GitRepositoryLockEntity;
import java.io.IOException;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.junit.jupiter.api.Test;

class HibernateTransactionContextMetricsH2Test {

  @Test
  void countsOnlyTopLevelTransactionsAndRollbacks() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider(true)) {
      HibernateTransactionContext context =
          new HibernateTransactionContext(provider.getSessionFactory());

      context.execute(
          session -> {
            context.execute(nested -> null);
            return null;
          });
      assertThrows(
          IOException.class,
          () ->
              context.execute(
                  session -> {
                    throw new IOException("rollback");
                  }));

      assertEquals(
          new StorageOperationMetrics(2, 1, 1, 0, 0), context.metricsSnapshot());
    }
  }

  @Test
  void countsRepositoryLockAcquisitionInsideTheOwningTransaction() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider(true)) {
      persistLock(provider, "metrics-repo");
      HibernateTransactionContext context =
          new HibernateTransactionContext(provider.getSessionFactory());
      StorageOperationMetrics before = context.metricsSnapshot();

      context.executeWithRepositoryLock("metrics-repo", session -> null);

      StorageOperationMetrics delta = context.metricsSnapshot().minus(before);
      assertEquals(1, delta.transactionsStarted());
      assertEquals(1, delta.transactionsCommitted());
      assertEquals(0, delta.transactionsRolledBack());
      assertEquals(1, delta.repositoryLocksAcquired());
      assertTrue(delta.repositoryLockAcquisitionNanos() >= 0);
    }
  }

  @Test
  void remainsZeroWhenMetricsAreNotEnabled() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider(false)) {
      HibernateTransactionContext context =
          new HibernateTransactionContext(provider.getSessionFactory());
      context.execute(session -> null);
      assertEquals(StorageOperationMetrics.ZERO, context.metricsSnapshot());
    }
  }

  @Test
  void rejectsSubtractingANewerSnapshot() {
    StorageOperationMetrics older = new StorageOperationMetrics(1, 1, 0, 0, 0);
    StorageOperationMetrics newer = new StorageOperationMetrics(2, 2, 0, 0, 0);
    assertThrows(IllegalArgumentException.class, () -> older.minus(newer));
  }

  private static void persistLock(HibernateSessionFactoryProvider provider, String repositoryName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      GitRepositoryLockEntity lock = new GitRepositoryLockEntity();
      lock.setRepositoryName(repositoryName);
      lock.setCreatedAt(Instant.now());
      session.persist(lock);
      transaction.commit();
    }
  }

  private static HibernateSessionFactoryProvider provider(boolean metricsEnabled) {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:transaction-metrics-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
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
