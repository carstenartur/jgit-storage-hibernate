/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.repository;

import io.github.carstenartur.jgit.storage.hibernate.objects.HibernateObjDatabase;
import io.github.carstenartur.jgit.storage.hibernate.refs.HibernateRefDatabase;
import io.github.carstenartur.jgit.storage.hibernate.refs.HibernateReflogReader;
import io.github.carstenartur.jgit.storage.hibernate.refs.HibernateReflogWriter;
import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import java.io.IOException;
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

  private final HibernateObjDatabase objectDatabase;
  private final HibernateRefDatabase refDatabase;
  private final HibernateReflogWriter reflogWriter;
  private final HibernateTransactionContext transactionContext;
  private final SessionFactory sessionFactory;
  private final String repositoryName;
  private String gitwebDescription;

  /**
   * Create a repository from a builder.
   *
   * @param builder configured repository builder
   */
  HibernateRepository(HibernateRepositoryBuilder builder) {
    super(builder);
    this.sessionFactory = builder.getSessionFactory();
    this.repositoryName = builder.getRepositoryName();
    this.transactionContext = new HibernateTransactionContext(sessionFactory);
    this.objectDatabase =
        new HibernateObjDatabase(
            this,
            builder.getReaderOptions(),
            sessionFactory,
            repositoryName,
            transactionContext);
    this.reflogWriter = new HibernateReflogWriter(transactionContext, repositoryName);
    this.refDatabase = new HibernateRefDatabase(this);
  }

  /**
   * Create a repository for the given logical repository name.
   *
   * @param sessionFactory Hibernate session factory
   * @param repositoryName logical repository name
   * @return configured repository instance
   * @throws IOException if repository cannot be built
   */
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

  /**
   * Return the logical database repository name.
   *
   * @return repository name
   */
  public String getRepositoryName() {
    return repositoryName;
  }

  /**
   * Return the Hibernate session factory.
   *
   * @return session factory
   */
  public SessionFactory getSessionFactory() {
    return sessionFactory;
  }

  /**
   * Execute repository storage work in one shared transaction.
   *
   * <p>If a transaction is rolled back after JGit has constructed an in-memory pack or Reftable
   * view, the repository caches are invalidated before the failure is returned. The next read then
   * rebuilds its view from committed database rows instead of retaining rolled-back state.
   *
   * @param work work that may persist packs, refs and reflog rows
   * @param <T> work result type
   * @return work result
   * @throws IOException if storage work fails
   */
  public <T> T inTransaction(HibernateTransactionContext.Work<T> work) throws IOException {
    try {
      return transactionContext.execute(work);
    } catch (IOException | RuntimeException exception) {
      invalidateStorageCaches(exception);
      throw exception;
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

  /**
   * Return the queryable reflog writer.
   *
   * <p>Normal {@code RefUpdate} operations already write queryable reflogs. This writer remains
   * available for importing externally produced history.
   *
   * @return reflog writer
   */
  public HibernateReflogWriter getReflogWriter() {
    return reflogWriter;
  }

  @Override
  public ReflogReader getReflogReader(String refName) throws IOException {
    return new HibernateReflogReader(sessionFactory, repositoryName, refName);
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
