/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

/** Controls whether a validated external identity may provision a new Security principal. */
public enum ExternalPrincipalProvisioningPolicy {
  /** Resolve only an existing canonical issuer/subject binding. */
  EXISTING_ONLY,

  /** Create a new external principal when no binding exists yet. */
  CREATE_IF_MISSING
}
