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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import java.nio.ByteBuffer;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.Session;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ChunkedPackStorageCapacityIT {

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();

  @ParameterizedTest(name = "{0} MiB")
  @ValueSource(ints = {1, 16, 128})
  @Timeout(300)
  void persistsAndRandomReadsCapacityEnvelope(int mebibytes) throws Exception {
    String repositoryName = "capacity-" + mebibytes + "-" + TEST_COUNTER.incrementAndGet();
    try (HibernateSessionFactoryProvider provider = provider(repositoryName);
        HibernateRepository ignored =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      HibernateObjDatabase.HibernatePackOutputStream output =
          new HibernateObjDatabase.HibernatePackOutputStream(
              new HibernateTransactionContext(provider.getSessionFactory()),
              repositoryName,
              "pack-capacity",
              "pack");
      byte[] block = deterministicBlock();
      for (int index = 0; index < mebibytes; index++) {
        output.write(block, 0, block.length);
      }
      output.close();

      long expectedSize = (long) mebibytes * HibernateObjDatabase.PACK_CHUNK_SIZE;
      Long packId;
      try (Session session = provider.getSessionFactory().openSession()) {
        GitPackEntity pack =
            session
                .createQuery(
                    "FROM GitPackEntity p WHERE p.repositoryName = :repo "
                        + "AND p.packName = :name",
                    GitPackEntity.class)
                .setParameter("repo", repositoryName)
                .setParameter("name", "pack-capacity")
                .getSingleResult();
        packId = pack.getId();
        assertEquals(expectedSize, pack.getFileSize());
        Long chunks =
            session
                .createQuery(
                    "SELECT COUNT(c) FROM GitPackChunkEntity c WHERE c.packId = :packId",
                    Long.class)
                .setParameter("packId", packId)
                .getSingleResult();
        assertEquals(mebibytes, chunks.intValue());
        Integer maximumChunk =
            session
                .createQuery(
                    "SELECT MAX(c.chunkSize) FROM GitPackChunkEntity c WHERE c.packId = :packId",
                    Integer.class)
                .setParameter("packId", packId)
                .getSingleResult();
        assertEquals(HibernateObjDatabase.PACK_CHUNK_SIZE, maximumChunk.intValue());
      }

      try (HibernateObjDatabase.ChunkedReadableChannel channel =
          new HibernateObjDatabase.ChunkedReadableChannel(
              provider.getSessionFactory(), packId, expectedSize)) {
        verifySample(channel, block, 0);
        verifySample(channel, block, expectedSize / 2);
        verifySample(channel, block, expectedSize - 64);
        assertTrue(channel.size() >= 1024L * 1024L);
      }
    }
  }

  private static void verifySample(
      HibernateObjDatabase.ChunkedReadableChannel channel, byte[] block, long position)
      throws Exception {
    channel.position(position);
    ByteBuffer destination = ByteBuffer.allocate(64);
    assertEquals(64, channel.read(destination));
    byte[] expected = new byte[64];
    int blockOffset = (int) (position % block.length);
    for (int index = 0; index < expected.length; index++) {
      expected[index] = block[(blockOffset + index) % block.length];
    }
    assertArrayEquals(expected, destination.array());
  }

  private static byte[] deterministicBlock() {
    byte[] data = new byte[HibernateObjDatabase.PACK_CHUNK_SIZE];
    int value = 17;
    for (int index = 0; index < data.length; index++) {
      value = value * 1103515245 + 12345;
      data[index] = (byte) (value >>> 16);
    }
    return data;
  }

  private static HibernateSessionFactoryProvider provider(String name) {
    Properties properties = new Properties();
    properties.put("hibernate.connection.url", "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return new HibernateSessionFactoryProvider(properties);
  }
}
