/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.DefaultHibernateRepositoryFactory;
import io.github.carstenartur.jgit.storage.hibernate.PackCleanupResult;
import io.github.carstenartur.jgit.storage.hibernate.PackStorageMaintenance;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackChunkEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource;
import org.eclipse.jgit.internal.storage.dfs.DfsOutputStream;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.hibernate.Session;

/** Package access to the real staging paths, called by the Flyway-backed Oracle suite. */
public final class OraclePackStorageContract {
  private OraclePackStorageContract() {}

  public static void verifyDuplicateConstraint(HibernateSessionFactoryProvider provider)
      throws Exception {
    String name = "oracle-duplicate-publication";
    try (HibernateRepository repository =
        HibernateRepository.create(provider.getSessionFactory(), name)) {
      repository.create(true);
      ReadAheadHibernateObjDatabase database =
          (ReadAheadHibernateObjDatabase) repository.getObjectDatabase();
      DfsPackDescription description = database.newPack(PackSource.RECEIVE);
      write(database, description, PackExt.PACK, bytes(80));
      write(database, description, PackExt.INDEX, bytes(40));
      long baseline = count(provider, name);
      persist(provider, name, baseName(description), "idx", Instant.now(), null, true, bytes(7));
      IOException failure =
          assertThrows(
              IOException.class, () -> database.commitPackImpl(List.of(description), null));
      assertTrue(
          failure.getMessage().contains("Pack extension already exists:"), failure::getMessage);
      assertTrue(failure.getMessage().contains(".idx"));
      assertNotNull(failure.getCause());
      assertEquals(baseline + 1, count(provider, name));
      assertEquals(0, database.stagedExtensionCount());
    }
  }

  public static void verifyLeaseCleanupAndDeletion(HibernateSessionFactoryProvider provider)
      throws Exception {
    String name = "oracle-lease-cleanup";
    Instant now = Instant.now();
    Instant old = now.minusSeconds(172_800);
    Instant cutoff = now.minusSeconds(86_400);
    try (HibernateRepository ignored =
        HibernateRepository.create(provider.getSessionFactory(), name)) {
      HibernateObjDatabase.HibernatePackOutputStream active =
          new HibernateObjDatabase.HibernatePackOutputStream(
              new HibernateTransactionContext(provider.getSessionFactory()),
              name,
              "active-pack",
              "pack");
      byte[] first = bytes(1_200_000);
      byte[] second = bytes(700_000);
      try {
        active.write(first, 0, first.length);
        active.flush();
        age(provider, name, "active-pack", old, now.plusSeconds(3600));
        persist(provider, name, "active-pack", "idx", old, now.minusSeconds(1), false, bytes(5));
        persist(provider, name, "expired-pack", "pack", old, now.minusSeconds(1), false, bytes(7));
        persist(provider, name, "legacy-orphan", "pack", old, null, false, bytes(9));
        persist(provider, name, "published-pack", "pack", old, null, true, bytes(13));
        PackStorageMaintenance maintenance =
            new PackStorageMaintenance(provider.getSessionFactory());
        assertEquals(
            new PackCleanupResult(2, 2, 16),
            maintenance.deleteExpiredUncommittedPacks(new RepositoryName(name), cutoff, now));
        active.write(second, 0, second.length);
        active.close();
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(first);
        expected.write(second);
        try (Session session = provider.getSessionFactory().openSession()) {
          List<byte[]> chunks =
              session
                  .createQuery(
                      "select c.data from GitPackChunkEntity c where c.packId = (select p.id from"
                          + " GitPackEntity p where p.repositoryName = :repo and p.packName ="
                          + " 'active-pack' and p.packExtension = 'pack') order by c.chunkIndex",
                      byte[].class)
                  .setParameter("repo", name)
                  .getResultList();
          ByteArrayOutputStream actual = new ByteArrayOutputStream();
          for (byte[] chunk : chunks) actual.write(chunk);
          assertArrayEquals(expected.toByteArray(), actual.toByteArray());
        }
        age(provider, name, "active-pack", old, now.minusSeconds(1));
        PackCleanupResult removed =
            maintenance.deleteExpiredUncommittedPacks(new RepositoryName(name), cutoff, now);
        assertEquals(2, removed.packRows());
        assertTrue(removed.chunkRows() > 1);
        assertEquals(first.length + second.length + 5L, removed.payloadBytes());
        assertEquals(1, count(provider, name));
      } finally {
        // Close is idempotent after success and releases local resources after assertion failures.
        try {
          active.close();
        } catch (IOException ignoredClose) {
        }
      }
    }

    String deleted = "oracle-delete-staged-writer";
    try (HibernateRepository ignored =
        HibernateRepository.create(provider.getSessionFactory(), deleted)) {
      HibernateObjDatabase.HibernatePackOutputStream staged =
          new HibernateObjDatabase.HibernatePackOutputStream(
              new HibernateTransactionContext(provider.getSessionFactory()),
              deleted,
              "staged-pack",
              "pack");
      byte[] payload = bytes(1_200_000);
      staged.write(payload, 0, payload.length);
      staged.flush();
      try (var executor = Executors.newSingleThreadExecutor()) {
        assertTrue(
            executor
                .submit(
                    () ->
                        new DefaultHibernateRepositoryFactory(provider.getSessionFactory())
                            .deleteRepository(new RepositoryName(deleted))
                            .deletedAnything())
                .get(30, TimeUnit.SECONDS));
      }
      assertThrows(IOException.class, staged::close);
      assertEquals(0, count(provider, deleted));
      try (Session session = provider.getSessionFactory().openSession()) {
        assertEquals(
            0L,
            session
                .createQuery(
                    "select count(c) from GitPackChunkEntity c left join c.pack p where p.id is"
                        + " null",
                    Long.class)
                .getSingleResult());
      }
    }
  }

