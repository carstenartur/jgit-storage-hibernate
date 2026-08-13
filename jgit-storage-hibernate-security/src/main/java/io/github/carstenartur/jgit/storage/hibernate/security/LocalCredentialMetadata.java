/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import java.time.Instant;
import java.util.Objects;

/** Non-secret local password lifecycle and lockout metadata. */
public record LocalCredentialMetadata(
    String principalId,
    Instant changedAt,
    int failedAttemptCount,
    Instant lockedUntil,
    long securityVersion) {

  /** Creates and validates metadata without exposing the persisted verifier. */
  public LocalCredentialMetadata {
    if (principalId == null || principalId.isBlank() || principalId.length() > 128) {
      throw new IllegalArgumentException("principalId must contain 1 to 128 characters");
    }
    changedAt = Objects.requireNonNull(changedAt, "changedAt");
    if (failedAttemptCount < 0) {
      throw new IllegalArgumentException("failedAttemptCount must not be negative");
    }
    if (securityVersion < 1) {
      throw new IllegalArgumentException("securityVersion must be positive");
    }
  }

  /** Return whether the credential is temporarily locked at the supplied instant. */
  public boolean lockedAt(Instant instant) {
    Objects.requireNonNull(instant, "instant");
    return lockedUntil != null && instant.isBefore(lockedUntil);
  }
}
