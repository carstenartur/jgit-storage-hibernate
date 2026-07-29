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
import java.util.EnumMap;
import java.util.Map;
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
 * transactions. When metrics are enabled, nested work inherits the operation category of the owning
 * top-level transaction so every transaction and lock is attributed exactly once.
 */
public final class HibernateTransactionContext {

  /** Enable lightweight repository transaction and lock acquisition counters. */
  public static final String METRICS_ENABLED_PROPERTY = "jgit.storage.hibernate.metrics.enabled";

  private final SessionFactory sessionFactory;
  private final ThreadLocal<Session> activeSession = new ThreadLocal<>();
  private final ThreadLocal<StorageOperationKind> activeOperation = new ThreadLocal<>();
  private final boolean metricsEnabled;
  private final LongAdder transactionsStarted = new LongAdder();
  private final LongAdder transactionsCommitted = new LongAdder();
  private final LongAdder transactionsRolledBack = new LongAdder();
  private final LongAdder repositoryLocksAcquired = new LongAdder();
  private final LongAdder repositoryLockAcquisitionNanos = new LongAdder();
  private final CategoryCounters[] categoryCounters;

  /**
   * Create a transaction context.
   *
   * @param sessionFactory application-managed Hibernate session factory
   */
  public HibernateTransactionContext(SessionFactory sessionFactory) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    Object configured = sessionFactory.getProperties().get(METRICS_ENABLED_PROPERTY);
    metricsEnabled = configured != null && Boolean.parseBoolean(configured.toString());
    if (metricsEnabled) {
      StorageOperationKind[] kinds = StorageOperationKind.values();
      categoryCounters = new CategoryCounters[kinds.length];
      for (int index = 0; index < kinds.length; index++) {
        categoryCounters[index] = new CategoryCounters();
      }
    } else {
      categoryCounters = new CategoryCounters[0];
    }
  }

  /** Execute uncategorized work in the current repository transaction. */
  public <T> T execute(Work<T> work) throws IOException {
    return execute(StorageOperationKind.OTHER, work);
  }

  /**
   * Execute categorized work in the current repository transaction, starting one when necessary.
   *
   * @param operation stable diagnostic category for the owning top-level transaction
   * @param work storage work
   * @param <T> result type
   * @return work result
   * @throws IOException when storage work fails
   */
  public <T> T execute(StorageOperationKind operation, Work<T> work) throws IOException {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(work, "work");
    Session existing = activeSession.get();
    if (existing != null) {
      return work.execute(existing);
    }

    CategoryCounters category = counters(operation);
    if (metricsEnabled) {
      transactionsStarted.increment();
      category.transactionsStarted.increment();
    }
    try (Session session = sessionFactory.openSession()) {
      Transaction transaction = session.beginTransaction();
      activeSession.set(session);
      activeOperation.set(operation);
      try {
        T result = work.execute(session);
        transaction.commit();
        if (metricsEnabled) {
          transactionsCommitted.increment();
          category.transactionsCommitted.increment();
        }
        return result;
      } catch (IOException | RuntimeException exception) {
        if (transaction.isActive()) {
          transaction.rollback();
          if (metricsEnabled) {
            transactionsRolledBack.increment();
            category.transactionsRolledBack.increment();
          }
        }
        throw exception;
      } finally {
        activeOperation.remove();
        activeSession.remove();
      }
    }
  }

  /** Execute uncategorized work while holding the logical repository lock. */
  public <T> T executeWithRepositoryLock(String repositoryName, Work<T> work) throws IOException {
    return executeWithRepositoryLock(StorageOperationKind.OTHER, repositoryName, work);
  }

  /**
   * Execute categorized storage work while holding the cross-SessionFactory lock for one repository.
   *
   * @param operation stable diagnostic category for the owning top-level transaction
   * @param repositoryName logical repository name
   * @param work storage work
   * @param <T> result type
   * @return work result
   * @throws IOException if the lock row is missing or storage work fails
   */
  public <T> T executeWithRepositoryLock(
      StorageOperationKind operation, String repositoryName, Work<T> work) throws IOException {
    Objects.requireNonNull(repositoryName, "repositoryName");
    Objects.requireNonNull(work, "work");
    return execute(
        operation,
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
    long elapsed = metricsEnabled ? System.nanoTime() - started : 0L;
    if (metricsEnabled) {
      repositoryLockAcquisitionNanos.add(elapsed);
      counters(activeOperation()).repositoryLockAcquisitionNanos.add(elapsed);
    }
    if (repositoryLock == null) {
      throw new IOException("Missing repository lock row for " + repositoryName);
    }
    if (metricsEnabled) {
      repositoryLocksAcquired.increment();
      counters(activeOperation()).repositoryLocksAcquired.increment();
    }
  }

  /** Return aggregate monotone metrics for this repository transaction context. */
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

  /** Return an immutable monotone per-operation breakdown, or an empty snapshot when disabled. */
  public StorageOperationBreakdown operationBreakdownSnapshot() {
    if (!metricsEnabled) {
      return StorageOperationBreakdown.ZERO;
    }
    Map<StorageOperationKind, StorageOperationMetrics> snapshot =
        new EnumMap<>(StorageOperationKind.class);
    for (StorageOperationKind kind : StorageOperationKind.values()) {
      StorageOperationMetrics value = categoryCounters[kind.ordinal()].snapshot();
      if (!StorageOperationMetrics.ZERO.equals(value)) {
        snapshot.put(kind, value);
      }
    }
    return new StorageOperationBreakdown(snapshot);
  }

  private StorageOperationKind activeOperation() {
    StorageOperationKind operation = activeOperation.get();
    return operation == null ? StorageOperationKind.OTHER : operation;
  }

  private CategoryCounters counters(StorageOperationKind operation) {
    return metricsEnabled ? categoryCounters[operation.ordinal()] : CategoryCounters.DISABLED;
  }

  private static final class CategoryCounters {
    private static final CategoryCounters DISABLED = new CategoryCounters();

    private final LongAdder transactionsStarted = new LongAdder();
    private final LongAdder transactionsCommitted = new LongAdder();
    private final LongAdder transactionsRolledBack = new LongAdder();
    private final LongAdder repositoryLocksAcquired = new LongAdder();
    private final LongAdder repositoryLockAcquisitionNanos = new LongAdder();

    private StorageOperationMetrics snapshot() {
      return new StorageOperationMetrics(
          transactionsStarted.sum(),
          transactionsCommitted.sum(),
          transactionsRolledBack.sum(),
          repositoryLocksAcquired.sum(),
          repositoryLockAcquisitionNanos.sum());
    }
  }

  /** Unit of repository persistence work that may report an I/O failure to JGit. */
  @FunctionalInterface
  public interface Work<T> {
    T execute(Session session) throws IOException;
  }
}