  private static void write(
      ReadAheadHibernateObjDatabase database,
      DfsPackDescription description,
      PackExt extension,
      byte[] bytes)
      throws Exception {
    try (DfsOutputStream output = database.writeFile(description, extension)) {
      output.write(bytes, 0, bytes.length);
    }
    description.addFileExt(extension);
    description.setFileSize(extension, bytes.length);
  }

  private static String baseName(DfsPackDescription description) {
    String name = description.getFileName(PackExt.PACK);
    return name.substring(0, name.lastIndexOf('.'));
  }

  private static byte[] bytes(int length) {
    byte[] bytes = new byte[length];
    new Random(1289).nextBytes(bytes);
    return bytes;
  }

  private static long count(HibernateSessionFactoryProvider provider, String name) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "select count(p) from GitPackEntity p where p.repositoryName = :repo", Long.class)
          .setParameter("repo", name)
          .getSingleResult();
    }
  }

  private static void persist(
      HibernateSessionFactoryProvider provider,
      String repository,
      String name,
      String extension,
      Instant created,
      Instant lease,
      boolean committed,
      byte[] bytes) {
    try (Session session = provider.getSessionFactory().openSession()) {
      var transaction = session.beginTransaction();
      GitPackEntity pack = new GitPackEntity();
      pack.setRepositoryName(repository);
      pack.setPackName(name);
      pack.setPackExtension(extension);
      pack.setFileSize(bytes.length);
      pack.setCommitted(committed);
      pack.setCreatedAt(created);
      pack.setCommittedAt(committed ? created : null);
      pack.setWriteToken(committed ? null : "writer");
      pack.setWriteLeaseUntil(lease);
      session.persist(pack);
      session.flush();
      GitPackChunkEntity chunk = new GitPackChunkEntity();
      chunk.setPackId(pack.getId());
      chunk.setChunkIndex(0);
      chunk.setChunkSize(bytes.length);
      chunk.setData(bytes);
      session.persist(chunk);
      transaction.commit();
    }
  }

  private static void age(
      HibernateSessionFactoryProvider provider,
      String repository,
      String name,
      Instant created,
      Instant lease) {
    try (Session session = provider.getSessionFactory().openSession()) {
      var transaction = session.beginTransaction();
      session
          .createMutationQuery(
              "update GitPackEntity p set p.createdAt = :created, p.writeLeaseUntil = :lease "
                  + "where p.repositoryName = :repo and p.packName = :name")
          .setParameter("created", created)
          .setParameter("lease", lease)
          .setParameter("repo", repository)
          .setParameter("name", name)
          .executeUpdate();
      transaction.commit();
    }
  }
}
