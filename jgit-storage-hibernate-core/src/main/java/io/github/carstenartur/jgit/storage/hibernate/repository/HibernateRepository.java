/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.repository;

import io.github.carstenartur.jgit.storage.hibernate.entity.GitRepositoryLockEntity;
import io.github.carstenartur.jgit.storage.hibernate.objects.HibernateObjDatabase;
import io.github.carstenartur.jgit.storage.hibernate.objects.ReadAheadHibernateObjDatabase;
import io.github.carstenartur.jgit.storage.hibernate.refs.HibernateRefDatabase;
import io.github.carstenartur.jgit.storage.hibernate.refs.HibernateReflogReader;
import io.github.carstenartur.jgit.storage.hibernate.refs.HibernateReflogWriter;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationMetrics;
import java.io.IOException;
import java.time.Instant;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.ReflogReader;
import org.eclipse.jgit.transport.ReceivePack;
import org.hibernate.SessionFactory;

/**
 * A JGit repository stored in a relational database through Hibernate.
 *
 * <p>This implementation uses JGit's DFS/Reftable storage abstractions internally. Consumers should
 * depend on the public facade package instead of importing this class directly unless they need JGit
 * repository-level integration.
 */
public class HibernateRepository extends DfsRepository {

  private final HibernateObjDatabase objectDatabase;
  private final HibernateRefDatabase refDatabase;
  private final HibernateReflogWriter reflogWriter;
  private final HibernateTransactionContext transactionContext;
  private final SessionFactory sessionFactory;
  private final String repositoryName;
  private String gitwebDescription;

  HibernateRepository(HibernateRepositoryBuilder builder) throws IOException {
    super(builder);
    this.sessionFactory = builder.getSessionFactory();
    this.repositoryName = builder.getRepositoryName();
    this.transactionContext = new HibernateTransactionContext(sessionFactory);
    this.objectDatabase =
        new ReadAheadHibernateObjDatabase(
            this,
            builder.getReaderOptions(),
            sessionFactory,
            repositoryName,
            transactionContext);
    this.reflogWriter = new HibernateReflogWriter(transactionContext, repositoryName);
    this.refDatabase = new HibernateRefDatabase(this);
    ensureRepositoryLockRow();
  }

  public static HibernateRepository create(SessionFactory sessionFactory, String repositoryName)
      throws IOException {
    return new HibernateRepositoryBuilder()
        .setSessionFactory(sessionFactory)
        .setRepositoryName(repositoryName)
        .build();
  }

  @Override
  public HibernateObjDatabase getObjectDatabase() {
    return objectDatabase;
  }

  @Override
  public RefDatabase getRefDatabase() {
    return refDatabase;
  }

  public String getRepositoryName() {
    return repositoryName;
  }

  public SessionFactory getSessionFactory() {
    return sessionFactory;
  }

  /**
   * Create a receive-pack server that commits one logical push in one repository transaction.
   *
   * <p>The returned adapter starts its transaction when pack ingestion begins, joins pack,
   * reftable, reflog and ref storage callbacks, commits before JGit sends the success status, and
   * rolls back plus invalidates storage caches when processing fails. A ref-only push starts the
   * transaction immediately before command execution.
   *
   * <p>Callers may configure hooks and limits on the returned instance exactly as on a normal
   * {@link ReceivePack}. Pre-receive hooks run while the transaction is active; post-receive hooks
   * run after commit.
   *
   * @return transaction-aware receive-pack instance
   */
  public ReceivePack newReceivePack() {
    return new HibernateReceivePack(this);
  }

  /**
   * Return monotone transaction and repository-lock metrics for this repository instance.
   *
   * @return current metrics snapshot, or zero counters when metrics are disabled
   */
  public StorageOperationMetrics getStorageOperationMetrics() {
    return transactionContext.metricsSnapshot();
  }

  /** Execute repository storage work in one shared transaction. */
  public <T> T inTransaction(HibernateTransactionContext.Work<T> work) throws IOException {
    try {
      return transactionContext.execute(work);
    } catch (IOException | RuntimeException exception) {
      invalidateStorageCaches(exception);
      throw exception;
    }
  }

  /**
   * Execute a ref mutation while holding the cross-SessionFactory repository lock.
   *
   * <p>Both the DFS pack list and Reftable stack are invalidated after the database row lock is
   * obtained. The optimistic expected-old-ID comparison therefore observes updates committed by a
   * different repository instance before the lock was acquired.
   */
  public <T> T inRefTransaction(HibernateTransactionContext.Work<T> work) throws IOException {
    try {
      return transactionContext.executeWithRepositoryLock(
          repositoryName,
          session -> {
            objectDatabase.close();
            refDatabase.refresh();
            return work.execute(session);
          });
    } catch (IOException | RuntimeException exception) {
      invalidateStorageCaches(exception);
      throw exception;
    }
  }

  HibernateTransactionContext.TransactionScope beginReceiveTransaction() {
    return transactionContext.beginScope();
  }

  void resetStorageCaches() {
    objectDatabase.close();
    refDatabase.refresh();
  }

  private void ensureRepositoryLockRow() throws IOException {
    try {
      transactionContext.execute(
          session -> {
            if (session.find(GitRepositoryLockEntity.class, repositoryName) == null) {
              GitRepositoryLockEntity lock = new GitRepositoryLockEntity();
              lock.setRepositoryName(repositoryName);
              lock.setCreatedAt(Instant.now());
              session.persist(lock);
              session.flush();
            }
            return null;
          });
    } catch (RuntimeException concurrentInsert) {
      try {
        transactionContext.execute(
            session -> {
              if (session.find(GitRepositoryLockEntity.class, repositoryName) == null) {
                throw concurrentInsert;
              }
              return null;
            });
      } catch (IOException | RuntimeException verificationFailure) {
        if (verificationFailure != concurrentInsert) {
          verificationFailure.addSuppressed(concurrentInsert);
        }
        throw verificationFailure;
      }
    }
  }

  private void invalidateStorageCaches(Exception originalFailure) {
    try {
      objectDatabase.close();
    } catch (RuntimeException cacheFailure) {
      originalFailure.addSuppressed(cacheFailure);
    }
    try {
      refDatabase.refresh();
    } catch (RuntimeException cacheFailure) {
      originalFailure.addSuppressed(cacheFailure);
    }
  }

  public HibernateReflogWriter getReflogWriter() {
    return reflogWriter;
  }

  @Override
  public ReflogReader getReflogReader(String refName) throws IOException {
    return new HibernateReflogReader(transactionContext, repositoryName, refName);
  }

  @Override
  public String getGitwebDescription() {
    return gitwebDescription;
  }

  @Override
  public void setGitwebDescription(String description) {
    this.gitwebDescription = description;
  }
}
