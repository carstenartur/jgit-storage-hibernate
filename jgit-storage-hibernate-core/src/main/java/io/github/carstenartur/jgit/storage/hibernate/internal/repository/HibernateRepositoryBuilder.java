/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.internal.repository;

import java.io.IOException;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryBuilder;
import org.hibernate.SessionFactory;

/** Internal builder for {@link HibernateRepository}. */
public class HibernateRepositoryBuilder
    extends DfsRepositoryBuilder<HibernateRepositoryBuilder, HibernateRepository> {

  private SessionFactory sessionFactory;
  private String repositoryName;

  public HibernateRepositoryBuilder setSessionFactory(SessionFactory sessionFactory) {
    this.sessionFactory = sessionFactory;
    return self();
  }

  public SessionFactory getSessionFactory() {
    return sessionFactory;
  }

  public HibernateRepositoryBuilder setRepositoryName(String repositoryName) {
    this.repositoryName = repositoryName;
    return self();
  }

  public String getRepositoryName() {
    return repositoryName;
  }

  @Override
  public HibernateRepository build() throws IOException {
    if (sessionFactory == null) {
      throw new IllegalArgumentException("sessionFactory must be set before build()");
    }
    if (repositoryName == null || repositoryName.isBlank()) {
      throw new IllegalArgumentException("repositoryName must be set before build()");
    }
    setRepositoryDescription(new org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription(repositoryName));
    return new HibernateRepository(this);
  }
}
