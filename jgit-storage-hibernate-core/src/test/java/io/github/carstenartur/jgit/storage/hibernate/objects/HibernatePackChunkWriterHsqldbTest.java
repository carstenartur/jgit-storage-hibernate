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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;

class HibernatePackChunkWriterHsqldbTest {

  @Test
  void directJdbcWriterPublishesAndReopensLargePackPayload() throws Exception {
    String databaseName = "jdbc-hsqldb-" + UUID.randomUUID();
    String repositoryName = "jdbc-hsqldb-repository";
    byte[] payload = new byte[2 * 1024 * 1024 + 257];
    new Random(0x4a4442434853514cL).nextBytes(payload);
    ObjectId objectId;

    try (HibernateSessionFactoryProvider provider =
        new HibernateSessionFactoryProvider(properties(databaseName))) {
      try (HibernateRepository repository =
          HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
        repository.create(true);
        try (ObjectInserter inserter = repository.newObjectInserter()) {
          objectId = inserter.insert(Constants.OBJ_BLOB, payload);
          inserter.flush();
        }
      }

      assertTrue(chunkCount(provider) > 0L, "The large payload must use chunk rows");
      try (HibernateRepository reopened =
          HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
        assertArrayEquals(payload, reopened.open(objectId).getBytes());
      }
    }
  }

  private static long chunkCount(HibernateSessionFactoryProvider provider) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery("SELECT COUNT(c) FROM GitPackChunkEntity c", Long.class)
          .getSingleResult();
    }
  }

  private static Properties properties(String databaseName) {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", "jdbc:hsqldb:mem:" + databaseName);
    properties.put("hibernate.connection.driver_class", "org.hsqldb.jdbc.JDBCDriver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.HSQLDialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.connection.pool_size", "4");
    properties.put("jgit.storage.hibernate.pack.chunk_writer", "jdbc");
    return properties;
  }
}
