/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import java.io.IOException;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.ObjectId;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HibernateObjDatabaseContractH2Test {

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();

  private HibernateSessionFactoryProvider provider;
  private String databaseName;

  @BeforeEach
  void setUp() {
    databaseName = "obj-contract-" + TEST_COUNTER.incrementAndGet();
    provider = new HibernateSessionFactoryProvider(h2Properties(databaseName));
  }

  @AfterEach
  void tearDown() {
    if (provider != null) {
      provider.close();
    }
  }

  @Test
  void persistsBytesWrittenAfterAnEarlyFlushAndRejectsWritesAfterClose() throws Exception {
    HibernateTransactionContext transactionContext =
        new HibernateTransactionContext(provider.getSessionFactory());
    HibernateObjDatabase.HibernatePackOutputStream stream =
        new HibernateObjDatabase.HibernatePackOutputStream(
            transactionContext, "repo", "pack-test", "pack");

    byte[] first = "first".getBytes(UTF_8);
    byte[] second = "-second".getBytes(UTF_8);
    stream.write(first, 0, first.length);
    stream.flush();
    stream.flush();
    stream.write(second, 0, second.length);
    stream.close();
    stream.close();

    try (Session session = provider.getSessionFactory().openSession()) {
      GitPackEntity entity =
          session
              .createQuery(
                  "FROM GitPackEntity p WHERE p.repositoryName = :repo "
                      + "AND p.packName = :name AND p.packExtension = :ext",
                  GitPackEntity.class)
              .setParameter("repo", "repo")
              .setParameter("name", "pack-test")
              .setParameter("ext", "pack")
              .getSingleResult();
      assertArrayEquals("first-second".getBytes(UTF_8), entity.getData());
      assertEquals("first-second".getBytes(UTF_8).length, entity.getFileSize());
      assertFalse(entity.isCommitted());
    }

    assertThrows(IOException.class, () -> stream.write(first, 0, first.length));
  }

  @Test
  void rejectsNonEmptyShallowBoundariesInsteadOfLosingThemOnRestart() throws Exception {
    try (HibernateRepository repository =
        HibernateRepository.create(provider.getSessionFactory(), "shallow-repo")) {
      repository.create(true);
      assertTrue(repository.getObjectDatabase().getShallowCommits().isEmpty());
      repository.getObjectDatabase().setShallowCommits(Set.of());

      ObjectId boundary = ObjectId.fromString("1111111111111111111111111111111111111111");
      UnsupportedOperationException exception =
          assertThrows(
              UnsupportedOperationException.class,
              () -> repository.getObjectDatabase().setShallowCommits(Set.of(boundary)));
      assertTrue(exception.getMessage().contains("Shallow repositories are not supported"));
    }
  }

  private static Properties h2Properties(String name) {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return properties;
  }
}
