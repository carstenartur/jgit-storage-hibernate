/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.repository;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessRequest;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitRepositoryLifecycleEntity;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitRepositoryLockEntity;
import io.github.carstenartur.jgit.storage.hibernate.objects.HibernateObjDatabase;
import io.github.carstenartur.jgit.storage.hibernate.objects.ReadAheadHibernateObjDatabase;
import io.github.carstenartur.jgit.storage.hibernate.refs.HibernateRefDatabase;
import io.github.carstenartur.jgit.storage.hibernate.refs.HibernateReflogReader;
import io.github.carstenartur.jgit.storage.hibernate.refs.HibernateReflogWriter;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.PackFileReadMetrics;
import io.github.carstenartur.jgit.storage.hibernate.transaction.PackPublicationSelectionMetrics;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StagingSpillMetrics;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageByteMetrics;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationBreakdown;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationKind;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationMetrics;
import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Consumer;
import org.eclipse.jgit.internal.storage.dfs.DfsRepository;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.ReflogReader;
import org.hibernate.SessionFactory;

/**
 * A JGit repository stored in a relational database through Hibernate.
 *
 * <p>This implementation uses JGit's DFS/Reftable storage abstractions internally. Consumers should
 * depend on the public facade package instead of importing this class directly unless they need JGit
 * repository-level integration.
 */
public class HibernateRepository extends DfsRepository {

  private final ReadAheadHibernateObjDatabase objectDatabase;
  private final HibernateRefDatabase refDatabase;
  private final HibernateReflogWriter reflogWriter;
  private final HibernateTransactionContext transactionContext;
  private final SessionFactory sessionFactory;
  private final String repositoryName;
  private final RepositoryName logicalRepositoryName;
  private final Consumer<RepositoryAccessRequest> accessGuard;
  private final Runnable afterClose;
  private String gitwebDescription;

  HibernateRepository(HibernateRepositoryBuilder builder) throws IOException {
    super(builder);
    this.sessionFactory = builder.getSessionFactory();
    this.repositoryName = builder.getRepositoryName();
    this.logicalRepositoryName = new RepositoryName(repositoryName);
    this.accessGuard = Objects.requireNonNull(builder.getAccessGuard(), "accessGuard");
    this.afterClose = Objects.requireNonNull(builder.getAfterClose(), "afterClose");
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
    ensureRepositoryRows();
  }

  public static HibernateRepository create(SessionFactory sessionFactory, String repositoryName)
      throws IOException {
    return new HibernateRepositoryBuilder()
        .setSessionFactory(sessionFactory)
        .setRepositoryName(repositoryName)
        .build();
  }

  /**
   * Create a repository carrying one explicit access guard into sensitive Core operations.
   *
   * @param sessionFactory Hibernate session factory
   * @param repositoryName logical repository name
   * @param accessGuard guard invoked for ref mutations
   * @return opened repository
   * @throws IOException when repository initialization fails
   */
  public static HibernateRepository create(
      SessionFactory sessionFactory,
      String repositoryName,
      Consumer<RepositoryAccessRequest> accessGuard)
      throws IOException {
    return new HibernateRepositoryBuilder()
        .setSessionFactory(sessionFactory)
        .setRepositoryName(repositoryName)
        .setAccessGuard(accessGuard)
        .build();
  }

