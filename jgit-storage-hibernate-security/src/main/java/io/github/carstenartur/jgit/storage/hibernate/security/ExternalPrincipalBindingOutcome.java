/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

/** Stable result of resolving an already authenticated external identity. */
public enum ExternalPrincipalBindingOutcome {
  /** An unchanged existing binding was resolved. */
  RESOLVED,

  /** A new external Security principal was provisioned. */
  CREATED,

  /** The existing binding was resolved and mutable display metadata was refreshed. */
  UPDATED_PROFILE
}
