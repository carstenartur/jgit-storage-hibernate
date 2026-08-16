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

/** Indicates that an explicitly authorized logical repository does not exist. */
public final class RepositoryDoesNotExistException extends HibernateStorageException {

  private final RepositoryName repositoryName;

  /**
   * Create a missing-repository result at Core's authoritative existence check.
   *
   * @param repositoryName missing logical repository
   */
  public RepositoryDoesNotExistException(RepositoryName repositoryName) {
    super(
        "Repository "
            + Objects.requireNonNull(repositoryName, "repositoryName")
            + " does not exist");
    this.repositoryName = repositoryName;
  }

  /** @return missing logical repository */
  public RepositoryName repositoryName() {
    return repositoryName;
  }
}
