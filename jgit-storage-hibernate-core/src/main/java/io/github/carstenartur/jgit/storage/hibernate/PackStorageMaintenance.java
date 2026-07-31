/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import io.github.carstenartur.jgit.storage.hibernate.refs.HibernateRefDatabase;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.internal.storage.dfs.DfsGarbageCollector;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.dfs.DfsPackFile;
import org.eclipse.jgit.internal.storage.dfs.DfsReftable;
import org.eclipse.jgit.internal.storage.pack.PackExt;
import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.ProgressMonitor;
import org.eclipse.jgit.storage.pack.PackConfig;
import org.hibernate.SessionFactory;

/**
 * Operator-facing maintenance for Hibernate-backed JGit pack storage.
 *
 * <p>Abandoned-write cleanup is repository-scoped and uses the same pessimistic database row lock as
 * pack and ref publication. A pack name is eligible only when every persisted extension is old,
 * uncommitted and has no current writer lease. Published packs and partially active multi-extension
 * packs are never candidates.
 *
 * <p>Repack maintenance delegates graph traversal, delta selection, bitmap generation, commit-graph
 * generation and race detection to JGit's DFS garbage collector. The expensive new extensions are
 * built through the normal local staging path; the final logical pack and optional compacted
 * Reftable are published through the existing atomic Hibernate transaction.
 */
public final class PackStorageMaintenance {

  private final SessionFactory sessionFactory;

