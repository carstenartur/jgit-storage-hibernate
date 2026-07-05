/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

/** Factory for opening Hibernate-backed JGit repositories. */
public interface HibernateRepositoryFactory {

  /**
   * Open or create a repository with the given logical repository name.
   *
   * @param repositoryName logical repository name used by the storage backend
   * @return opened storage facade
   */
  HibernateGitStorage open(RepositoryName repositoryName);
}
