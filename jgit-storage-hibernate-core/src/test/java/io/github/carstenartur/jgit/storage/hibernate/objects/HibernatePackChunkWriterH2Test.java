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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitRepositoryLifecycleEntity;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;
import java.util.UUID;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.junit.jupiter.api.Test;

class HibernatePackChunkWriterH2Test {

  private static final String STATELESS_ROLLBACK_DATABASE =
      "stateless-rollback-" + UUID.randomUUID();
  private static final String JDBC_ROLLBACK_DATABASE = "jdbc-rollback-" + UUID.randomUUID();

  @Test
  void statelessWriterSharesTheOuterResourceLocalTransaction() {
    verifySharedRollback(
        STATELESS_ROLLBACK_DATABASE,
        HibernatePackChunkWriter.STATELESS_MODE,
        HibernatePackChunkWriter::stateless);
  }

  @Test
  void directJdbcWriterSharesTheOuterResourceLocalTransaction() {
    verifySharedRollback(
        JDBC_ROLLBACK_DATABASE,
        HibernatePackChunkWriter.JDBC_MODE,
        HibernatePackChunkWriter::jdbc);
  }

  @Test
  void defaultWriterRemainsStateful() {
    try (HibernateSessionFactoryProvider provider = provider("default-writer", null);
        Session session = provider.getSessionFactory().openSession()) {
      session.beginTransaction();
      try (HibernatePackChunkWriter writer = HibernatePackChunkWriter.open(session)) {
        assertFalse(writer.stateless());
        assertFalse(writer.jdbc());
      }
      session.getTransaction().rollback();
    }
  }

  @Test
  void rejectsUnknownWriterMode() {
    try (HibernateSessionFactoryProvider provider = provider("unknown-writer", "mystery");
        Session session = provider.getSessionFactory().openSession()) {
      session.beginTransaction();
      assertThrows(
          IllegalArgumentException.class, () -> HibernatePackChunkWriter.open(session));
      session.getTransaction().rollback();
    }
  }

  @Test
  void statelessWriterPublishesAndReopensLargePackPayload() throws Exception {
    verifyLargePackReopen(HibernatePackChunkWriter.STATELESS_MODE, false);
  }

  @Test
  void directJdbcWriterPublishesAndReopensLargePackPayloadWithQualifiedSchema()
      throws Exception {
    verifyLargePackReopen(HibernatePackChunkWriter.JDBC_MODE, true);
  }

  private static void verifySharedRollback(
      String databaseName,
      String mode,
      java.util.function.Predicate<HibernatePackChunkWriter> modePredicate) {
    try (HibernateSessionFactoryProvider provider = provider(databaseName, mode);
        Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      Long packId = persistParent(session);

      List<byte[]> chunks = chunks(3);
      try (HibernatePackChunkWriter writer = HibernatePackChunkWriter.open(session)) {
        assertTrue(modePredicate.test(writer));
        writer.insert(packId, 0, chunks);
      }

      assertEquals(3L, chunkCount(session, packId));
      transaction.rollback();
    }

    try (HibernateSessionFactoryProvider provider = providerForExistingDatabase(databaseName)) {
      assertEquals(0L, totalPackCount(provider));
      assertEquals(0L, totalChunkCount(provider));
    }
  }

  private static void verifyLargePackReopen(String mode, boolean qualifiedSchema) throws Exception {
    String databaseName = mode + "-reopen-" + UUID.randomUUID();
    Properties properties = h2Properties(databaseName);
    properties.put(HibernatePackChunkWriter.MODE_PROPERTY, mode);
    if (qualifiedSchema) {
      properties.put("hibernate.default_schema", "PUBLIC");
    }
    byte[] payload = new byte[2 * 1024 * 1024 + 257];
    new Random(0x53544154454c4553L ^ mode.hashCode()).nextBytes(payload);
    ObjectId objectId;
    String repositoryName = mode + "-reopen";

    try (HibernateSessionFactoryProvider provider =
        new HibernateSessionFactoryProvider(properties)) {
      try (HibernateRepository repository =
          HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
        repository.create(true);
        try (ObjectInserter inserter = repository.newObjectInserter()) {
          objectId = inserter.insert(Constants.OBJ_BLOB, payload);
          inserter.flush();
        }
      }

      assertTrue(totalChunkCount(provider) > 0L);
      try (HibernateRepository reopened =
          HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
        assertArrayEquals(payload, reopened.open(objectId).getBytes());
      }
    }
  }

  private static HibernateSessionFactoryProvider provider(String databaseName, String mode) {
    Properties properties = h2Properties(databaseName);
    if (mode != null) {
      properties.put(HibernatePackChunkWriter.MODE_PROPERTY, mode);
    }
    return new HibernateSessionFactoryProvider(properties);
  }

  private static HibernateSessionFactoryProvider providerForExistingDatabase(String databaseName) {
    return new HibernateSessionFactoryProvider(h2Properties(databaseName));
  }

  private static Properties h2Properties(String databaseName) {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url", "jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "update");
    properties.put("hibernate.show_sql", "false");
    return properties;
  }

  private static Long persistParent(Session session) {
    Instant now = Instant.now();
    GitRepositoryLifecycleEntity lifecycle = new GitRepositoryLifecycleEntity();
    lifecycle.setRepositoryName("rollback");
    lifecycle.setCreatedAt(now);
    session.persist(lifecycle);

    GitPackEntity pack = new GitPackEntity();
    pack.setRepositoryName("rollback");
    pack.setPackName("pack-rollback");
    pack.setPackExtension("pack");
    pack.setData(null);
    pack.setFileSize(3L * 1024L * 1024L);
    pack.setCommitted(false);
    pack.setCreatedAt(now);
    session.persist(pack);
    session.flush();
    return pack.getId();
  }

  private static List<byte[]> chunks(int count) {
    List<byte[]> chunks = new ArrayList<>(count);
    for (int index = 0; index < count; index++) {
      chunks.add(new byte[] {(byte) index, 1, 2, 3});
    }
    return chunks;
  }

  private static long chunkCount(Session session, Long packId) {
    return session
        .createQuery(
            "SELECT COUNT(c) FROM GitPackChunkEntity c WHERE c.packId = :packId", Long.class)
        .setParameter("packId", packId)
        .getSingleResult();
  }

  private static long totalPackCount(HibernateSessionFactoryProvider provider) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery("SELECT COUNT(p) FROM GitPackEntity p", Long.class)
          .getSingleResult();
    }
  }

  private static long totalChunkCount(HibernateSessionFactoryProvider provider) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery("SELECT COUNT(c) FROM GitPackChunkEntity c", Long.class)
          .getSingleResult();
    }
  }
}
