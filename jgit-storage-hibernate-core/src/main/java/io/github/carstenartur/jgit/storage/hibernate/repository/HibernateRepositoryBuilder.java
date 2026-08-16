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
import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;
import org.eclipse.jgit.internal.storage.dfs.DfsReaderOptions;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryBuilder;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.hibernate.SessionFactory;

/** Internal builder for {@link HibernateRepository}. */
class HibernateRepositoryBuilder
    extends DfsRepositoryBuilder<HibernateRepositoryBuilder, HibernateRepository> {

  private static final Consumer<RepositoryAccessRequest> UNRESTRICTED_ACCESS = ignored -> {};
  private static final Runnable NO_AFTER_CLOSE = () -> {};

  private SessionFactory sessionFactory;
  private String repositoryName;
  private Consumer<RepositoryAccessRequest> accessGuard = UNRESTRICTED_ACCESS;
  private Runnable afterClose = NO_AFTER_CLOSE;

  /**
   * Set the Hibernate session factory.
   *
   * @param sessionFactory session factory configured with storage entities
   * @return this builder
   */
  public HibernateRepositoryBuilder setSessionFactory(SessionFactory sessionFactory) {
    this.sessionFactory = sessionFactory;
    return self();
  }

  /**
   * Return the configured session factory.
   *
   * @return session factory
   */
  public SessionFactory getSessionFactory() {
    return sessionFactory;
  }

  /**
   * Set the logical repository name used to partition database rows.
   *
   * @param repositoryName repository name
   * @return this builder
   */
  public HibernateRepositoryBuilder setRepositoryName(String repositoryName) {
    this.repositoryName = repositoryName;
    if (repositoryName != null && getRepositoryDescription() == null) {
      setRepositoryDescription(new DfsRepositoryDescription(repositoryName));
    }
    return self();
  }

  /**
   * Return the logical repository name.
   *
   * @return repository name
   */
  public String getRepositoryName() {
    return repositoryName;
  }

  /**
   * Set the explicit access guard carried by the opened repository.
   *
   * @param accessGuard guard invoked at sensitive Core publication boundaries
   * @return this builder
   */
  public HibernateRepositoryBuilder setAccessGuard(
      Consumer<RepositoryAccessRequest> accessGuard) {
    this.accessGuard = Objects.requireNonNull(accessGuard, "accessGuard");
    return self();
  }

  /** @return configured access guard */
  public Consumer<RepositoryAccessRequest> getAccessGuard() {
    return accessGuard;
  }

  /**
   * Set a lifecycle callback invoked when JGit closes the repository handle.
   *
   * @param afterClose callback invoked after repository databases close
   * @return this builder
   */
  public HibernateRepositoryBuilder setAfterClose(Runnable afterClose) {
    this.afterClose = Objects.requireNonNull(afterClose, "afterClose");
    return self();
  }

  /** @return configured repository-close callback */
  public Runnable getAfterClose() {
    return afterClose;
  }

  @Override
  public HibernateRepository build() throws IOException {
    if (repositoryName == null || repositoryName.isBlank()) {
      throw new IllegalArgumentException("repositoryName is required");
    }
    Objects.requireNonNull(sessionFactory, "sessionFactory");
    Objects.requireNonNull(accessGuard, "accessGuard");
    Objects.requireNonNull(afterClose, "afterClose");
    if (getReaderOptions() == null) {
      setReaderOptions(new DfsReaderOptions());
    }
    if (getRepositoryDescription() == null
        || getRepositoryDescription().getRepositoryName() == null
        || getRepositoryDescription().getRepositoryName().isBlank()) {
      setRepositoryDescription(new DfsRepositoryDescription(repositoryName));
    }
    return new HibernateRepository(this);
  }
}
