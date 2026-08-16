/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

/**
 * Trusted host strategy for assigning a stable ID when an external principal is first provisioned.
 *
 * <p>The strategy is application configuration, not request data. A consumer may therefore align
 * the Security principal ID with its own stable application principal ID without accepting a
 * client-supplied authorization identity.
 */
@FunctionalInterface
public interface ExternalPrincipalIdGenerator {

  /** Return the stable ID for a newly provisioned external identity. */
  String generate(ExternalPrincipalIdentity identity);
}
