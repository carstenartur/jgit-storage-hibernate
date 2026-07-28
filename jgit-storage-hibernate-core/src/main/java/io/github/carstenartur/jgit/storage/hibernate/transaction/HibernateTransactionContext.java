/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.transaction;

import io.github.carstenartur.jgit.storage.hibernate.entity.GitRepositoryLockEntity;
import jakarta.persistence.LockModeType;
import java.io.IOException;
import java.util.Objects;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

/**
 * Coordinates one Hibernate transaction across pack/reftable persistence and queryable reflogs.
 *
 * <p>The context is deliberately scoped to one repository instance. Nested storage operations on
 * the same thread join the active session; unrelated repository operations keep independent
 * transactions. Repository-specific counters complement Hibernate's SessionFactory statistics and
 * can be sampled through the owning repository.
 */
public final class HibernateTransactionContext {

  private final SessionFactory sessionFactory;
  private final HibernateStorageMetrics storageMetrics = new HibernateStorageMetrics();
  private final ThreadLocal<Session> activeSession = new ThreadLocal<>();

  /**
   * Create a transaction context.
   *
   * @param sessionFactory application-managed Hibernate session factory
   */
  public HibernateTransactionContext(SessionFactory sessionFactory) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
  }

  /** Return cumulative repository-specific metrics owned by this context. */
  public HibernateStorageMetrics getStorageMetrics() {
    return storageMetrics;
  }

  /**
   * Execute work in the current repository transaction, starting one when necessary.
   *
   * @param work storage work
   * @param <T> result type
   * @return work result
   * @throws IOException when storage work fails
   */
  public <T> T execute(Work<T> work) throws IOException {
    Objects.requireNonNull(work, "work");
    Session existing = activeSession.get();
    if (existing != null) {
      return work.execute(existing);
    }

    try (Session session = sessionFactory.openSession()) {
      Transaction transaction = session.beginTransaction();
      storageMetrics.recordTransactionStarted();
      activeSession.set(session);
      try {
        T result = work.execute(session);
        transaction.commit();
        storageMetrics.recordTransactionCommitted();
        return result;
      } catch (IOException | RuntimeException exception) {
        if (transaction.isActive()) {
          transaction.rollback();
          storageMetrics.recordTransactionRolledBack();
        }
        throw exception;
      } finally {
        activeSession.remove();
      }
    }
  }

  /**
   * Execute storage work while holding the cross-SessionFactory lock for one logical repository.
   *
   * <p>Pack publication, lease renewal, abandoned-write cleanup and ref publication use the same
   * row lock so maintenance cannot race a writer between its ownership check and mutation.
   *
   * @param repositoryName logical repository name
   * @param work storage work
   * @param <T> result type
   * @return work result
   * @throws IOException if the lock row is missing or storage work fails
   */
  public <T> T executeWithRepositoryLock(String repositoryName, Work<T> work) throws IOException {
    Objects.requireNonNull(repositoryName, "repositoryName");
    Objects.requireNonNull(work, "work");
    return execute(
        session -> {
          long lockStarted = System.nanoTime();
          GitRepositoryLockEntity repositoryLock =
              session.find(
                  GitRepositoryLockEntity.class,
                  repositoryName,
                  LockModeType.PESSIMISTIC_WRITE);
          long lockWaitNanos = System.nanoTime() - lockStarted;
          if (repositoryLock == null) {
            throw new IOException("Missing repository lock row for " + repositoryName);
          }
          storageMetrics.recordRepositoryLock(lockWaitNanos);
          return work.execute(session);
        });
  }

  /** Record binary payload bytes materialized from database storage. */
  public void recordPayloadBytesRead(long bytes) {
    storageMetrics.recordPayloadBytesRead(bytes);
  }

  /** Record binary payload bytes inserted or updated in database storage. */
  public void recordPayloadBytesWritten(long bytes) {
    storageMetrics.recordPayloadBytesWritten(bytes);
  }

  /** Record newly inserted bounded chunk rows. */
  public void recordChunkRowsInserted(long rows) {
    storageMetrics.recordChunkRowsInserted(rows);
  }

  /** Record bounded chunk rows removed before replacement or deletion. */
  public void recordChunkRowsDeleted(long rows) {
    storageMetrics.recordChunkRowsDeleted(rows);
  }

  /** Unit of repository persistence work that may report an I/O failure to JGit. */
  @FunctionalInterface
  public interface Work<T> {
    T execute(Session session) throws IOException;
  }
}
