/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import java.util.Objects;

import org.eclipse.jgit.lib.Repository;

/** Default {@link HibernateGitStorage} implementation wrapping a JGit repository. */
public final class DefaultHibernateGitStorage implements HibernateGitStorage {

  private final Repository repository;

  /**
   * Create a storage facade.
   *
   * @param repository repository to expose and close
   */
  public DefaultHibernateGitStorage(Repository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
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
