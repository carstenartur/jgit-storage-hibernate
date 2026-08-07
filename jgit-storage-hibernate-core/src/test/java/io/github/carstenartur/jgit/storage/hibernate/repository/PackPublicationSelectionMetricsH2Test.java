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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.PackPublicationSelectionMetrics;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectInserter;
import org.junit.jupiter.api.Test;

class PackPublicationSelectionMetricsH2Test {

  @Test
  void exposesDirectAndPrePersistedSelectionsWithoutChangingTheFixedDefault() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), "selection-metrics")) {
      repository.create(true);
      assertEquals(1024L * 1024L, repository.getMinimumPrePersistedPackPayloadBytes());
      PackPublicationSelectionMetrics before = repository.getPackPublicationSelectionMetrics();

      insert(repository, 384 * 1024 + 17, 17);
      PackPublicationSelectionMetrics direct =
          repository.getPackPublicationSelectionMetrics().minus(before);
      assertEquals(1, direct.directSelections());
      assertEquals(0, direct.prePersistedSelections());
      assertTrue(direct.directStagedPayloadBytes() > 256 * 1024);

      PackPublicationSelectionMetrics beforeLarge =
          repository.getPackPublicationSelectionMetrics();
      insert(repository, 2 * 1024 * 1024 + 31, 29);
      PackPublicationSelectionMetrics prePersisted =
          repository.getPackPublicationSelectionMetrics().minus(beforeLarge);
      assertEquals(0, prePersisted.directSelections());
      assertEquals(1, prePersisted.prePersistedSelections());
      assertTrue(prePersisted.prePersistedStagedPayloadBytes() >= 1024 * 1024);
    }
  }

  private static void insert(HibernateRepository repository, int length, int seed)
      throws Exception {
    byte[] payload = new byte[length];
    new Random(seed).nextBytes(payload);
    try (ObjectInserter inserter = repository.newObjectInserter()) {
      inserter.insert(Constants.OBJ_BLOB, payload);
      inserter.flush();
    }
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:selection-metrics-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.enabled", "false");
    properties.put("hibernate.connection.pool_size", "4");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    return new HibernateSessionFactoryProvider(properties);
  }
}