  public PackStorageMaintenance(SessionFactory sessionFactory) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
  }

  /**
   * Delete expired, uncommitted pack groups for one logical repository.
   *
   * @param repositoryName logical repository
   * @param createdBefore delete only pack groups whose persisted extensions were created strictly
   *     before this instant
   * @param now instant used to decide whether a writer lease has expired
   * @return deleted pack/chunk counts and the declared payload bytes reclaimed
   */
  public PackCleanupResult deleteExpiredUncommittedPacks(
      RepositoryName repositoryName, Instant createdBefore, Instant now) {
    Objects.requireNonNull(repositoryName, "repositoryName");
    Objects.requireNonNull(createdBefore, "createdBefore");
    Objects.requireNonNull(now, "now");
    if (createdBefore.isAfter(now)) {
      throw new IllegalArgumentException("createdBefore must not be after now");
    }

    try (HibernateRepository ignored =
        HibernateRepository.create(sessionFactory, repositoryName.value())) {
      HibernateTransactionContext context = new HibernateTransactionContext(sessionFactory);
      return context.executeWithRepositoryLock(
          repositoryName.value(),
          session -> {
            List<String> packNames =
                session
                    .createQuery(
                        "SELECT DISTINCT p.packName FROM GitPackEntity p "
                            + "WHERE p.repositoryName = :repo AND p.committed = false "
                            + "AND p.createdAt < :createdBefore "
                            + "AND (p.writeLeaseUntil IS NULL OR p.writeLeaseUntil < :now) "
                            + "AND NOT EXISTS (SELECT active.id FROM GitPackEntity active "
                            + "WHERE active.repositoryName = p.repositoryName "
                            + "AND active.packName = p.packName "
                            + "AND (active.committed = true "
                            + "OR active.createdAt >= :createdBefore "
                            + "OR (active.writeLeaseUntil IS NOT NULL "
                            + "AND active.writeLeaseUntil >= :now)))",
                        String.class)
                    .setParameter("repo", repositoryName.value())
                    .setParameter("createdBefore", createdBefore)
                    .setParameter("now", now)
                    .getResultList();
            if (packNames.isEmpty()) {
              return new PackCleanupResult(0, 0, 0);
            }

            List<Object[]> rows =
                session
                    .createQuery(
                        "SELECT p.id, p.fileSize FROM GitPackEntity p "
                            + "WHERE p.repositoryName = :repo AND p.committed = false "
                            + "AND p.packName IN :packNames",
                        Object[].class)
                    .setParameter("repo", repositoryName.value())
                    .setParameter("packNames", packNames)
                    .getResultList();
            List<Long> packIds = rows.stream().map(row -> (Long) row[0]).toList();
            long payloadBytes =
                rows.stream().mapToLong(row -> ((Number) row[1]).longValue()).sum();
            int chunkRows =
                Math.toIntExact(
                    session
                        .createQuery(
                            "SELECT COUNT(c) FROM GitPackChunkEntity c WHERE c.packId IN :packIds",
                            Long.class)
                        .setParameter("packIds", packIds)
                        .getSingleResult());
            int packRows =
                session
                    .createMutationQuery(
                        "DELETE FROM GitPackEntity p WHERE p.repositoryName = :repo "
                            + "AND p.packName IN :packNames")
                    .setParameter("repo", repositoryName.value())
                    .setParameter("packNames", packNames)
                    .executeUpdate();
            return new PackCleanupResult(packRows, chunkRows, payloadBytes);
          });
    } catch (IOException exception) {
      throw new HibernateStorageException(
          "Could not clean expired pack payloads for " + repositoryName, exception);
    }
  }

  /**
   * Repack one repository with the read-optimized maintenance defaults.
   *
   * @param repositoryName logical repository
   * @return structural maintenance outcome
   */
  public PackRepackResult repackForReads(RepositoryName repositoryName) {
    return repack(
        repositoryName, PackRepackOptions.optimizedForReads(), NullProgressMonitor.INSTANCE);
  }

  /**
   * Repack one repository through JGit's DFS garbage collector.
   *
   * @param repositoryName logical repository
   * @param options pack, graph, bitmap and garbage-retention options
   * @return structural maintenance outcome
   */
  public PackRepackResult repack(
      RepositoryName repositoryName, PackRepackOptions options) {
    return repack(repositoryName, options, NullProgressMonitor.INSTANCE);
  }

  /**
   * Repack one repository through JGit's DFS garbage collector.
   *
   * <p>The collector validates that refs did not move incompatibly before replacing source packs. A
   * {@code successful=false} result means JGit detected such a race and the operation should be
   * retried later; it is not reported as storage corruption.
   *
   * @param repositoryName logical repository
   * @param options pack, graph, bitmap and garbage-retention options
   * @param progressMonitor receives JGit maintenance progress
   * @return structural maintenance outcome
   */
  public PackRepackResult repack(
      RepositoryName repositoryName,
      PackRepackOptions options,
      ProgressMonitor progressMonitor) {
    Objects.requireNonNull(repositoryName, "repositoryName");
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(progressMonitor, "progressMonitor");

    try (HibernateRepository repository =
        HibernateRepository.create(sessionFactory, repositoryName.value())) {
      DfsObjDatabase objectDatabase = repository.getObjectDatabase();
      PackInventory before = inventory(objectDatabase);

      DfsGarbageCollector collector = new DfsGarbageCollector(repository);
      PackConfig packConfig = new PackConfig(repository);
      packConfig.setSinglePack(options.singlePack());
      packConfig.setBuildBitmaps(options.buildBitmaps());
      collector.setPackConfig(packConfig);
      collector.setWriteCommitGraph(options.writeCommitGraph());
      collector.setWriteBloomFilter(options.writeBloomFilter());
      collector.setGarbageTtl(
          options.garbageTtl().toMillis(), TimeUnit.MILLISECONDS);
      collector.setCoalesceGarbageLimit(options.coalesceGarbageLimitBytes());

      if (options.compactReftables()) {
        if (!(repository.getRefDatabase() instanceof HibernateRefDatabase refDatabase)) {
          throw new HibernateStorageException(
              "Repository does not use the Hibernate Reftable database: " + repositoryName);
        }
        collector.setReftableConfig(refDatabase.getReftableConfig());
        collector.setConvertToReftable(true);
      } else {
        collector.setConvertToReftable(false);
      }

      long started = System.nanoTime();
      boolean successful = collector.pack(progressMonitor);
      long elapsedNanos = System.nanoTime() - started;
      PackInventory after = inventory(objectDatabase);

      return new PackRepackResult(
          successful,
          before.packs(),
          after.packs(),
          before.reftables(),
          after.reftables(),
          before.bytes(),
          after.bytes(),
          collector.getSourcePacks().size(),
          collector.getNewPacks().size(),
          elapsedNanos);
    } catch (IOException exception) {
      throw new HibernateStorageException(
          "Could not repack Hibernate-backed repository " + repositoryName, exception);
    }
  }

  private static PackInventory inventory(DfsObjDatabase objectDatabase) throws IOException {
    DfsPackFile[] packs = objectDatabase.getPacks();
    DfsReftable[] reftables = objectDatabase.getReftables();
    Set<DfsPackDescription> descriptions = new LinkedHashSet<>();
    for (DfsPackFile pack : packs) {
      descriptions.add(pack.getPackDescription());
    }
    for (DfsReftable reftable : reftables) {
      descriptions.add(reftable.getPackDescription());
    }
    long bytes = descriptions.stream().mapToLong(PackStorageMaintenance::totalFileSize).sum();
    return new PackInventory(packs.length, reftables.length, bytes);
  }

  private static long totalFileSize(DfsPackDescription description) {
    long bytes = 0;
    for (PackExt extension : PackExt.values()) {
      if (description.hasFileExt(extension)) {
        bytes += description.getFileSize(extension);
      }
    }
    return bytes;
  }

  private record PackInventory(int packs, int reftables, long bytes) {}
}
