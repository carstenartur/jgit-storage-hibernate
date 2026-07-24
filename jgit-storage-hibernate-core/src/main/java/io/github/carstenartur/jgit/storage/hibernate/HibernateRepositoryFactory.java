/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

/** Factory for opening and deleting Hibernate-backed JGit repositories. */
public interface HibernateRepositoryFactory {

  /**
   * Open or create a repository with the given logical repository name.
   *
   * @param repositoryName logical repository name used by the storage backend
   * @return opened storage facade
   */
  HibernateGitStorage open(RepositoryName repositoryName);

  /**
   * Transactionally delete all persisted state for one logical repository.
   *
   * <p>All storage handles opened through {@link DefaultHibernateRepositoryFactory} for the
   * requested name and the same Hibernate {@code SessionFactory} must be closed first, including
   * handles opened by another factory instance. The operation is idempotent and never deletes rows
   * belonging to another logical repository. Code that bypasses the public factory and constructs
   * internal repository implementations directly is responsible for closing those handles before
   * invoking this lifecycle operation.
   *
   * @param repositoryName logical repository to delete
   * @return deleted row counts
   */
  RepositoryDeletionResult deleteRepository(RepositoryName repositoryName);
}
