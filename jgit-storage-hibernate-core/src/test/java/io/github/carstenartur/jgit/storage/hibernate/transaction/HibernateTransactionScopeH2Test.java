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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitRepositoryLockEntity;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.junit.jupiter.api.Test;

class HibernateTransactionScopeH2Test {

  @Test
  void joinsNestedWorkAndReusesOneRepositoryLock() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider()) {
      persistLock(provider, "scope-repo");
      HibernateTransactionContext context =
          new HibernateTransactionContext(provider.getSessionFactory());
      StorageOperationMetrics before = context.metricsSnapshot();

      try (var scope = context.beginScope()) {
        context.executeWithRepositoryLock("scope-repo", session -> null);
        context.executeWithRepositoryLock("scope-repo", session -> null);
        context.execute(session -> null);
        assertThrows(IllegalStateException.class, context::beginScope);
        scope.commit();
        assertThrows(IllegalStateException.class, scope::commit);
        scope.rollback();
      }

      assertEquals(
          new StorageOperationMetrics(1, 1, 0, 1, context.metricsSnapshot()
              .minus(before)
              .repositoryLockAcquisitionNanos()),
          context.metricsSnapshot().minus(before));
    }
  }

  @Test
  void closingAnUncommittedScopeRollsBackAndReleasesTheThreadContext() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider()) {
      HibernateTransactionContext context =
          new HibernateTransactionContext(provider.getSessionFactory());
      StorageOperationMetrics before = context.metricsSnapshot();

      try (var ignored = context.beginScope()) {
        context.execute(
            session -> {
              GitRepositoryLockEntity transientRow = new GitRepositoryLockEntity();
              transientRow.setRepositoryName("rolled-back");
              transientRow.setCreatedAt(Instant.now());
              session.persist(transientRow);
              return null;
            });
      }

      try (Session session = provider.getSessionFactory().openSession()) {
        assertNull(session.find(GitRepositoryLockEntity.class, "rolled-back"));
      }
      StorageOperationMetrics delta = context.metricsSnapshot().minus(before);
      assertEquals(1, delta.transactionsStarted());
      assertEquals(0, delta.transactionsCommitted());
      assertEquals(1, delta.transactionsRolledBack());

      context.execute(session -> null);
      assertEquals(2, context.metricsSnapshot().minus(before).transactionsStarted());
    }
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

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:transaction-scope-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.connection.pool_size", "2");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    return new HibernateSessionFactoryProvider(properties);
  }
}
