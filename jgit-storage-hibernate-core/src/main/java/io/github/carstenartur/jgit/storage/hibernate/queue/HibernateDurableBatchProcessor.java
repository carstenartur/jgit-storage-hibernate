/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.queue;

import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationKind;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

/**
 * Adapts a {@link DurableStripedWriteQueue} batch to exactly one Hibernate transaction.
 *
 * <p>The adapter returns only after {@link HibernateTransactionContext} committed. Consequently the
 * queue can safely complete all submission futures after the adapter returns. With repository
 * locking enabled, one batch also participates in the same cross-SessionFactory lock protocol as
 * pack publication, refs and maintenance.
 *
 * <p>The stateful Hibernate session receives the actual command count as its session-level JDBC
 * batch size. A 50-record queue batch can therefore be emitted as one JDBC batch per compatible SQL
 * statement shape instead of being split by a smaller SessionFactory default. Hibernate may still
 * split work when commands produce different SQL, require identity-generated keys or explicitly
 * flush inside the application work.
 *
 * @param <C> queued command or record type
 * @param <R> durable result type
 */
public final class HibernateDurableBatchProcessor<C, R>
    implements DurableStripedWriteQueue.DurableBatchProcessor<C, R> {

  /** Locking policy for one repository-homogeneous batch. */
  public enum Locking {
    /** Use one transaction without acquiring the repository coordination row. */
    NONE,
    /** Acquire the repository coordination row inside the owning transaction. */
    REPOSITORY
  }

  /** Application-specific persistence performed inside the owning Hibernate transaction. */
  @FunctionalInterface
  public interface BatchWork<C, R> {
    /**
     * Persist or mutate every command and return one result per command in the same order.
     *
     * @param session active Hibernate session whose JDBC batch size equals {@code commands.size()}
     * @param repositoryName repository and batch key
     * @param commands immutable repository-homogeneous command list
     * @return one result per command in the same order
     * @throws IOException when persistence must roll back
     */
    List<R> execute(Session session, String repositoryName, List<C> commands) throws IOException;
  }

  private final HibernateTransactionContext transactionContext;
  private final StorageOperationKind operation;
  private final Locking locking;
  private final BatchWork<C, R> work;

  /** Create a processor backed by the supplied application-managed SessionFactory. */
  public HibernateDurableBatchProcessor(
      SessionFactory sessionFactory,
      StorageOperationKind operation,
      Locking locking,
      BatchWork<C, R> work) {
    this.transactionContext = new HibernateTransactionContext(sessionFactory);
    this.operation = Objects.requireNonNull(operation, "operation");
    this.locking = Objects.requireNonNull(locking, "locking");
    this.work = Objects.requireNonNull(work, "work");
  }

  /** Execute one complete queue batch in one transaction and optional repository lock. */
  @Override
  public List<R> execute(String repositoryName, List<C> commands) throws IOException {
    Objects.requireNonNull(repositoryName, "repositoryName");
    Objects.requireNonNull(commands, "commands");
    if (commands.isEmpty()) {
      return List.of();
    }
    return switch (locking) {
      case NONE ->
          transactionContext.execute(
              operation,
              session -> executeWork(session, repositoryName, commands));
      case REPOSITORY ->
          transactionContext.executeWithRepositoryLock(
              operation,
              repositoryName,
              session -> executeWork(session, repositoryName, commands));
    };
  }

  private List<R> executeWork(
      Session session, String repositoryName, List<C> commands) throws IOException {
    session.setJdbcBatchSize(commands.size());
    return work.execute(session, repositoryName, commands);
  }
}
