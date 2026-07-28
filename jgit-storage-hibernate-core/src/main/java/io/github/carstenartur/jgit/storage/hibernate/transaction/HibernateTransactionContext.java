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
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.LongAdder;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

/**
 * Coordinates one Hibernate transaction across pack/reftable persistence and queryable reflogs.
 *
 * <p>The context is deliberately scoped to one repository instance. Nested storage operations on
 * the same thread join the active session; unrelated repository operations keep independent
 * transactions. Repository row locks are reentrant inside one active transaction, so pack,
 * reftable, reflog and ref publication do not repeatedly issue the same pessimistic lock statement.
 */
public final class HibernateTransactionContext {

  /** Enable lightweight repository transaction and lock acquisition counters. */
  public static final String METRICS_ENABLED_PROPERTY =
      "jgit.storage.hibernate.metrics.enabled";

  private final SessionFactory sessionFactory;
  private final ThreadLocal<Session> activeSession = new ThreadLocal<>();
  private final ThreadLocal<Set<String>> heldRepositoryLocks = new ThreadLocal<>();
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

    try (TransactionScope scope = beginScope()) {
      T result = work.execute(scope.session());
      scope.commit();
      return result;
    }
  }

  /**
   * Begin an explicit repository transaction on the current thread.
   *
   * <p>This advanced scope is used by protocol adapters that must keep several otherwise independent
   * JGit storage callbacks in one transaction and commit before reporting success to a client. The
   * scope owns one Hibernate session and must be completed on the same thread. Closing an uncommitted
   * scope rolls it back.
   *
   * @return new transaction scope
   * @throws IllegalStateException if this context already has an active transaction on the thread
   */
  public TransactionScope beginScope() {
    if (activeSession.get() != null) {
      throw new IllegalStateException("A repository transaction is already active on this thread");
    }

    Session session = sessionFactory.openSession();
    Transaction transaction;
    try {
      transaction = session.beginTransaction();
    } catch (RuntimeException failure) {
      session.close();
      throw failure;
    }
    if (metricsEnabled) {
      transactionsStarted.increment();
    }
    activeSession.set(session);
    heldRepositoryLocks.set(new HashSet<>());
    return new TransactionScope(session, transaction);
  }

  /**
   * Execute storage work while holding the cross-SessionFactory lock for one logical repository.
   *
   * <p>Pack publication, lease renewal, abandoned-write cleanup and ref publication use the same
   * row lock so maintenance cannot race a writer between its ownership check and mutation.
   * Repeated calls for the same repository inside one transaction reuse the already-held lock.
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
    Set<String> heldLocks = heldRepositoryLocks.get();
    if (heldLocks != null && heldLocks.contains(repositoryName)) {
      return;
    }

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
    if (heldLocks != null) {
      heldLocks.add(repositoryName);
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

  /** Explicit same-thread transaction scope used by protocol adapters. */
  public final class TransactionScope implements AutoCloseable {
    private final Session session;
    private final Transaction transaction;
    private boolean completed;

    private TransactionScope(Session session, Transaction transaction) {
      this.session = session;
      this.transaction = transaction;
    }

    private Session session() {
      return session;
    }

    /** Commit the transaction and release its session. */
    public void commit() {
      ensureOpen();
      try {
        transaction.commit();
        completed = true;
        if (metricsEnabled) {
          transactionsCommitted.increment();
        }
      } catch (RuntimeException failure) {
        rollbackActiveTransaction();
        throw failure;
      } finally {
        cleanup();
      }
    }

    /** Roll back the transaction if still active and release its session. */
    public void rollback() {
      if (completed) {
        return;
      }
      try {
        rollbackActiveTransaction();
      } finally {
        completed = true;
        cleanup();
      }
    }

    @Override
    public void close() {
      rollback();
    }

    private void rollbackActiveTransaction() {
      if (transaction.isActive()) {
        transaction.rollback();
        if (metricsEnabled) {
          transactionsRolledBack.increment();
        }
      }
    }

    private void cleanup() {
      activeSession.remove();
      heldRepositoryLocks.remove();
      session.close();
    }

    private void ensureOpen() {
      if (completed) {
        throw new IllegalStateException("Repository transaction scope is already completed");
      }
    }
  }

  /** Unit of repository persistence work that may report an I/O failure to JGit. */
  @FunctionalInterface
  public interface Work<T> {
    T execute(Session session) throws IOException;
  }
}
