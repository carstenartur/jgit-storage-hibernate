/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitRepositoryLifecycleEntity;
import io.github.carstenartur.jgit.storage.hibernate.queue.HibernateDurableBatchProcessor.Locking;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationKind;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;

class HibernateDurableBatchProcessorH2Test {

  @Test
  void oneQueueBatchCommitsThroughTheHibernateAdapterBeforeAcknowledgement() throws Exception {
    try (HibernateSessionFactoryProvider provider =
        new HibernateSessionFactoryProvider(h2Properties())) {
      HibernateDurableBatchProcessor<String, String> processor =
          new HibernateDurableBatchProcessor<>(
              provider.getSessionFactory(),
              StorageOperationKind.OTHER,
              Locking.NONE,
              (session, repositoryName, names) -> {
                for (String name : names) {
                  GitRepositoryLifecycleEntity entity = new GitRepositoryLifecycleEntity();
                  entity.setRepositoryName(name);
                  entity.setCreatedAt(Instant.now());
                  session.persist(entity);
                }
                return names;
              });
      DurableStripedWriteQueue.Limits limits =
          new DurableStripedWriteQueue.Limits(
              1,
              10,
              1024,
              3,
              1024,
              Duration.ofSeconds(1),
              Duration.ofSeconds(1));

      try (DurableStripedWriteQueue<String, String> queue =
          new DurableStripedWriteQueue<>(limits, processor)) {
        List<DurableStripedWriteQueue.Submission<String>> submissions =
            List.of(
                queue.submit("logical-repository", 1, "adapter-1"),
                queue.submit("logical-repository", 1, "adapter-2"),
                queue.submit("logical-repository", 1, "adapter-3"));

        for (int index = 0; index < submissions.size(); index++) {
          assertEquals(
              "adapter-" + (index + 1),
              submissions.get(index).completion().get(5, TimeUnit.SECONDS));
          assertEquals(3, submissions.get(index).batchSize());
        }
        assertEquals(3L, lifecycleCount(provider));
      }
    }
  }

  private static Properties h2Properties() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:hibernate-queue-adapter-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return properties;
  }

  private static long lifecycleCount(HibernateSessionFactoryProvider provider) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery("SELECT COUNT(l) FROM GitRepositoryLifecycleEntity l", Long.class)
          .getSingleResult();
    }
  }
}
