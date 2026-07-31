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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;

class MemoryFirstPackStagingH2Test {

  @Test
  void publishesSmallExtensionDirectlyFromMemoryAndReleasesBudget() throws Exception {
    String repositoryName = "memory-first-inline";
    byte[] payload = deterministicBytes(12_345, 17);
    long processBaseline = PackExtensionStagingBuffer.retainedMemoryBytes();

    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      HibernateTransactionContext context =
          new HibernateTransactionContext(provider.getSessionFactory());
      ReadAheadHibernateObjDatabase database =
          (ReadAheadHibernateObjDatabase) repository.getObjectDatabase();
      StagedPackExtensionStore store =
          new StagedPackExtensionStore(repositoryName, context);
      DfsPackDescription description = database.newPack(PackSource.RECEIVE);

      writeAndDescribe(store, description, PackExt.PACK, payload);
      assertEquals(1, store.stagedExtensionCount());
      assertEquals(1, store.stagedMemoryExtensionCount());
      assertEquals(0, store.stagedFileExtensionCount());
      assertEquals(payload.length, store.stagedMemoryBytes());
      assertTrue(PackExtensionStagingBuffer.retainedMemoryBytes() > processBaseline);

      StagedPackExtensionStore.CommitResult result =
          store.commit(List.of(description), null);

      assertTrue(result.completeMetadata());
      assertEquals(1, result.committedExtensions().size());
      assertArrayEquals(
          payload, inlineData(provider, repositoryName, baseName(description), "pack"));
      assertEquals(0, store.stagedExtensionCount());
      assertEquals(processBaseline, PackExtensionStagingBuffer.retainedMemoryBytes());
    }
  }

  @Test
  void spillsThresholdCrossingExtensionAndPublishesEveryChunk() throws Exception {
    String repositoryName = "memory-first-spill";
    byte[] payload =
        deterministicBytes(HibernateObjDatabase.PACK_CHUNK_SIZE + 37, 29);
    long processBaseline = PackExtensionStagingBuffer.retainedMemoryBytes();

    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      HibernateTransactionContext context =
          new HibernateTransactionContext(provider.getSessionFactory());
      ReadAheadHibernateObjDatabase database =
          (ReadAheadHibernateObjDatabase) repository.getObjectDatabase();
      StagedPackExtensionStore store =
          new StagedPackExtensionStore(repositoryName, context);
      DfsPackDescription description = database.newPack(PackSource.RECEIVE);

      try (DfsOutputStream stream = store.open(description, PackExt.PACK)) {
        int firstLength = PackExtensionStagingBuffer.MAX_MEMORY_BYTES - 19;
        stream.write(payload, 0, firstLength);
        ByteBuffer beforeSpill = ByteBuffer.allocate(31);
        assertEquals(31, stream.read(73, beforeSpill));
        assertArrayEquals(
            java.util.Arrays.copyOfRange(payload, 73, 104), beforeSpill.flip().array());

        stream.write(payload, firstLength, payload.length - firstLength);
        ByteBuffer acrossSpill = ByteBuffer.allocate(43);
        int offset = firstLength - 13;
        assertEquals(43, stream.read(offset, acrossSpill));
        assertArrayEquals(
            java.util.Arrays.copyOfRange(payload, offset, offset + 43),
            acrossSpill.flip().array());
      }
      description.addFileExt(PackExt.PACK);
      description.setFileSize(PackExt.PACK, payload.length);

      assertEquals(0, store.stagedMemoryExtensionCount());
      assertEquals(1, store.stagedFileExtensionCount());
      assertEquals(processBaseline, PackExtensionStagingBuffer.retainedMemoryBytes());

      store.commit(List.of(description), null);

      assertArrayEquals(
          payload, chunkedData(provider, repositoryName, baseName(description), "pack"));
      assertEquals(0, store.stagedExtensionCount());
      assertEquals(processBaseline, PackExtensionStagingBuffer.retainedMemoryBytes());
    }
  }

  @Test
  void rollbackReleasesMemoryWithoutStartingPublication() throws Exception {
    String repositoryName = "memory-first-rollback";
    byte[] payload = deterministicBytes(4_321, 41);
    long processBaseline = PackExtensionStagingBuffer.retainedMemoryBytes();

    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      HibernateTransactionContext context =
          new HibernateTransactionContext(provider.getSessionFactory());
      ReadAheadHibernateObjDatabase database =
          (ReadAheadHibernateObjDatabase) repository.getObjectDatabase();
      StagedPackExtensionStore store =
          new StagedPackExtensionStore(repositoryName, context);
      DfsPackDescription description = database.newPack(PackSource.RECEIVE);
      writeAndDescribe(store, description, PackExt.PACK, payload);

      store.rollback(List.of(description));

      assertEquals(0, store.stagedExtensionCount());
      assertEquals(processBaseline, PackExtensionStagingBuffer.retainedMemoryBytes());
      assertFalse(packExists(provider, repositoryName, baseName(description)));
    }
  }

  private static void writeAndDescribe(
      StagedPackExtensionStore store,
      DfsPackDescription description,
      PackExt extension,
      byte[] data)
      throws IOException {
    try (DfsOutputStream stream = store.open(description, extension)) {
      stream.write(data, 0, data.length);
    }
    description.addFileExt(extension);
    description.setFileSize(extension, data.length);
  }

  private static byte[] inlineData(
      HibernateSessionFactoryProvider provider,
      String repositoryName,
      String packName,
      String extension) {
    try (Session session = provider.getSessionFactory().openSession()) {
      Object[] row =
          session
              .createQuery(
                  "SELECT p.data, p.committed, p.writeToken, p.writeLeaseUntil "
                      + "FROM GitPackEntity p WHERE p.repositoryName = :repo "
                      + "AND p.packName = :name AND p.packExtension = :extension",
                  Object[].class)
              .setParameter("repo", repositoryName)
              .setParameter("name", packName)
              .setParameter("extension", extension)
              .getSingleResult();
      assertTrue((Boolean) row[1]);
      assertNull(row[2]);
      assertNull(row[3]);
      return (byte[]) row[0];
    }
  }

  private static byte[] chunkedData(
      HibernateSessionFactoryProvider provider,
      String repositoryName,
      String packName,
      String extension) {
    try (Session session = provider.getSessionFactory().openSession()) {
      Object[] row =
          session
              .createQuery(
                  "SELECT p.id, p.data, p.committed FROM GitPackEntity p "
                      + "WHERE p.repositoryName = :repo AND p.packName = :name "
                      + "AND p.packExtension = :extension",
                  Object[].class)
              .setParameter("repo", repositoryName)
              .setParameter("name", packName)
              .setParameter("extension", extension)
              .getSingleResult();
      assertNull(row[1]);
      assertTrue((Boolean) row[2]);
      List<byte[]> chunks =
          session
              .createQuery(
                  "SELECT c.data FROM GitPackChunkEntity c WHERE c.packId = :packId "
                      + "ORDER BY c.chunkIndex",
                  byte[].class)
              .setParameter("packId", row[0])
              .getResultList();
      int length = chunks.stream().mapToInt(data -> data.length).sum();
      byte[] combined = new byte[length];
      int offset = 0;
      for (byte[] chunk : chunks) {
        System.arraycopy(chunk, 0, combined, offset, chunk.length);
        offset += chunk.length;
      }
      return combined;
    }
  }

  private static boolean packExists(
      HibernateSessionFactoryProvider provider, String repositoryName, String packName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
              .createQuery(
                  "SELECT COUNT(p) FROM GitPackEntity p WHERE p.repositoryName = :repo "
                      + "AND p.packName = :name",
                  Long.class)
              .setParameter("repo", repositoryName)
              .setParameter("name", packName)
              .getSingleResult()
          > 0;
    }
  }

  private static String baseName(DfsPackDescription description) {
    String fileName = description.getFileName(PackExt.PACK);
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
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

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:memory-first-staging-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.enabled", "false");
    properties.put("hibernate.connection.pool_size", "4");
    return new HibernateSessionFactoryProvider(properties);
  }
}
