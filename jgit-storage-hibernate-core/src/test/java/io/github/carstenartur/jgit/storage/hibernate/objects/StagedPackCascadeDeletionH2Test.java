/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackChunkEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;

class StagedPackCascadeDeletionH2Test {

  @Test
  void replacesMultipleLogicalPacksWithOneParentDeleteAndDatabaseCascade() throws Exception {
    String repositoryName = "cascade-replacement";
    try (HibernateSessionFactoryProvider provider = provider();
        HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
      repository.create(true);
      ReadAheadHibernateObjDatabase database =
          (ReadAheadHibernateObjDatabase) repository.getObjectDatabase();
      PersistedPack first = persistChunkedPack(provider, repositoryName, "pack-old-first", 11);
      PersistedPack second = persistChunkedPack(provider, repositoryName, "pack-old-second", 29);

      List<DfsPackDescription> before = database.listPacks();
      DfsPackDescription firstDescription = description(before, first.packName());
      DfsPackDescription secondDescription = description(before, second.packName());

      DfsPackDescription replacement = database.newPack(PackSource.COMPACT);
      write(database, replacement, PackExt.PACK, new byte[] {3, 1, 4, 1, 5});

      Statistics statistics = provider.getSessionFactory().getStatistics();
      statistics.clear();
      database.commitPackImpl(
          List.of(replacement), List.of(firstDescription, secondDescription));

      assertEquals(
          0L,
          statistics.getQueryExecutionCount(),
          "Replacement must not select generated pack IDs before deletion");
      assertTrue(
          statistics.getPrepareStatementCount() <= 4,
          "One lock select, one parent bulk delete and one replacement insert should be sufficient");

      assertEquals(
          0L,
          packRowCount(provider, repositoryName, List.of(first.packName(), second.packName())));
      assertEquals(
          0L,
          chunkRowCount(provider, List.of(first.chunkedPackId(), second.chunkedPackId())),
          "The database foreign key must cascade parent deletion to payload chunks");
      assertEquals(
          1L,
          packRowCount(provider, repositoryName, List.of(baseName(replacement))));
      assertEquals(0, database.stagedExtensionCount());
    }
  }

  private static PersistedPack persistChunkedPack(
      HibernateSessionFactoryProvider provider,
      String repositoryName,
      String packName,
      int seed) {
    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      byte[] data = deterministicBytes(257, seed);

      GitPackEntity pack = entity(repositoryName, packName, PackExt.PACK.getExtension(), null);
      pack.setFileSize(data.length);
      session.persist(pack);
      session.flush();

      GitPackChunkEntity chunk = new GitPackChunkEntity();
      chunk.setPackId(pack.getId());
      chunk.setChunkIndex(0);
      chunk.setChunkSize(data.length);
      chunk.setData(data);
      session.persist(chunk);

      GitPackEntity index =
          entity(
              repositoryName,
              packName,
              PackExt.INDEX.getExtension(),
              deterministicBytes(31, seed + 1));
      index.setFileSize(index.getData().length);
      session.persist(index);
      transaction.commit();
      return new PersistedPack(packName, pack.getId());
    }
  }

  private static GitPackEntity entity(
      String repositoryName, String packName, String extension, byte[] data) {
    GitPackEntity entity = new GitPackEntity();
    entity.setRepositoryName(repositoryName);
    entity.setPackName(packName);
    entity.setPackExtension(extension);
    entity.setData(data);
    entity.setCommitted(true);
    entity.setCreatedAt(Instant.now());
    entity.setCommittedAt(Instant.now());
    return entity;
  }

  private static void write(
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

  private static DfsPackDescription description(
      List<DfsPackDescription> descriptions, String packName) {
    return descriptions.stream()
        .filter(candidate -> baseName(candidate).equals(packName))
        .findFirst()
        .orElseThrow();
  }

  private static String baseName(DfsPackDescription description) {
    String fileName = description.getFileName(PackExt.PACK);
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  private static long packRowCount(
      HibernateSessionFactoryProvider provider,
      String repositoryName,
      List<String> packNames) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT COUNT(p) FROM GitPackEntity p WHERE p.repositoryName = :repo "
                  + "AND p.packName IN :packNames",
              Long.class)
          .setParameter("repo", repositoryName)
          .setParameter("packNames", packNames)
          .getSingleResult();
    }
  }

  private static long chunkRowCount(
      HibernateSessionFactoryProvider provider, List<Long> packIds) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT COUNT(c) FROM GitPackChunkEntity c WHERE c.packId IN :packIds", Long.class)
          .setParameter("packIds", packIds)
          .getSingleResult();
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

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:cascade-pack-delete-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.generate_statistics", "true");
    properties.put("hibernate.connection.pool_size", "2");
    return new HibernateSessionFactoryProvider(properties);
  }

  private record PersistedPack(String packName, Long chunkedPackId) {}
}
