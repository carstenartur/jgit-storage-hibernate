/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

/** Stable outcome of reconciling a managed repository authorization snapshot. */
public enum RepositoryAuthorizationPolicySyncStatus {
  /** The complete desired policy became the active managed state. */
  APPLIED,

  /** The same version and content were already active. */
  NO_OP,

  /** The requested desired version is older than the active source version. */
  STALE,

  /** Expected version, digest or repository ownership did not match. */
  CONFLICT
}
