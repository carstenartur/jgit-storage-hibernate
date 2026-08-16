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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitRepositoryLifecycleEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitRepositoryLockEntity;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.junit.jupiter.api.Test;

class SecuredRepositoryMissingH2Test {

  private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();
  private static final RepositoryName MISSING = new RepositoryName("missing-secured-repository");

  @Test
  void authorizedMissingRepositoryHasAStableTypedResultWithoutCreatingMetadata() {
    try (HibernateSessionFactoryProvider provider = provider("missing")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      SecuredHibernateRepositoryFactory<String> factory =
          new SecuredHibernateRepositoryFactory<>(sessionFactory, (context, request) -> {});

      RepositoryDoesNotExistException missing =
          assertThrows(
              RepositoryDoesNotExistException.class,
              () -> factory.open(MISSING, "alice"));
      assertEquals(MISSING, missing.repositoryName());
      assertEquals("Repository " + MISSING + " does not exist", missing.getMessage());
      assertNoRepositoryMetadata(sessionFactory);
    }
  }

  @Test
  void incompleteRepositoryMetadataIsAnInfrastructureFailure() {
    try (HibernateSessionFactoryProvider provider = provider("incomplete")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistLifecycleOnly(sessionFactory);
      SecuredHibernateRepositoryFactory<String> factory =
          new SecuredHibernateRepositoryFactory<>(sessionFactory, (context, request) -> {});

      HibernateStorageException failure =
          assertThrows(
              HibernateStorageException.class,
              () -> factory.open(MISSING, "alice"));
      assertEquals(HibernateStorageException.class, failure.getClass());
      assertTrue(failure.getMessage().contains("Incomplete repository metadata"));
    }
  }

  @Test
  void repositoryMetadataWithoutInitializedGitRefsIsAnInfrastructureFailure() {
    try (HibernateSessionFactoryProvider provider = provider("uninitialized")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistLifecycleAndLock(sessionFactory);
      SecuredHibernateRepositoryFactory<String> factory =
          new SecuredHibernateRepositoryFactory<>(sessionFactory, (context, request) -> {});

      HibernateStorageException failure =
          assertThrows(
              HibernateStorageException.class,
              () -> factory.open(MISSING, "alice"));
      assertEquals(HibernateStorageException.class, failure.getClass());
      assertTrue(failure.getMessage().contains("Git refs are not initialized"));
    }
  }

  @Test
  void policyInfrastructureFailureIsNeverReclassifiedAsMissingOrPersisted() {
    try (HibernateSessionFactoryProvider provider = provider("policy-failure")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      HibernateStorageException expected =
          new HibernateStorageException("authorization database unavailable");
      SecuredHibernateRepositoryFactory<String> factory =
          new SecuredHibernateRepositoryFactory<>(
              sessionFactory,
              (context, request) -> {
                throw expected;
              });

      HibernateStorageException actual =
          assertThrows(
              HibernateStorageException.class,
              () -> factory.open(MISSING, "alice"));
      assertSame(expected, actual);
      assertNoRepositoryMetadata(sessionFactory);
    }
  }

  private static void assertNoRepositoryMetadata(SessionFactory sessionFactory) {
    try (Session session = sessionFactory.openSession()) {
      assertNull(session.find(GitRepositoryLifecycleEntity.class, MISSING.value()));
      assertNull(session.find(GitRepositoryLockEntity.class, MISSING.value()));
    }
  }

  private static void persistLifecycleOnly(SessionFactory sessionFactory) {
    try (Session session = sessionFactory.openSession()) {
      Transaction transaction = session.beginTransaction();
      GitRepositoryLifecycleEntity lifecycle = new GitRepositoryLifecycleEntity();
      lifecycle.setRepositoryName(MISSING.value());
      lifecycle.setCreatedAt(Instant.now());
      session.persist(lifecycle);
      transaction.commit();
    }
  }

  private static void persistLifecycleAndLock(SessionFactory sessionFactory) {
    try (Session session = sessionFactory.openSession()) {
      Transaction transaction = session.beginTransaction();
      Instant createdAt = Instant.now();
      GitRepositoryLifecycleEntity lifecycle = new GitRepositoryLifecycleEntity();
      lifecycle.setRepositoryName(MISSING.value());
      lifecycle.setCreatedAt(createdAt);
      session.persist(lifecycle);
      GitRepositoryLockEntity lock = new GitRepositoryLockEntity();
      lock.setRepositoryName(MISSING.value());
      lock.setCreatedAt(createdAt);
      session.persist(lock);
      transaction.commit();
    }
  }

  private static HibernateSessionFactoryProvider provider(String purpose) {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:secured-missing-"
            + purpose
            + "-"
            + DATABASE_COUNTER.incrementAndGet()
            + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return new HibernateSessionFactoryProvider(properties);
  }
}
