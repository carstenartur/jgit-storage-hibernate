/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitRepositoryLockEntity;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import java.time.Instant;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectInserter;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.junit.jupiter.api.Test;

class PackRepositoryOwnershipH2Test {

  @Test
  void deletingRepositoryLifecycleCascadesToPacksAndChunks() throws Exception {
    String repositoryName = "owned-" + UUID.randomUUID();
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      byte[] payload = new byte[1024 * 1024 + 257];
      new Random(42).nextBytes(payload);
      try (ObjectInserter inserter = repository.newObjectInserter()) {
        inserter.insert(Constants.OBJ_BLOB, payload);
        inserter.flush();
      }

      assertTrue(packRows(provider, repositoryName) > 0);
      assertTrue(chunkRows(provider, repositoryName) > 0);

      try (Session session = provider.getSessionFactory().openSession()) {
        Transaction transaction = session.beginTransaction();
        GitRepositoryLockEntity lock =
            session.find(GitRepositoryLockEntity.class, repositoryName);
        session.remove(lock);
        transaction.commit();
      }

      assertEquals(0, packRows(provider, repositoryName));
      assertEquals(0, chunkRows(provider, repositoryName));
    }
  }

  @Test
  void rejectsPackForMissingRepositoryLifecycle() {
    try (HibernateSessionFactoryProvider provider = provider();
        Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      GitPackEntity pack = new GitPackEntity();
      pack.setRepositoryName("missing-" + UUID.randomUUID());
      pack.setPackName("pack-orphan");
      pack.setPackExtension("pack");
      pack.setData(new byte[] {1, 2, 3});
      pack.setFileSize(3);
      pack.setCommitted(false);
      pack.setCreatedAt(Instant.now());
      session.persist(pack);

      assertThrows(RuntimeException.class, transaction::commit);
      if (transaction.isActive()) {
        transaction.rollback();
      }
    }
  }

  private static long packRows(
      HibernateSessionFactoryProvider provider, String repositoryName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT COUNT(p) FROM GitPackEntity p WHERE p.repositoryName = :repo", Long.class)
          .setParameter("repo", repositoryName)
          .getSingleResult();
    }
  }

  private static long chunkRows(
      HibernateSessionFactoryProvider provider, String repositoryName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT COUNT(c) FROM GitPackChunkEntity c "
                  + "WHERE c.packId IN (SELECT p.id FROM GitPackEntity p "
                  + "WHERE p.repositoryName = :repo)",
              Long.class)
          .setParameter("repo", repositoryName)
          .getSingleResult();
    }
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:pack-ownership-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.connection.pool_size", "2");
    return new HibernateSessionFactoryProvider(properties);
  }
}
