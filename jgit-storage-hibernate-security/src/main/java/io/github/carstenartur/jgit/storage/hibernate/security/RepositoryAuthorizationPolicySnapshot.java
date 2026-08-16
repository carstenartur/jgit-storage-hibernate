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
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Complete versioned desired Git authorization state for one repository and source namespace. */
public record RepositoryAuthorizationPolicySnapshot(
    RepositoryName repositoryName,
    ManagedPolicySource source,
    long policyVersion,
    RepositoryPolicyOwnershipMode ownershipMode,
    Set<DesiredRepositoryGrant> grants,
    Set<DesiredRepositoryRefRule> refRules) {

  private static final int MAX_ENTRY_COUNT = 10_000;

  /** Creates a deeply immutable, completely validated desired snapshot. */
  public RepositoryAuthorizationPolicySnapshot {
    repositoryName = Objects.requireNonNull(repositoryName, "repositoryName");
    source = Objects.requireNonNull(source, "source");
    if (policyVersion < 1) {
      throw new IllegalArgumentException("policyVersion must be positive");
    }
    ownershipMode = Objects.requireNonNull(ownershipMode, "ownershipMode");
    grants = Set.copyOf(Objects.requireNonNull(grants, "grants"));
    refRules = Set.copyOf(Objects.requireNonNull(refRules, "refRules"));
    if (grants.size() + refRules.size() > MAX_ENTRY_COUNT) {
      throw new IllegalArgumentException(
          "managed policy must contain at most " + MAX_ENTRY_COUNT + " entries");
    }
    HashSet<String> entryKeys = new HashSet<>();
    grants.forEach(
        grant -> {
          if (!entryKeys.add(Objects.requireNonNull(grant, "grant").entryKey())) {
            throw new IllegalArgumentException(
                "duplicate managed policy entryKey: " + grant.entryKey());
          }
        });
    refRules.forEach(
        rule -> {
          if (!entryKeys.add(Objects.requireNonNull(rule, "refRule").entryKey())) {
            throw new IllegalArgumentException(
                "duplicate managed policy entryKey: " + rule.entryKey());
          }
        });
  }
}
