/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationMetrics;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.UUID;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;

class DeferredPackStreamPersistenceH2Test {

  @Test
  void keepsIntermediateFlushesLocalAndPersistsOnlyOnceOnClose() throws Exception {
    String repositoryName = "deferred-pack-" + UUID.randomUUID();
    try (HibernateSessionFactoryProvider provider = provider(repositoryName);
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      HibernateObjDatabase objectDatabase = repository.getObjectDatabase();
      DfsPackDescription description =
          new DfsPackDescription(repository.getDescription(), "manual-pack", PackSource.INSERT);
      byte[] payload = "complete pack payload after several flushes".getBytes(StandardCharsets.UTF_8);
      StorageOperationMetrics before = repository.getStorageOperationMetrics();

      try (DfsOutputStream stream = objectDatabase.writeFile(description, PackExt.PACK)) {
        stream.write(payload, 0, 12);
        stream.flush();
        stream.write(payload, 12, payload.length - 12);
        stream.flush();

        assertEquals(
            StorageOperationMetrics.ZERO,
            repository.getStorageOperationMetrics().minus(before),
            "Intermediate flushes must not open database transactions");
        assertEquals(
            0L,
            countPackRows(provider, repositoryName),
            "The partial temporary stream must not be visible in database state");
      }

      StorageOperationMetrics closeDelta = repository.getStorageOperationMetrics().minus(before);
      assertEquals(1, closeDelta.transactionsStarted());
      assertEquals(1, closeDelta.transactionsCommitted());
      assertEquals(0, closeDelta.transactionsRolledBack());
      assertEquals(1, closeDelta.repositoryLocksAcquired());
      assertArrayEquals(payload, storedPayload(provider, repositoryName));
    }
  }

  private static long countPackRows(
      HibernateSessionFactoryProvider provider, String repositoryName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT count(p.id) FROM GitPackEntity p WHERE p.repositoryName = :repo "
                  + "AND p.packName = 'manual-pack' AND p.packExtension = 'pack'",
              Long.class)
          .setParameter("repo", repositoryName)
          .getSingleResult();
    }
  }

  private static byte[] storedPayload(
      HibernateSessionFactoryProvider provider, String repositoryName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT p.data FROM GitPackEntity p WHERE p.repositoryName = :repo "
                  + "AND p.packName = 'manual-pack' AND p.packExtension = 'pack'",
              byte[].class)
          .setParameter("repo", repositoryName)
          .getSingleResult();
    }
  }

  private static HibernateSessionFactoryProvider provider(String repositoryName) {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url", "jdbc:h2:mem:" + repositoryName + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.connection.pool_size", "2");
    properties.put(HibernateTransactionContext.METRICS_ENABLED_PROPERTY, "true");
    return new HibernateSessionFactoryProvider(properties);
  }
}
