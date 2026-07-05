/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.repository;

import java.io.IOException;
import java.util.Objects;
import org.eclipse.jgit.internal.storage.dfs.DfsReaderOptions;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryBuilder;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.hibernate.SessionFactory;

/** Internal builder for {@link HibernateRepository}. */
class HibernateRepositoryBuilder
    extends DfsRepositoryBuilder<HibernateRepositoryBuilder, HibernateRepository> {

  private SessionFactory sessionFactory;
  private String repositoryName;

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

  @Override
  public HibernateRepository build() throws IOException {
    if (repositoryName == null || repositoryName.isBlank()) {
      throw new IllegalArgumentException("repositoryName is required");
    }
    Objects.requireNonNull(sessionFactory, "sessionFactory");
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
