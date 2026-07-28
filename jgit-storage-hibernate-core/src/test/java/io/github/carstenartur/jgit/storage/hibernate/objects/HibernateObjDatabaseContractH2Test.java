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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackChunkEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
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
  void persistsBoundedChunksAfterEarlyFlushAndSupportsRandomReads() throws Exception {
    HibernateTransactionContext transactionContext =
        new HibernateTransactionContext(provider.getSessionFactory());
    HibernateObjDatabase.HibernatePackOutputStream stream =
        new HibernateObjDatabase.HibernatePackOutputStream(
            transactionContext, "repo", "pack-test", "pack");

    byte[] first = deterministicBytes(HibernateObjDatabase.PACK_CHUNK_SIZE + 97, 3);
    byte[] second = deterministicBytes(HibernateObjDatabase.PACK_CHUNK_SIZE + 31, 97);
    byte[] expected = concatenate(first, second);

    stream.write(first, 0, first.length);
    ByteBuffer beforeFlush = ByteBuffer.allocate(32);
    assertEquals(32, stream.read(HibernateObjDatabase.PACK_CHUNK_SIZE - 8L, beforeFlush));
    assertArrayEquals(
        Arrays.copyOfRange(
            first,
            HibernateObjDatabase.PACK_CHUNK_SIZE - 8,
            HibernateObjDatabase.PACK_CHUNK_SIZE + 24),
        beforeFlush.array());

    stream.flush();
    stream.flush();
    stream.write(second, 0, second.length);
    stream.close();
    stream.close();

    Long packId;
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
      packId = entity.getId();
      assertNull(entity.getData());
      assertEquals(expected.length, entity.getFileSize());
      assertFalse(entity.isCommitted());

      List<GitPackChunkEntity> chunks =
          session
              .createQuery(
                  "FROM GitPackChunkEntity c WHERE c.packId = :packId ORDER BY c.chunkIndex",
                  GitPackChunkEntity.class)
              .setParameter("packId", packId)
              .getResultList();
      assertEquals(3, chunks.size());
      assertTrue(
          chunks.stream()
              .allMatch(
                  chunk -> chunk.getData().length <= HibernateObjDatabase.PACK_CHUNK_SIZE));
      assertArrayEquals(expected, concatenate(chunks));
    }

    try (HibernateObjDatabase.ChunkedReadableChannel channel =
        new HibernateObjDatabase.ChunkedReadableChannel(
            provider.getSessionFactory(), packId, expected.length)) {
      channel.position(HibernateObjDatabase.PACK_CHUNK_SIZE - 11L);
      ByteBuffer acrossBoundary = ByteBuffer.allocate(73);
      assertEquals(73, channel.read(acrossBoundary));
      assertArrayEquals(
          Arrays.copyOfRange(
              expected,
              HibernateObjDatabase.PACK_CHUNK_SIZE - 11,
              HibernateObjDatabase.PACK_CHUNK_SIZE - 11 + 73),
          acrossBoundary.array());
      assertEquals(HibernateObjDatabase.PACK_CHUNK_SIZE, channel.blockSize());
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

  private static byte[] deterministicBytes(int length, int seed) {
    byte[] result = new byte[length];
    int value = seed;
    for (int index = 0; index < result.length; index++) {
      value = value * 1103515245 + 12345;
      result[index] = (byte) (value >>> 16);
    }
    return result;
  }

  private static byte[] concatenate(byte[] first, byte[] second) {
    byte[] result = Arrays.copyOf(first, first.length + second.length);
    System.arraycopy(second, 0, result, first.length, second.length);
    return result;
  }

  private static byte[] concatenate(List<GitPackChunkEntity> chunks) throws IOException {
    try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      for (GitPackChunkEntity chunk : chunks) {
        assertEquals(chunk.getChunkSize(), chunk.getData().length);
        output.write(chunk.getData());
      }
      return output.toByteArray();
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
