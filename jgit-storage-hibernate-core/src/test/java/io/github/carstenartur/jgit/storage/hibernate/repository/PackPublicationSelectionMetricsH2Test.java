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
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateStorageSettings;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.PackPublicationPolicySnapshot.Path;
import io.github.carstenartur.jgit.storage.hibernate.transaction.PackPublicationPolicySnapshot.Reason;
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
    try (HibernateSessionFactoryProvider provider = provider(true, new Properties());
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
      assertEquals(Path.DIRECT, repository.getPackPublicationPolicySnapshot().lastPath());
      assertEquals(
          Reason.SIZE_THRESHOLD, repository.getPackPublicationPolicySnapshot().lastReason());

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

  @Test
  void observesCompletedPublicationsAndHonorsTheConfiguredFallbackThreshold() throws Exception {
    Properties overrides = new Properties();
    overrides.put(HibernateStorageSettings.PACK_PUBLICATION_SELECTOR, "contention-aware");
    overrides.put(HibernateStorageSettings.PACK_PREPERSIST_MIN_PAYLOAD_BYTES, "524288");
    try (HibernateSessionFactoryProvider provider = provider(true, overrides);
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), "contention-quiet")) {
      repository.create(true);

      insert(repository, 384 * 1024, 17);
      var direct = repository.getPackPublicationPolicySnapshot();
      assertEquals(Path.DIRECT, direct.lastPath());
      assertEquals(Reason.INSUFFICIENT_SAMPLES, direct.lastReason());
      assertEquals(1, direct.samples());
      assertEquals(524288, direct.effectiveThresholdBytes());
      assertTrue(direct.lockAcquisitionEwmaNanos() > 0);
      assertTrue(direct.publicationDurationEwmaNanos() >= direct.lockAcquisitionEwmaNanos());
      assertEquals(0, direct.queuedWritersEwma());

      insert(repository, 640 * 1024, 29);
      assertEquals(Path.PRE_PERSISTED, repository.getPackPublicationPolicySnapshot().lastPath());
      assertEquals(2, repository.getPackPublicationPolicySnapshot().samples());
      assertEquals(1, repository.getPackPublicationSelectionMetrics().prePersistedSelections());
    }
  }

  @Test
  void optingInWithoutMetricsKeepsTheDeterministicThreshold() throws Exception {
    Properties overrides = new Properties();
    overrides.put(HibernateStorageSettings.PACK_PUBLICATION_SELECTOR, "contention-aware");
    try (HibernateSessionFactoryProvider provider = provider(false, overrides);
        HibernateRepository repository =
            HibernateRepository.create(
                provider.getSessionFactory(), "contention-metrics-disabled")) {
      repository.create(true);
      insert(repository, 384 * 1024, 17);
      var policy = repository.getPackPublicationPolicySnapshot();
      assertEquals(Reason.METRICS_DISABLED, policy.lastReason());
      assertEquals(Path.NOT_SELECTED, policy.lastPath());
      assertEquals(1024 * 1024, policy.effectiveThresholdBytes());
      assertEquals(0, policy.samples());
      assertEquals(
          PackPublicationSelectionMetrics.ZERO, repository.getPackPublicationSelectionMetrics());
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

  private static HibernateSessionFactoryProvider provider(
      boolean metricsEnabled, Properties overrides) {
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
    properties.put(
        HibernateTransactionContext.METRICS_ENABLED_PROPERTY, Boolean.toString(metricsEnabled));
    properties.putAll(overrides);
    return new HibernateSessionFactoryProvider(properties);
  }
}
