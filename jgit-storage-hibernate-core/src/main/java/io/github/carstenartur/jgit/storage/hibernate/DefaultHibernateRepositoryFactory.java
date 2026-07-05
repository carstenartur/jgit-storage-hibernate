/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import java.io.IOException;
import java.util.Objects;

import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepositoryBuilder;
import org.hibernate.SessionFactory;

/** Default factory that opens Hibernate-backed JGit repositories from a {@link SessionFactory}. */
public final class DefaultHibernateRepositoryFactory implements HibernateRepositoryFactory {

  private final SessionFactory sessionFactory;

  /**
   * Create a repository factory.
   *
   * @param sessionFactory Hibernate session factory configured with the core storage entities
   */
  public DefaultHibernateRepositoryFactory(SessionFactory sessionFactory) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
  }

  @Override
  public HibernateGitStorage open(RepositoryName repositoryName) {
    Objects.requireNonNull(repositoryName, "repositoryName");
    try {
      HibernateRepository repository =
          new HibernateRepositoryBuilder()
              .setSessionFactory(sessionFactory)
              .setRepositoryName(repositoryName.value())
              .build();
      if (!repository.exists()) {
        repository.create(true);
      }
      return new DefaultHibernateGitStorage(repository);
    } catch (IOException e) {
      throw new HibernateStorageException("Could not open Hibernate-backed repository " + repositoryName, e);
    }
  }
}
