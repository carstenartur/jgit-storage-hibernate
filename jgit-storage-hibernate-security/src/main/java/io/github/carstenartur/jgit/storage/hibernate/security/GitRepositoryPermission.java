/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package io.github.carstenartur.jgit.storage.hibernate.security;

/** Git-generic permissions persisted by the optional security capability. */
public enum GitRepositoryPermission {
  DISCOVER,
  READ,
  CREATE_REF,
  UPDATE_REF,
  DELETE_REF,
  FORCE_UPDATE,
  ADMINISTER;

  /**
   * Returns whether this granted permission covers the requested permission.
   *
   * <p>{@link #ADMINISTER} covers every repository permission. No write permission implies
   * repository deletion.
   */
  public boolean includes(GitRepositoryPermission requested) {
    return this == ADMINISTER || this == requested;
  }
}
