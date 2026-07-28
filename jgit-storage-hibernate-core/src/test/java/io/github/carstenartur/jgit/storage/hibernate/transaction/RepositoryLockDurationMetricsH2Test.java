/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.transaction;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitRepositoryLockEntity;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RepositoryLockDurationMetricsH2Test {

  @Test
  void recordsNonNegativeAcquisitionDuration() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider()) {
      try (var session = provider.getSessionFactory().openSession()) {
        var transaction = session.beginTransaction();
        GitRepositoryLockEntity lock = new GitRepositoryLockEntity();
        lock.setRepositoryName("duration-repo");
        lock.setCreatedAt(Instant.now());
        session.persist(lock);
        transaction.commit();
      }

      HibernateTransactionContext context =
          new HibernateTransactionContext(provider.getSessionFactory());
      context.executeWithRepositoryLock("duration-repo", session -> null);
      assertTrue(context.metricsSnapshot().repositoryLockAcquisitionNanos() >= 0);
    }
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:lock-duration-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.connection.pool_size", "2");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    return new HibernateSessionFactoryProvider(properties);
  }
}
