/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

/** Stable storage-level operation checked by an optional repository access policy. */
public enum RepositoryAccessOperation {
  DISCOVER(false),
  READ(false),
  CREATE_REF(true),
  UPDATE_REF(true),
  DELETE_REF(true),
  FORCE_UPDATE(true),
  DELETE_REPOSITORY(false);

  private final boolean refScoped;

  RepositoryAccessOperation(boolean refScoped) {
    this.refScoped = refScoped;
  }

  /**
   * Return whether this operation must identify one exact ref.
   *
   * @return {@code true} for ref mutations
   */
  public boolean refScoped() {
    return refScoped;
  }
}
