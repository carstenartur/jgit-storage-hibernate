/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import io.github.carstenartur.jgit.storage.hibernate.internal.repository.DatabaseBackedGitStorage;
import io.github.carstenartur.jgit.storage.hibernate.internal.repository.HibernateRepository;
import io.github.carstenartur.jgit.storage.hibernate.internal.repository.HibernateRepositoryBuilder;
import java.io.IOException;
import org.hibernate.SessionFactory;

/** Entry point for opening Hibernate-backed JGit repositories. */
public final class HibernateRepositories {

  private HibernateRepositories() {}

  /**
   * Open a Hibernate-backed JGit repository.
   *
   * @param sessionFactory Hibernate session factory configured with the core entity classes
   * @param repositoryName logical repository name used to partition database storage
   * @return storage facade exposing a public JGit {@code Repository}
   */
  public static HibernateGitStorage open(SessionFactory sessionFactory, RepositoryName repositoryName) {
    try {
      HibernateRepository repository =
          new HibernateRepositoryBuilder()
              .setSessionFactory(sessionFactory)
              .setRepositoryName(repositoryName.value())
              .build();
      return new DatabaseBackedGitStorage(repository);
    } catch (IOException e) {
      throw new HibernateStorageException("Could not open Hibernate-backed JGit repository", e);
    }
  }
}
