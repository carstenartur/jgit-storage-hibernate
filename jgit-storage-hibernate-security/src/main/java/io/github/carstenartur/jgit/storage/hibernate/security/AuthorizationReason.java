/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package io.github.carstenartur.jgit.storage.hibernate.security;

/** Stable non-secret reason code emitted by one authorization decision. */
public enum AuthorizationReason {
  GRANT_ALLOWED(true),
  REF_RULE_ALLOWED(true),
  EXPLICIT_GRANT_DENY(false),
  PROTECTED_REF_DENY(false),
  NO_MATCHING_GRANT(false);

  private final boolean allowed;

  AuthorizationReason(boolean allowed) {
    this.allowed = allowed;
  }

  /** Returns whether this reason represents an allowed decision. */
  public boolean allowed() {
    return allowed;
  }
}
