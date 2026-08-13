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
 * Principal-bound handle whose JGit repository rechecks every ref mutation at publication time.
 *
 * @param <C> immutable access-context type
 */
public interface AuthorizedRepositorySession<C> extends AutoCloseable {

  /** @return logical repository name */
  RepositoryName repositoryName();

  /** @return explicitly bound access context */
  C accessContext();

  /**
   * Return the guarded JGit repository.
   *
   * <p>The session has already required {@link RepositoryAccessOperation#DISCOVER} and {@link
   * RepositoryAccessOperation#READ}. Ref updates performed through this repository are rechecked at
   * the transactional publication boundary.
   *
   * @return guarded repository
   */
  Repository repository();

  /**
   * Perform an application-level precheck using the same bound policy and context.
   *
   * <p>The request must refer to this session's repository. Ref mutations are still checked again by
   * Core when JGit publishes them.
   *
   * @param request requested operation
   */
  void require(RepositoryAccessRequest request);

  /** Close repository resources. */
  @Override
  void close();
}
