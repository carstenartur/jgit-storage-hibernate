/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

/** Describes whether a transfer provisions a new history or advances an existing target. */
public enum RepositoryTransferMode {
  /** Provision selected refs in a target that has no existing refs. */
  INITIAL_CLONE,

  /** Transfer newly reachable objects and explicitly advance existing target refs. */
  INCREMENTAL_FETCH
}