  /**
   * Create a guarded repository whose JGit close operation releases an owning lifecycle handle.
   *
   * <p>This overload supports framework integrations such as JGit Smart HTTP whose resolver returns
   * only a {@link org.eclipse.jgit.lib.Repository}. JGit closes that returned repository directly,
   * so lifecycle ownership must travel with the repository rather than a separate storage wrapper.
   *
   * @param sessionFactory Hibernate session factory
   * @param repositoryName logical repository name
   * @param accessGuard guard invoked for ref mutations
   * @param afterClose callback invoked after JGit closes the repository databases
   * @return opened repository
   * @throws IOException when repository initialization fails
   */
  public static HibernateRepository create(
      SessionFactory sessionFactory,
      String repositoryName,
      Consumer<RepositoryAccessRequest> accessGuard,
      Runnable afterClose)
      throws IOException {
    return new HibernateRepositoryBuilder()
        .setSessionFactory(sessionFactory)
        .setRepositoryName(repositoryName)
        .setAccessGuard(accessGuard)
        .setAfterClose(afterClose)
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

  /** @return immutable public logical repository name */
  public RepositoryName getLogicalRepositoryName() {
    return logicalRepositoryName;
  }

  public SessionFactory getSessionFactory() {
    return sessionFactory;
  }

  /**
   * Require one access operation through the policy bound when this repository was opened.
   *
   * @param request exact repository or ref mutation request
   */
  public void requireAccess(RepositoryAccessRequest request) {
    Objects.requireNonNull(request, "request");
    if (!logicalRepositoryName.equals(request.repositoryName())) {
      throw new IllegalArgumentException(
          "request repository "
              + request.repositoryName()
              + " does not match "
              + logicalRepositoryName);
    }
    accessGuard.accept(request);
  }

  /**
   * Return monotone transaction and repository-lock metrics for this repository instance.
   *
   * @return current aggregate metrics snapshot, or zero counters when metrics are disabled
   */
  public StorageOperationMetrics getStorageOperationMetrics() {
    return transactionContext.metricsSnapshot();
  }

  /**
   * Return the immutable per-operation view of the aggregate repository metrics.
   *
   * <p>The category total equals {@link #getStorageOperationMetrics()} for the same snapshot. The
   * breakdown is empty when metrics are disabled.
   *
   * @return monotone metrics grouped by stable storage operation kind
   */
  public StorageOperationBreakdown getStorageOperationBreakdown() {
    return transactionContext.operationBreakdownSnapshot();
  }

  /**
   * Return committed pack-extension database fallback reads grouped by extension and storage mode.
   *
   * <p>Catalogued chunked extensions are absent because they open without a pack-file transaction.
   * The snapshot is zero when repository diagnostics are disabled.
   *
   * @return monotone pack-file read attribution
   */
  public PackFileReadMetrics getPackFileReadMetrics() {
    return objectDatabase.packFileReadMetricsSnapshot();
  }

  /**
   * Return monotone staged-file, database-payload and read-ahead byte counters.
   *
   * <p>These counters complement transaction and statement statistics by showing physical byte
   * movement. They are zero when repository diagnostics are disabled.
   *
   * @return current storage byte metrics
   */
  public StorageByteMetrics getStorageByteMetrics() {
    return objectDatabase.storageByteMetricsSnapshot();
  }

  /**
   * Return monotone memory-to-file staging spill counters.
   *
   * @return current staging spill metrics, or zero counters when diagnostics are disabled
   */
  public StagingSpillMetrics getStagingSpillMetrics() {
    return objectDatabase.stagingSpillMetricsSnapshot();
  }

  /**
   * Return monotone direct-versus-pre-persisted pack publication selections.
   *
   * @return current selection metrics, or zero counters when diagnostics are disabled
   */
  public PackPublicationSelectionMetrics getPackPublicationSelectionMetrics() {
    return objectDatabase.publicationSelectionMetricsSnapshot();
  }

  /**
   * Return the deterministic staged-payload threshold for two-phase publication.
   *
   * @return minimum staged logical-pack bytes selected for pre-persistence
   */
  public long getMinimumPrePersistedPackPayloadBytes() {
    return objectDatabase.minimumPrePersistedPayloadBytes();
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
          StorageOperationKind.REF_PUBLICATION,
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

  private void ensureRepositoryRows() throws IOException {
    try {
      transactionContext.execute(
          StorageOperationKind.REPOSITORY_INITIALIZATION,
          session -> {
            Instant createdAt = Instant.now();
            if (session.find(GitRepositoryLifecycleEntity.class, repositoryName) == null) {
              GitRepositoryLifecycleEntity lifecycle = new GitRepositoryLifecycleEntity();
              lifecycle.setRepositoryName(repositoryName);
              lifecycle.setCreatedAt(createdAt);
              session.persist(lifecycle);
            }
            if (session.find(GitRepositoryLockEntity.class, repositoryName) == null) {
              GitRepositoryLockEntity lock = new GitRepositoryLockEntity();
              lock.setRepositoryName(repositoryName);
              lock.setCreatedAt(createdAt);
              session.persist(lock);
            }
            session.flush();
            return null;
          });
    } catch (RuntimeException concurrentInsert) {
      try {
        transactionContext.execute(
            StorageOperationKind.REPOSITORY_INITIALIZATION,
            session -> {
              boolean lifecycleExists =
                  session.find(GitRepositoryLifecycleEntity.class, repositoryName) != null;
              boolean lockExists =
                  session.find(GitRepositoryLockEntity.class, repositoryName) != null;
              if (!lifecycleExists || !lockExists) {
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

  @Override
  protected void doClose() {
    Throwable closeFailure = null;
    try {
      super.doClose();
    } catch (RuntimeException | Error failure) {
      closeFailure = failure;
      throw failure;
    } finally {
      try {
        afterClose.run();
      } catch (RuntimeException | Error callbackFailure) {
        if (closeFailure != null) {
          closeFailure.addSuppressed(callbackFailure);
        } else {
          throw callbackFailure;
        }
      }
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
