/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package io.github.carstenartur.jgit.storage.hibernate.security;

import java.util.Objects;
import java.util.Set;

/** Deterministic authorization outcome containing only bounded, non-secret evidence. */
public record AuthorizationDecision(
    boolean allowed,
    AuthorizationReason reason,
    String evidenceId,
    long policyVersion,
    Set<GitRepositoryPermission> effectivePermissions) {

  /** Creates a validated immutable decision. */
  public AuthorizationDecision {
    Objects.requireNonNull(reason, "reason");
    if (reason.allowed() != allowed) {
      throw new IllegalArgumentException("reason and allowed flag disagree");
    }
    if (evidenceId != null && (evidenceId.isBlank() || evidenceId.length() > 256)) {
      throw new IllegalArgumentException("evidenceId must be null or contain 1 to 256 characters");
    }
    if (policyVersion < 0) {
      throw new IllegalArgumentException("policyVersion must not be negative");
    }
    effectivePermissions =
        Set.copyOf(Objects.requireNonNull(effectivePermissions, "effectivePermissions"));
  }
}
