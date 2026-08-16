/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

/** Stable, non-secret outcome reason for local credential authentication. */
public enum SecurityAuthenticationReason {
  PASSWORD_AUTHENTICATED(true),
  ACCESS_TOKEN_AUTHENTICATED(true),
  INVALID_CREDENTIALS(false),
  PRINCIPAL_NOT_ACTIVE(false),
  PASSWORD_LOCKED(false),
  CREDENTIAL_NOT_CONFIGURED(false),
  MALFORMED_ACCESS_TOKEN(false),
  ACCESS_TOKEN_REVOKED(false),
  ACCESS_TOKEN_EXPIRED(false),
  CREDENTIAL_SCOPE_DENY(false),
  AUTHENTICATION_FAILURE(false);

  private final boolean authenticated;

  SecurityAuthenticationReason(boolean authenticated) {
    this.authenticated = authenticated;
  }

  /** Return whether this reason represents successful authentication. */
  public boolean authenticated() {
    return authenticated;
  }
}
