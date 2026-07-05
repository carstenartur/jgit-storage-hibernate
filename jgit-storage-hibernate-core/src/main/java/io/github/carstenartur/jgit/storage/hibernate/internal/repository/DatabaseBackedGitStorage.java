/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.internal.repository;

import io.github.carstenartur.jgit.storage.hibernate.HibernateGitStorage;
import org.eclipse.jgit.lib.Repository;

/** Internal {@link HibernateGitStorage} implementation backed by a {@link HibernateRepository}. */
public final class DatabaseBackedGitStorage implements HibernateGitStorage {

  private final HibernateRepository repository;

  public DatabaseBackedGitStorage(HibernateRepository repository) {
    this.repository = repository;
  }

  @Override
  public Repository repository() {
    return repository;
  }

  @Override
  public void close() {
    repository.close();
  }
}
