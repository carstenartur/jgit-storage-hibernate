/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

/** Stable operation recorded for local credential and token lifecycle evidence. */
public enum SecurityIdentityAuditOperation {
  PASSWORD_SET,
  PASSWORD_REMOVED,
  PASSWORD_UNLOCKED,
  PASSWORD_AUTHENTICATION,
  ACCESS_TOKEN_ISSUED,
  ACCESS_TOKEN_AUTHENTICATION,
  ACCESS_TOKEN_REVOKED
}
