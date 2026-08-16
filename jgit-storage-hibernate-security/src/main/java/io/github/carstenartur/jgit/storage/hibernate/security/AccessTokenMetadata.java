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
import java.util.Set;

/** Non-secret access-token lifecycle metadata returned by management and query operations. */
public record AccessTokenMetadata(
    String tokenId,
    String principalId,
    String tokenPrefix,
    Set<GitRepositoryPermission> permissionScopes,
    Instant issuedAt,
    Instant expiresAt,
    Instant lastUsedAt,
    Instant revokedAt,
    String issuedBy,
    long securityVersion) {

  /** Creates and validates deeply immutable metadata. */
  public AccessTokenMetadata {
    tokenId = required("tokenId", tokenId, 128);
    principalId = required("principalId", principalId, 128);
    tokenPrefix = required("tokenPrefix", tokenPrefix, 64);
    permissionScopes =
        Set.copyOf(Objects.requireNonNull(permissionScopes, "permissionScopes"));
    if (permissionScopes.isEmpty()) {
      throw new IllegalArgumentException("permissionScopes must not be empty");
    }
    issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
    if (expiresAt != null && !expiresAt.isAfter(issuedAt)) {
      throw new IllegalArgumentException("expiresAt must be after issuedAt");
    }
    if (lastUsedAt != null && lastUsedAt.isBefore(issuedAt)) {
      throw new IllegalArgumentException("lastUsedAt must not be before issuedAt");
    }
    if (revokedAt != null && revokedAt.isBefore(issuedAt)) {
      throw new IllegalArgumentException("revokedAt must not be before issuedAt");
    }
    issuedBy = required("issuedBy", issuedBy, 128);
    if (securityVersion < 1) {
      throw new IllegalArgumentException("securityVersion must be positive");
    }
  }

  /** Return whether the token is revoked. */
  public boolean revoked() {
    return revokedAt != null;
  }

  /** Return whether the token is expired at the supplied instant. */
  public boolean expiredAt(Instant instant) {
    Objects.requireNonNull(instant, "instant");
    return expiresAt != null && !instant.isBefore(expiresAt);
  }

  private static String required(String name, String value, int maximumLength) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (value.length() > maximumLength) {
      throw new IllegalArgumentException(
          name + " must contain at most " + maximumLength + " characters");
    }
    return value;
  }
}
