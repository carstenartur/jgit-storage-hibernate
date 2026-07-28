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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackChunkEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;

class ReadAheadHibernateObjDatabaseH2Test {

  @Test
  void loadsASequentialThreeChunkWindowWithOneHibernateQuery() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider()) {
      PersistedPack pack = persistPack(provider, false);
      Statistics statistics = provider.getSessionFactory().getStatistics();
      statistics.clear();

      try (var channel =
          new ReadAheadHibernateObjDatabase.ReadAheadChunkedReadableChannel(
              provider.getSessionFactory(), pack.packId(), pack.expected().length)) {
        channel.setReadAheadBytes(pack.expected().length);
        ByteBuffer destination = ByteBuffer.allocate(pack.expected().length);

        assertEquals(pack.expected().length, channel.read(destination));
        assertArrayEquals(pack.expected(), destination.array());
        assertEquals(
            1L,
            statistics.getQueryExecutionCount(),
            "One ordered query should fill the complete bounded read-ahead window");
      }
    }
  }

  @Test
  void reportsAMissingChunkInsideThePrefetchWindow() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider()) {
      PersistedPack pack = persistPack(provider, true);
      try (var channel =
          new ReadAheadHibernateObjDatabase.ReadAheadChunkedReadableChannel(
              provider.getSessionFactory(), pack.packId(), pack.expected().length)) {
        channel.setReadAheadBytes(pack.expected().length);

        IOException exception =
            assertThrows(IOException.class, () -> channel.read(ByteBuffer.allocate(1)));
        assertTrue(exception.getMessage().contains("Missing chunk 1"));
      }
    }
  }

  private static PersistedPack persistPack(
      HibernateSessionFactoryProvider provider, boolean omitMiddleChunk) {
    byte[] first = bytes(HibernateObjDatabase.PACK_CHUNK_SIZE, 11);
    byte[] middle = bytes(HibernateObjDatabase.PACK_CHUNK_SIZE, 29);
    byte[] last = bytes(257, 47);
    byte[] expected = concatenate(first, middle, last);

    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      GitPackEntity pack = new GitPackEntity();
      pack.setRepositoryName("read-ahead");
      pack.setPackName("pack-test");
      pack.setPackExtension("pack");
      pack.setData(null);
      pack.setFileSize(expected.length);
      pack.setCommitted(true);
      pack.setCreatedAt(Instant.now());
      pack.setCommittedAt(Instant.now());
      session.persist(pack);
      session.flush();

      persistChunk(session, pack.getId(), 0, first);
      if (!omitMiddleChunk) {
        persistChunk(session, pack.getId(), 1, middle);
      }
      persistChunk(session, pack.getId(), 2, last);
      transaction.commit();
      return new PersistedPack(pack.getId(), expected);
    }
  }

  private static void persistChunk(Session session, Long packId, int chunkIndex, byte[] data) {
    GitPackChunkEntity chunk = new GitPackChunkEntity();
    chunk.setPackId(packId);
    chunk.setChunkIndex(chunkIndex);
    chunk.setChunkSize(data.length);
    chunk.setData(data);
    session.persist(chunk);
  }

  private static byte[] concatenate(byte[]... parts) {
    int length = 0;
    for (byte[] part : parts) {
      length += part.length;
    }
    byte[] result = new byte[length];
    int offset = 0;
    for (byte[] part : parts) {
      System.arraycopy(part, 0, result, offset, part.length);
      offset += part.length;
    }
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
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:read-ahead-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.generate_statistics", "true");
    properties.put("hibernate.connection.pool_size", "2");
    return new HibernateSessionFactoryProvider(properties);
  }

  private record PersistedPack(Long packId, byte[] expected) {}
}
