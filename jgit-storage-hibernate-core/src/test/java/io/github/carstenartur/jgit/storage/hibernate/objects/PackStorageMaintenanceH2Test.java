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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.PackCleanupResult;
import io.github.carstenartur.jgit.storage.hibernate.PackStorageMaintenance;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackChunkEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.junit.jupiter.api.Test;

class PackStorageMaintenanceH2Test {

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();

  @Test
  void deletesOnlyExpiredUncommittedWritersAndAllowsLeasedWriterToContinue() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository ignored =
            HibernateRepository.create(provider.getSessionFactory(), "maintenance-repo")) {
      Instant now = Instant.parse("2026-07-28T03:00:00Z");
      Instant old = now.minusSeconds(172_800);
      Instant cutoff = now.minusSeconds(86_400);

      HibernateObjDatabase.HibernatePackOutputStream activeStream =
          new HibernateObjDatabase.HibernatePackOutputStream(
              new HibernateTransactionContext(provider.getSessionFactory()),
              "maintenance-repo",
              "active-pack",
              "pack");
      byte[] first = bytes(1_200_000, 11);
      byte[] second = bytes(700_000, 29);
      activeStream.write(first, 0, first.length);
      activeStream.flush();
      agePack(provider, "active-pack", old, now.plusSeconds(3_600));

      persistPack(provider, "expired-pack", old, now.minusSeconds(1), false, 7);
      persistPack(provider, "legacy-orphan", old, null, false, 9);
      persistPack(provider, "published-pack", old, now.minusSeconds(1), true, 13);

      PackStorageMaintenance maintenance =
          new PackStorageMaintenance(provider.getSessionFactory());
      PackCleanupResult firstCleanup =
          maintenance.deleteExpiredUncommittedPacks(
              new RepositoryName("maintenance-repo"), cutoff, now);

      assertEquals(new PackCleanupResult(2, 2, 16), firstCleanup);
      assertPackExists(provider, "active-pack", true);
      assertPackExists(provider, "published-pack", true);
      assertPackExists(provider, "expired-pack", false);
      assertPackExists(provider, "legacy-orphan", false);

      activeStream.write(second, 0, second.length);
      activeStream.close();
      assertArrayEquals(concatenate(first, second), readChunks(provider, "active-pack"));

      agePack(provider, "active-pack", old, now.minusSeconds(1));
      PackCleanupResult secondCleanup =
          maintenance.deleteExpiredUncommittedPacks(
              new RepositoryName("maintenance-repo"), cutoff, now);
      assertEquals(1, secondCleanup.packRows());
      assertTrue(secondCleanup.chunkRows() > 0);
      assertEquals(first.length + second.length, secondCleanup.payloadBytes());
      assertPackExists(provider, "active-pack", false);
      assertPackExists(provider, "published-pack", true);
    }
  }

  private static void persistPack(
      HibernateSessionFactoryProvider provider,
      String packName,
      Instant createdAt,
      Instant leaseUntil,
      boolean committed,
      int size) {
    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      GitPackEntity pack = new GitPackEntity();
      pack.setRepositoryName("maintenance-repo");
      pack.setPackName(packName);
      pack.setPackExtension("pack");
      pack.setData(null);
      pack.setFileSize(size);
      pack.setCommitted(committed);
      pack.setCreatedAt(createdAt);
      pack.setCommittedAt(committed ? createdAt.plusSeconds(1) : null);
      pack.setWriteToken(committed ? null : "writer-" + packName);
      pack.setWriteLeaseUntil(committed ? null : leaseUntil);
      session.persist(pack);
      session.flush();

      GitPackChunkEntity chunk = new GitPackChunkEntity();
      chunk.setPackId(pack.getId());
      chunk.setChunkIndex(0);
      chunk.setChunkSize(size);
      chunk.setData(bytes(size, size));
      session.persist(chunk);
      transaction.commit();
    }
  }

  private static void agePack(
      HibernateSessionFactoryProvider provider,
      String packName,
      Instant createdAt,
      Instant leaseUntil) {
    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      session
          .createMutationQuery(
              "UPDATE GitPackEntity p SET p.createdAt = :createdAt, "
                  + "p.writeLeaseUntil = :leaseUntil WHERE p.repositoryName = :repo "
                  + "AND p.packName = :name")
          .setParameter("createdAt", createdAt)
          .setParameter("leaseUntil", leaseUntil)
          .setParameter("repo", "maintenance-repo")
          .setParameter("name", packName)
          .executeUpdate();
      transaction.commit();
    }
  }

  private static void assertPackExists(
      HibernateSessionFactoryProvider provider, String packName, boolean expected) {
    try (Session session = provider.getSessionFactory().openSession()) {
      GitPackEntity pack =
          session
              .createQuery(
                  "FROM GitPackEntity p WHERE p.repositoryName = :repo AND p.packName = :name",
                  GitPackEntity.class)
              .setParameter("repo", "maintenance-repo")
              .setParameter("name", packName)
              .uniqueResult();
      if (expected) {
        assertNotNull(pack);
      } else {
        assertNull(pack);
      }
    }
  }

  private static byte[] readChunks(HibernateSessionFactoryProvider provider, String packName)
      throws Exception {
    try (Session session = provider.getSessionFactory().openSession()) {
      Long packId =
          session
              .createQuery(
                  "SELECT p.id FROM GitPackEntity p WHERE p.repositoryName = :repo "
                      + "AND p.packName = :name",
                  Long.class)
              .setParameter("repo", "maintenance-repo")
              .setParameter("name", packName)
              .getSingleResult();
      List<byte[]> chunks =
          session
              .createQuery(
                  "SELECT c.data FROM GitPackChunkEntity c WHERE c.packId = :packId "
                      + "ORDER BY c.chunkIndex",
                  byte[].class)
              .setParameter("packId", packId)
              .getResultList();
      try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        for (byte[] chunk : chunks) {
          output.write(chunk);
        }
        return output.toByteArray();
      }
    }
  }

  private static byte[] concatenate(byte[] first, byte[] second) {
    byte[] result = new byte[first.length + second.length];
    System.arraycopy(first, 0, result, 0, first.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }

  private static byte[] bytes(int size, int seed) {
    byte[] data = new byte[size];
    int value = seed;
    for (int index = 0; index < size; index++) {
      value = value * 1664525 + 1013904223;
      data[index] = (byte) (value >>> 24);
    }
    return data;
  }

  private static HibernateSessionFactoryProvider provider() {
    String name = "pack-maintenance-" + TEST_COUNTER.incrementAndGet();
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return new HibernateSessionFactoryProvider(properties);
  }
}
