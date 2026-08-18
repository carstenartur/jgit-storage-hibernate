/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.smarthttp;

/** Non-secret classification of the authentication mechanism selected for one request. */
public enum SmartHttpAuthenticationKind {
  /** A bearer token validated by the host application, such as an OIDC or OAuth2 token. */
  EXTERNAL_BEARER,

  /** A one-way access token managed by the optional Security module. */
  SECURITY_ACCESS_TOKEN,

  /** A local username/password verifier managed by the optional Security module. */
  SECURITY_LOCAL_BASIC,

  /** An already authenticated application, gateway or servlet-container context. */
  APPLICATION_CONTEXT,

  /** A service-specific bearer credential in an explicitly reserved namespace. */
  SERVICE
}
