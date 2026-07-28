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
import java.util.concurrent.atomic.LongAdder;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

/**
 * Coordinates one Hibernate transaction across pack/reftable persistence and queryable reflogs.
 *
 * <p>The context is deliberately scoped to one repository instance. Nested storage operations on
 * the same thread join the active session; unrelated repository operations keep independent
 * transactions.
 */
public final class HibernateTransactionContext {

  /** Enable lightweight repository transaction and lock acquisition counters. */
  public static final String METRICS_ENABLED_PROPERTY =
      "jgit.storage.hibernate.metrics.enabled";

  private final SessionFactory sessionFactory;
  private final ThreadLocal<Session> activeSession = new ThreadLocal<>();
  private final boolean metricsEnabled;
  private final LongAdder transactionsStarted = new LongAdder();
  private final LongAdder transactionsCommitted = new LongAdder();
  private final LongAdder transactionsRolledBack = new LongAdder();
  private final LongAdder repositoryLocksAcquired = new LongAdder();
  private final LongAdder repositoryLockAcquisitionNanos = new LongAdder();

  /**
   * Create a transaction context.
   *
   * @param sessionFactory application-managed Hibernate session factory
   */
  public HibernateTransactionContext(SessionFactory sessionFactory) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    Object configured = sessionFactory.getProperties().get(METRICS_ENABLED_PROPERTY);
    metricsEnabled = configured != null && Boolean.parseBoolean(configured.toString());
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

    if (metricsEnabled) {
      transactionsStarted.increment();
    }
    try (Session session = sessionFactory.openSession()) {
      Transaction transaction = session.beginTransaction();
      activeSession.set(session);
      try {
        T result = work.execute(session);
        transaction.commit();
        if (metricsEnabled) {
          transactionsCommitted.increment();
        }
        return result;
      } catch (IOException | RuntimeException exception) {
        if (transaction.isActive()) {
          transaction.rollback();
          if (metricsEnabled) {
            transactionsRolledBack.increment();
          }
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
          acquireRepositoryLock(session, repositoryName);
          return work.execute(session);
        });
  }

  /**
   * Acquire the pessimistic row lock for a repository inside the caller's active transaction.
   *
   * <p>The measured duration includes both database round-trip latency and any contention wait. It
   * is therefore an end-to-end acquisition metric rather than a database-specific lock-wait value.
   *
   * @param session active Hibernate session and transaction
   * @param repositoryName logical repository name
   * @throws IOException if the lock row does not exist
   */
  public void acquireRepositoryLock(Session session, String repositoryName) throws IOException {
    Objects.requireNonNull(session, "session");
    Objects.requireNonNull(repositoryName, "repositoryName");
    long started = metricsEnabled ? System.nanoTime() : 0L;
    GitRepositoryLockEntity repositoryLock =
        session.find(
            GitRepositoryLockEntity.class,
            repositoryName,
            LockModeType.PESSIMISTIC_WRITE);
    if (metricsEnabled) {
      repositoryLockAcquisitionNanos.add(System.nanoTime() - started);
    }
    if (repositoryLock == null) {
      throw new IOException("Missing repository lock row for " + repositoryName);
    }
    if (metricsEnabled) {
      repositoryLocksAcquired.increment();
    }
  }

  /**
   * Return a monotone metrics snapshot for this repository transaction context.
   *
   * @return current counters, or {@link StorageOperationMetrics#ZERO} when metrics are disabled
   */
  public StorageOperationMetrics metricsSnapshot() {
    if (!metricsEnabled) {
      return StorageOperationMetrics.ZERO;
    }
    return new StorageOperationMetrics(
        transactionsStarted.sum(),
        transactionsCommitted.sum(),
        transactionsRolledBack.sum(),
        repositoryLocksAcquired.sum(),
        repositoryLockAcquisitionNanos.sum());
  }

  /** Unit of repository persistence work that may report an I/O failure to JGit. */
  @FunctionalInterface
  public interface Work<T> {
    T execute(Session session) throws IOException;
  }
}
