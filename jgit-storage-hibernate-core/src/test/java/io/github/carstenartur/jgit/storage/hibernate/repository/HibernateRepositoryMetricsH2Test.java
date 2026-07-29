/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.repository;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationBreakdown;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationKind;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationMetrics;
import java.util.Properties;
import java.util.UUID;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.RefUpdate;
import org.junit.jupiter.api.Test;

class HibernateRepositoryMetricsH2Test {

  @Test
  void exposesOptInRepositoryMetricsWithoutChangingDefaultBehavior() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider(true);
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), "metrics-facade")) {
      StorageOperationMetrics before = repository.getStorageOperationMetrics();
      StorageOperationBreakdown breakdownBefore = repository.getStorageOperationBreakdown();
      repository.inTransaction(session -> null);
      StorageOperationMetrics delta = repository.getStorageOperationMetrics().minus(before);
      StorageOperationBreakdown breakdown =
          repository.getStorageOperationBreakdown().minus(breakdownBefore);

      assertEquals(1, delta.transactionsStarted());
      assertEquals(1, delta.transactionsCommitted());
      assertEquals(0, delta.transactionsRolledBack());
      assertEquals(delta, breakdown.total());
      assertEquals(delta, breakdown.metrics(StorageOperationKind.OTHER));
    }

    try (HibernateSessionFactoryProvider provider = provider(false);
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), "metrics-disabled")) {
      repository.inTransaction(session -> null);
      assertEquals(StorageOperationMetrics.ZERO, repository.getStorageOperationMetrics());
      assertEquals(StorageOperationBreakdown.ZERO, repository.getStorageOperationBreakdown());
    }
  }

  @Test
  void attributesActualJGitPackAndRefOperationsWithoutUnclassifiedTransactions()
      throws Exception {
    String repositoryName = "metrics-jgit-" + UUID.randomUUID();
    try (HibernateSessionFactoryProvider provider = provider(true);
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      StorageOperationMetrics before = repository.getStorageOperationMetrics();
      StorageOperationBreakdown breakdownBefore = repository.getStorageOperationBreakdown();

      ObjectId blob;
      try (ObjectInserter inserter = repository.newObjectInserter()) {
        blob = inserter.insert(Constants.OBJ_BLOB, "categorized payload".getBytes(UTF_8));
        inserter.flush();
      }
      RefUpdate update = repository.updateRef("refs/heads/metrics");
      update.setNewObjectId(blob);
      assertEquals(RefUpdate.Result.NEW, update.update());

      StorageOperationMetrics aggregate = repository.getStorageOperationMetrics().minus(before);
      StorageOperationBreakdown breakdown =
          repository.getStorageOperationBreakdown().minus(breakdownBefore);
      assertEquals(aggregate, breakdown.total());
      assertTrue(
          breakdown.metrics(StorageOperationKind.PACK_EXTENSION_WRITE).transactionsStarted() > 0);
      assertTrue(
          breakdown.metrics(StorageOperationKind.PACK_PUBLICATION).transactionsStarted() > 0);
      assertTrue(breakdown.metrics(StorageOperationKind.REF_PUBLICATION).transactionsStarted() > 0);
      assertEquals(
          StorageOperationMetrics.ZERO,
          breakdown.metrics(StorageOperationKind.OTHER),
          "Every real top-level repository transaction must have a stable operation category");
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
