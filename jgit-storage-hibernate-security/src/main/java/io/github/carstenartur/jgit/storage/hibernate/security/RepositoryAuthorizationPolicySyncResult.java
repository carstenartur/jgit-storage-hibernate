/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import java.util.Objects;

/** Bounded evidence returned after managed repository authorization reconciliation. */
public record RepositoryAuthorizationPolicySyncResult(
    RepositoryAuthorizationPolicySyncStatus status,
    String reasonCode,
    RepositoryName repositoryName,
    ManagedPolicySource source,
    long previousPolicyVersion,
    long activePolicyVersion,
    long policyGeneration,
    String contentDigest,
    int createdEntries,
    int updatedEntries,
    int deletedEntries,
    int outsideNamespaceGrantCount,
    int outsideNamespaceRefRuleCount) {

  private static final int MAX_REASON_CODE_LENGTH = 128;

  /** Creates a validated immutable synchronization result. */
  public RepositoryAuthorizationPolicySyncResult {
    status = Objects.requireNonNull(status, "status");
    reasonCode = required("reasonCode", reasonCode, MAX_REASON_CODE_LENGTH);
    repositoryName = Objects.requireNonNull(repositoryName, "repositoryName");
    source = Objects.requireNonNull(source, "source");
    if (previousPolicyVersion < 0 || activePolicyVersion < 0 || policyGeneration < 0) {
      throw new IllegalArgumentException("policy versions and generation must not be negative");
    }
    if (contentDigest == null || !contentDigest.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(
          "contentDigest must be a lowercase 64-character SHA-256 value");
    }
    requireNonNegative("createdEntries", createdEntries);
    requireNonNegative("updatedEntries", updatedEntries);
    requireNonNegative("deletedEntries", deletedEntries);
    requireNonNegative("outsideNamespaceGrantCount", outsideNamespaceGrantCount);
    requireNonNegative("outsideNamespaceRefRuleCount", outsideNamespaceRefRuleCount);
  }

  private static String required(String name, String value, int maximumLength) {
    if (value == null || value.isBlank() || value.length() > maximumLength) {
      throw new IllegalArgumentException(
          name + " must contain 1 to " + maximumLength + " characters");
    }
    return value;
  }

  private static void requireNonNegative(String name, int value) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
  }
}
