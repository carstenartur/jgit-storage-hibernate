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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackChunkEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitRepositoryLifecycleEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitRepositoryLockEntity;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;

class RepositoryLifecycleOwnershipH2Test {

  @Test
  void deletingLifecycleCascadesToLockPackAndChunks() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      String repositoryName = "cascade-" + UUID.randomUUID();
      persistRepositoryPayload(provider, repositoryName);

      assertEquals(1L, count(provider, "GitRepositoryLifecycleEntity"));
      assertEquals(1L, count(provider, "GitRepositoryLockEntity"));
      assertEquals(1L, count(provider, "GitPackEntity"));
      assertEquals(1L, count(provider, "GitPackChunkEntity"));

      try (Session session = provider.getSessionFactory().openSession()) {
        Transaction transaction = session.beginTransaction();
        GitRepositoryLifecycleEntity lifecycle =
            session.find(GitRepositoryLifecycleEntity.class, repositoryName);
        assertNotNull(lifecycle);
        session.remove(lifecycle);
        transaction.commit();
      }

      assertEquals(0L, count(provider, "GitRepositoryLifecycleEntity"));
      assertEquals(0L, count(provider, "GitRepositoryLockEntity"));
      assertEquals(0L, count(provider, "GitPackEntity"));
      assertEquals(0L, count(provider, "GitPackChunkEntity"));
    }
  }

  @Test
  void rejectsPackForMissingRepositoryLifecycle() {
    try (HibernateSessionFactoryProvider provider = provider();
        Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      GitPackEntity pack = pack("missing-" + UUID.randomUUID(), Instant.now());
      session.persist(pack);

      RuntimeException failure = assertThrows(RuntimeException.class, session::flush);
      assertTrue(
          hasConstraintViolation(failure),
          () -> "Expected repository lifecycle foreign-key violation but got " + failure);
      if (transaction.isActive()) {
        transaction.rollback();
      }
    }
  }

  private static void persistRepositoryPayload(
      HibernateSessionFactoryProvider provider, String repositoryName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      Instant createdAt = Instant.now();

      GitRepositoryLifecycleEntity lifecycle = new GitRepositoryLifecycleEntity();
      lifecycle.setRepositoryName(repositoryName);
      lifecycle.setCreatedAt(createdAt);
      session.persist(lifecycle);
      session.flush();

      GitRepositoryLockEntity lock = new GitRepositoryLockEntity();
      lock.setRepositoryName(repositoryName);
      lock.setCreatedAt(createdAt);
      session.persist(lock);

      GitPackEntity pack = pack(repositoryName, createdAt);
      session.persist(pack);
      session.flush();

      GitPackChunkEntity chunk = new GitPackChunkEntity();
      chunk.setPackId(pack.getId());
      chunk.setChunkIndex(0);
      chunk.setChunkSize(4);
      chunk.setData(new byte[] {1, 2, 3, 4});
      session.persist(chunk);
      transaction.commit();
    }
  }

  private static GitPackEntity pack(String repositoryName, Instant createdAt) {
    GitPackEntity pack = new GitPackEntity();
    pack.setRepositoryName(repositoryName);
    pack.setPackName("pack-" + UUID.randomUUID());
    pack.setPackExtension("pack");
    pack.setData(null);
    pack.setFileSize(4);
    pack.setCommitted(false);
    pack.setCreatedAt(createdAt);
    return pack;
  }

  private static long count(HibernateSessionFactoryProvider provider, String entityName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery("SELECT COUNT(e) FROM " + entityName + " e", Long.class)
          .getSingleResult();
    }
  }

  private static boolean hasConstraintViolation(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      if (current instanceof ConstraintViolationException) {
        return true;
      }
    }
    return false;
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:repository-lifecycle-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return new HibernateSessionFactoryProvider(properties);
  }
}
