/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import org.eclipse.jgit.lib.Repository;

/**
 * Public facade for a Hibernate-backed JGit repository.
 *
 * <p>This interface deliberately exposes only public JGit API types. Implementations may use JGit
 * DFS/Reftable internals internally, but those types must not leak through this facade.
 */
public interface HibernateGitStorage extends AutoCloseable {

  /**
   * Return the opened JGit repository.
   *
   * @return repository backed by Hibernate-managed storage
   */
  Repository repository();

  /** Close repository resources. */
  @Override
  void close();
}
