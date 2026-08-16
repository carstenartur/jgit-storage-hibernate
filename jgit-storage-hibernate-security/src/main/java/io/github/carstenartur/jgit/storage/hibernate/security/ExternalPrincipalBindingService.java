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
 * Framework-neutral binding from an already authenticated external identity to a stable principal.
 *
 * <p>This service never validates a JWT, OIDC session, LDAP password or provider token. The host
 * application must perform authentication first and supply the canonical trusted issuer/subject.
 */
@FunctionalInterface
public interface ExternalPrincipalBindingService {

  /** Resolve or provision the stable Security principal for the validated external identity. */
  ExternalPrincipalBindingResult resolve(
      ExternalPrincipalIdentity identity, ExternalPrincipalProvisioningPolicy provisioningPolicy);
}
