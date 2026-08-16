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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SecuredRepositoryMissingH2Test {

  private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();
  private static final RepositoryName MISSING = new RepositoryName("missing-secured-repository");

  @Test
  void authorizedMissingRepositoryHasAStableTypedResult() {
    try (HibernateSessionFactoryProvider provider = provider("missing")) {
      SecuredHibernateRepositoryFactory<String> factory =
          new SecuredHibernateRepositoryFactory<>(
              provider.getSessionFactory(), (context, request) -> {});

      RepositoryDoesNotExistException missing =
          assertThrows(
              RepositoryDoesNotExistException.class,
              () -> factory.open(MISSING, "alice"));
      assertEquals(MISSING, missing.repositoryName());
      assertEquals("Repository " + MISSING + " does not exist", missing.getMessage());
    }
  }

  @Test
  void policyInfrastructureFailureIsNeverReclassifiedAsMissing() {
    try (HibernateSessionFactoryProvider provider = provider("policy-failure")) {
      HibernateStorageException expected =
          new HibernateStorageException("authorization database unavailable");
      SecuredHibernateRepositoryFactory<String> factory =
          new SecuredHibernateRepositoryFactory<>(
              provider.getSessionFactory(),
              (context, request) -> {
                throw expected;
              });

      HibernateStorageException actual =
          assertThrows(
              HibernateStorageException.class,
              () -> factory.open(MISSING, "alice"));
      assertSame(expected, actual);
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
