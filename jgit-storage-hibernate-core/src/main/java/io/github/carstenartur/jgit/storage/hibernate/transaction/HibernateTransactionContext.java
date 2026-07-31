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

  private static final StackWalker STACK_WALKER = StackWalker.getInstance();

  private final SessionFactory sessionFactory;
  private final ThreadLocal<Session> activeSession = new ThreadLocal<>();
  private final ThreadLocal<StorageOperationKind> activeOperation = new ThreadLocal<>();
  private final ThreadLocal<Long> firstRepositoryLockAcquiredAtNanos = new ThreadLocal<>();
  private final boolean metricsEnabled;
  private final LongAdder transactionsStarted = new LongAdder();
  private final LongAdder transactionsCommitted = new LongAdder();
  private final LongAdder transactionsRolledBack = new LongAdder();
  private final LongAdder repositoryLocksAcquired = new LongAdder();
  private final LongAdder repositoryLockAcquisitionNanos = new LongAdder();
  private final LongAdder transactionDurationNanos = new LongAdder();
  private final LongAdder repositoryLockHeldNanos = new LongAdder();
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

  /**
   * Execute uncategorized work in the current repository transaction, starting one when necessary.
   *
   * @param work storage work
   * @param <T> result type
   * @return work result
   * @throws IOException when storage work fails
   */
  public <T> T execute(Work<T> work) throws IOException {
    return execute(StorageOperationKind.OTHER, work);
  }

  /**
   * Execute categorized work in the current repository transaction, starting one when necessary.
   *
   * <p>Nested work joins the active session and inherits the category of the owning top-level
   * transaction.
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

    StorageOperationKind effectiveOperation = effectiveOperation(operation);
    CategoryCounters category = counters(effectiveOperation);
    if (metricsEnabled) {
      transactionsStarted.increment();
      category.transactionsStarted.increment();
    }
    try (Session session = sessionFactory.openSession()) {
      Transaction transaction = session.beginTransaction();
      long transactionStartedAt = metricsEnabled ? System.nanoTime() : 0L;
      activeSession.set(session);
      activeOperation.set(effectiveOperation);
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
        if (metricsEnabled) {
          recordDurations(category, transactionStartedAt);
        }
        firstRepositoryLockAcquiredAtNanos.remove();
        activeOperation.remove();
        activeSession.remove();
      }
    }
  }

  private void recordDurations(CategoryCounters category, long transactionStartedAt) {
    long completedAt = System.nanoTime();
    long transactionDuration = completedAt - transactionStartedAt;
    transactionDurationNanos.add(transactionDuration);
    category.transactionDurationNanos.add(transactionDuration);

    Long lockAcquiredAt = firstRepositoryLockAcquiredAtNanos.get();
    if (lockAcquiredAt != null) {
      long lockHeldDuration = completedAt - lockAcquiredAt;
      repositoryLockHeldNanos.add(lockHeldDuration);
      category.repositoryLockHeldNanos.add(lockHeldDuration);
    }
  }

  /**
   * Execute uncategorized storage work while holding the cross-SessionFactory lock for one logical
   * repository.
   *
   * @param repositoryName logical repository name
   * @param work storage work
   * @param <T> result type
   * @return work result
   * @throws IOException if the lock row is missing or storage work fails
   */
  public <T> T executeWithRepositoryLock(String repositoryName, Work<T> work) throws IOException {
    return executeWithRepositoryLock(StorageOperationKind.OTHER, repositoryName, work);
  }

  /**
   * Execute categorized storage work while holding the cross-SessionFactory lock for one logical
   * repository.
   *
   * <p>Pack publication, lease renewal, abandoned-write cleanup and ref publication use the same
   * row lock so maintenance cannot race a writer between its ownership check and mutation.
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
   * <p>The measured acquisition duration includes both database round-trip latency and any
   * contention wait. Lock hold duration is measured separately from the first successful acquisition
   * in a context-managed top-level transaction until that transaction completes.
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
    long acquiredAt = metricsEnabled ? System.nanoTime() : 0L;
    if (metricsEnabled) {
      long elapsed = acquiredAt - started;
      repositoryLockAcquisitionNanos.add(elapsed);
      counters(activeOperation()).repositoryLockAcquisitionNanos.add(elapsed);
    }
    if (repositoryLock == null) {
      throw new IOException("Missing repository lock row for " + repositoryName);
    }
    if (metricsEnabled) {
      repositoryLocksAcquired.increment();
      counters(activeOperation()).repositoryLocksAcquired.increment();
      if (activeSession.get() == session && firstRepositoryLockAcquiredAtNanos.get() == null) {
        firstRepositoryLockAcquiredAtNanos.set(acquiredAt);
      }
    }
  }

  /**
   * Return a monotone aggregate metrics snapshot for this repository transaction context.
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

  /**
   * Return cumulative top-level transaction and repository-lock hold durations.
   *
   * @return current duration snapshot, or {@link StorageDurationMetrics#ZERO} when metrics are
   *     disabled
   */
  public StorageDurationMetrics durationMetricsSnapshot() {
    if (!metricsEnabled) {
      return StorageDurationMetrics.ZERO;
    }
    return new StorageDurationMetrics(
        transactionDurationNanos.sum(), repositoryLockHeldNanos.sum());
  }

  /**
   * Return the same monotone counters grouped by stable operation category.
   *
   * @return immutable breakdown, or {@link StorageOperationBreakdown#ZERO} when metrics are disabled
   */
  public StorageOperationBreakdown operationBreakdownSnapshot() {
    if (!metricsEnabled) {
      return StorageOperationBreakdown.ZERO;
    }
    Map<StorageOperationKind, StorageOperationMetrics> snapshot =
        new EnumMap<>(StorageOperationKind.class);
    for (StorageOperationKind kind : StorageOperationKind.values()) {
      StorageOperationMetrics value = categoryCounters[kind.ordinal()].operationSnapshot();
      if (!StorageOperationMetrics.ZERO.equals(value)) {
        snapshot.put(kind, value);
      }
    }
    return new StorageOperationBreakdown(snapshot);
  }

  /**
   * Return cumulative transaction and repository-lock hold durations grouped by operation category.
   *
   * @return immutable duration breakdown, or {@link StorageDurationBreakdown#ZERO} when metrics are
   *     disabled
   */
  public StorageDurationBreakdown durationBreakdownSnapshot() {
    if (!metricsEnabled) {
      return StorageDurationBreakdown.ZERO;
    }
    Map<StorageOperationKind, StorageDurationMetrics> snapshot =
        new EnumMap<>(StorageOperationKind.class);
    for (StorageOperationKind kind : StorageOperationKind.values()) {
      StorageDurationMetrics value = categoryCounters[kind.ordinal()].durationSnapshot();
      if (!StorageDurationMetrics.ZERO.equals(value)) {
        snapshot.put(kind, value);
      }
    }
    return new StorageDurationBreakdown(snapshot);
  }

  private StorageOperationKind effectiveOperation(StorageOperationKind requested) {
    if (requested != StorageOperationKind.OTHER) {
      return requested;
    }
    return metricsEnabled ? inferInternalOperation() : StorageOperationKind.OTHER;
  }

  /**
   * Classify legacy internal call sites only in opt-in diagnostic mode. Explicit operation categories
   * always take precedence and applications with metrics disabled never inspect the call stack.
   */
  private static StorageOperationKind inferInternalOperation() {
    return STACK_WALKER.walk(
        frames ->
            frames
                .map(HibernateTransactionContext::classify)
                .filter(kind -> kind != null)
                .findFirst()
                .orElse(StorageOperationKind.OTHER));
  }

  private static StorageOperationKind classify(StackWalker.StackFrame frame) {
    String className = frame.getClassName();
    String method = frame.getMethodName();
    if (className.endsWith("HibernateObjDatabase$HibernatePackOutputStream")) {
      return StorageOperationKind.PACK_EXTENSION_WRITE;
    }
    if (className.endsWith("HibernateObjDatabase")
        || className.endsWith("ReadAheadHibernateObjDatabase")) {
      if (method.contains("listPacks")) {
        return StorageOperationKind.PACK_METADATA_READ;
      }
      if (method.contains("openFile")) {
        return StorageOperationKind.PACK_FILE_READ;
      }
      if (method.contains("commitPackImpl")) {
        return StorageOperationKind.PACK_PUBLICATION;
      }
      if (method.contains("rollbackPack")) {
        return StorageOperationKind.PACK_ROLLBACK;
      }
    }
    if (className.endsWith("PackStorageMaintenance")) {
      return StorageOperationKind.PACK_MAINTENANCE;
    }
    if (className.endsWith("HibernateReflogReader")) {
      return StorageOperationKind.REFLOG_READ;
    }
    if (className.endsWith("HibernateReflogWriter")) {
      return StorageOperationKind.REFLOG_WRITE;
    }
    return null;
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
    private final LongAdder transactionDurationNanos = new LongAdder();
    private final LongAdder repositoryLockHeldNanos = new LongAdder();

    private StorageOperationMetrics operationSnapshot() {
      return new StorageOperationMetrics(
          transactionsStarted.sum(),
          transactionsCommitted.sum(),
          transactionsRolledBack.sum(),
          repositoryLocksAcquired.sum(),
          repositoryLockAcquisitionNanos.sum());
    }

    private StorageDurationMetrics durationSnapshot() {
      return new StorageDurationMetrics(
          transactionDurationNanos.sum(), repositoryLockHeldNanos.sum());
    }
  }

  /** Unit of repository persistence work that may report an I/O failure to JGit. */
  @FunctionalInterface
  public interface Work<T> {
    T execute(Session session) throws IOException;
  }
}
