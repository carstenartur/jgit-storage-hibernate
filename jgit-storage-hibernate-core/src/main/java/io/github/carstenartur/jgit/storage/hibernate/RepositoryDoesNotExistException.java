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
   * Create a missing-repository result while retaining the lower-level open failure.
   *
   * @param repositoryName missing logical repository
   * @param cause lower-level storage failure that established absence
   */
  public RepositoryDoesNotExistException(
      RepositoryName repositoryName, HibernateStorageException cause) {
    super(
        "Repository "
            + Objects.requireNonNull(repositoryName, "repositoryName")
            + " does not exist",
        Objects.requireNonNull(cause, "cause"));
    this.repositoryName = repositoryName;
  }

  /** @return missing logical repository */
  public RepositoryName repositoryName() {
    return repositoryName;
  }
}
