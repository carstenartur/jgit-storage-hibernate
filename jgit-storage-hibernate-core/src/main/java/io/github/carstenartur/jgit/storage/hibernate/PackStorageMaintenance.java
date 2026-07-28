/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.hibernate.SessionFactory;

/**
 * Operator-facing maintenance for abandoned, uncommitted pack payloads.
 *
 * <p>Cleanup is repository-scoped and uses the same pessimistic database row lock as pack and ref
 * publication. A pack name is eligible only when every persisted extension is old, uncommitted and
 * has no current writer lease. Published packs and partially active multi-extension packs are never
 * candidates.
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
                session
                    .createMutationQuery(
                        "DELETE FROM GitPackChunkEntity c WHERE c.packId IN :packIds")
                    .setParameter("packIds", packIds)
                    .executeUpdate();
            int packRows =
                session
                    .createMutationQuery("DELETE FROM GitPackEntity p WHERE p.id IN :packIds")
                    .setParameter("packIds", packIds)
                    .executeUpdate();
            return new PackCleanupResult(packRows, chunkRows, payloadBytes);
          });
    } catch (IOException exception) {
      throw new HibernateStorageException(
          "Could not clean expired pack payloads for " + repositoryName, exception);
    }
  }
}
