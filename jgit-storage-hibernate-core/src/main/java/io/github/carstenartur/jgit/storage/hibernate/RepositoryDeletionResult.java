/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

/** Counts rows removed by one transactional logical-repository deletion. */
public record RepositoryDeletionResult(int packRows, int reflogRows, int projectionRows) {

  /**
   * Return whether any persisted state was removed.
   *
   * @return {@code true} when at least one row was deleted
   */
  public boolean deletedAnything() {
    return packRows + reflogRows + projectionRows > 0;
  }
}
