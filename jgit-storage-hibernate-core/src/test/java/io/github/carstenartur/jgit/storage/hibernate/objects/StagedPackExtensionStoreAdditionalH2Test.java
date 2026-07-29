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
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackChunkEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.dfs.ReadableChannel;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.junit.jupiter.api.Test;

class StagedPackExtensionStoreAdditionalH2Test {

  @Test
  void openStreamSupportsRandomReadAndRejectsInvalidOrClosedAccess() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), "staged-stream")) {
      repository.create(true);
      ReadAheadHibernateObjDatabase database = database(repository);
      DfsPackDescription description = database.newPack(PackSource.RECEIVE);
      byte[] data = deterministicBytes(128, 17);
      DfsOutputStream stream = database.writeFile(description, PackExt.PACK);

      assertThrows(IndexOutOfBoundsException.class, () -> stream.write(data, -1, 1));
      stream.write(data, 0, data.length);
      stream.flush();
      ByteBuffer selected = ByteBuffer.allocate(24);
      assertEquals(24, stream.read(37, selected));
      assertArrayEquals(
          java.util.Arrays.copyOfRange(data, 37, 61), selected.flip().array());
      assertEquals(-1, stream.read(data.length, ByteBuffer.allocate(1)));
      assertThrows(
          IllegalArgumentException.class, () -> stream.read(-1, ByteBuffer.allocate(1)));

      stream.close();
      stream.close();
      assertThrows(IOException.class, () -> stream.write(data, 0, 1));
      assertThrows(IOException.class, stream::flush);
      assertThrows(IOException.class, () -> stream.read(0, ByteBuffer.allocate(1)));

      description.addFileExt(PackExt.PACK);
      database.rollbackPack(List.of(description));
      assertEquals(0, database.stagedExtensionCount());
    }
  }

  @Test
  void duplicateOpenAndRegistrationAreRejectedWithoutLosingFirstStaging() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), "staged-duplicate")) {
      repository.create(true);
      ReadAheadHibernateObjDatabase database = database(repository);
      DfsPackDescription description = database.newPack(PackSource.RECEIVE);
      DfsOutputStream first = database.writeFile(description, PackExt.PACK);
      DfsOutputStream competing = database.writeFile(description, PackExt.PACK);
      first.write(new byte[] {1, 2, 3}, 0, 3);
      competing.write(new byte[] {4, 5}, 0, 2);
      first.close();

      assertThrows(IOException.class, competing::close);
      assertThrows(IOException.class, () -> database.writeFile(description, PackExt.PACK));
      assertEquals(1, database.stagedExtensionCount());
      description.addFileExt(PackExt.PACK);
      database.rollbackPack(List.of(description));
    }
  }

  @Test
  void publishesAndReadsChunkedExtension() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), "staged-chunked")) {
      repository.create(true);
      ReadAheadHibernateObjDatabase database = database(repository);
      DfsPackDescription description = database.newPack(PackSource.RECEIVE);
      byte[] payload =
          deterministicBytes(HibernateObjDatabase.PACK_CHUNK_SIZE + 37, 43);
      writeAndDescribe(database, description, PackExt.PACK, payload);

      database.commitPackImpl(List.of(description), null);
      List<GitPackChunkEntity> chunks = chunks(provider, "staged-chunked", baseName(description));
      assertEquals(2, chunks.size());
      assertEquals(HibernateObjDatabase.PACK_CHUNK_SIZE, chunks.get(0).getChunkSize());
      assertEquals(37, chunks.get(1).getChunkSize());

      try (ReadableChannel channel = database.openFile(description, PackExt.PACK)) {
        ByteBuffer loaded = ByteBuffer.allocate(payload.length);
        assertEquals(payload.length, channel.read(loaded));
        assertArrayEquals(payload, loaded.array());
        assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
      }
    }
  }

  @Test
  void publishesLegacyUncommittedRowAndRejectsMissingExpectedExtension() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), "legacy-publication")) {
      repository.create(true);
      ReadAheadHibernateObjDatabase database = database(repository);
      DfsPackDescription legacy = database.newPack(PackSource.RECEIVE);
      legacy.addFileExt(PackExt.PACK);
      persistPack(provider, "legacy-publication", baseName(legacy), "pack", false);

      database.commitPackImpl(List.of(legacy), null);
      assertTrue(pack(provider, "legacy-publication", baseName(legacy), "pack").isCommitted());

      DfsPackDescription missing = database.newPack(PackSource.RECEIVE);
      missing.addFileExt(PackExt.INDEX);
      assertThrows(IOException.class, () -> database.commitPackImpl(List.of(missing), null));

      DfsPackDescription empty = database.newPack(PackSource.RECEIVE);
      assertThrows(IOException.class, () -> database.commitPackImpl(List.of(empty), null));
    }
  }

  @Test
  void replacementAndLegacyRollbackRemoveDatabaseRows() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), "staged-replace")) {
      repository.create(true);
      ReadAheadHibernateObjDatabase database = database(repository);
      DfsPackDescription replaced = database.newPack(PackSource.RECEIVE);
      replaced.addFileExt(PackExt.PACK);
      persistPack(provider, "staged-replace", baseName(replaced), "pack", true);

      DfsPackDescription replacement = database.newPack(PackSource.RECEIVE);
      writeAndDescribe(database, replacement, PackExt.PACK, new byte[] {9, 8, 7});
      database.commitPackImpl(List.of(replacement), List.of(replaced));
      assertFalse(exists(provider, "staged-replace", baseName(replaced)));
      assertTrue(exists(provider, "staged-replace", baseName(replacement)));

      DfsPackDescription abandonedLegacy = database.newPack(PackSource.RECEIVE);
      abandonedLegacy.addFileExt(PackExt.PACK);
      persistPack(provider, "staged-replace", baseName(abandonedLegacy), "pack", false);
      database.rollbackPack(List.of(abandonedLegacy));
      assertFalse(exists(provider, "staged-replace", baseName(abandonedLegacy)));
    }
  }

  private static ReadAheadHibernateObjDatabase database(HibernateRepository repository) {
    return (ReadAheadHibernateObjDatabase) repository.getObjectDatabase();
  }

  private static void writeAndDescribe(
      ReadAheadHibernateObjDatabase database,
      DfsPackDescription description,
      PackExt extension,
      byte[] data)
      throws IOException {
    try (DfsOutputStream stream = database.writeFile(description, extension)) {
      stream.write(data, 0, data.length);
    }
    description.addFileExt(extension);
    description.setFileSize(extension, data.length);
  }

  private static void persistPack(
      HibernateSessionFactoryProvider provider,
      String repositoryName,
      String packName,
      String extension,
      boolean committed) {
    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      GitPackEntity entity = new GitPackEntity();
      entity.setRepositoryName(repositoryName);
      entity.setPackName(packName);
      entity.setPackExtension(extension);
      entity.setData(new byte[] {1});
      entity.setFileSize(1);
      entity.setCommitted(committed);
      entity.setCreatedAt(Instant.now());
      entity.setCommittedAt(committed ? Instant.now() : null);
      entity.setWriteToken(committed ? null : UUID.randomUUID().toString());
      entity.setWriteLeaseUntil(committed ? null : Instant.now().plusSeconds(300));
      session.persist(entity);
      transaction.commit();
    }
  }

  private static GitPackEntity pack(
      HibernateSessionFactoryProvider provider,
      String repositoryName,
      String packName,
      String extension) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "FROM GitPackEntity p WHERE p.repositoryName = :repo "
                  + "AND p.packName = :name AND p.packExtension = :ext",
              GitPackEntity.class)
          .setParameter("repo", repositoryName)
          .setParameter("name", packName)
          .setParameter("ext", extension)
          .getSingleResult();
    }
  }

  private static boolean exists(
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

  private static List<GitPackChunkEntity> chunks(
      HibernateSessionFactoryProvider provider, String repositoryName, String packName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      Long packId =
          session
              .createQuery(
                  "SELECT p.id FROM GitPackEntity p WHERE p.repositoryName = :repo "
                      + "AND p.packName = :name AND p.packExtension = 'pack'",
                  Long.class)
              .setParameter("repo", repositoryName)
              .setParameter("name", packName)
              .getSingleResult();
      return session
          .createQuery(
              "FROM GitPackChunkEntity c WHERE c.packId = :packId ORDER BY c.chunkIndex",
              GitPackChunkEntity.class)
          .setParameter("packId", packId)
          .getResultList();
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
        "jdbc:h2:mem:staged-additional-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.connection.pool_size", "2");
    return new HibernateSessionFactoryProvider(properties);
  }
}
