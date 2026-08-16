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

/**
 * Authenticated Git context plus the maximum permissions carried by its credential.
 *
 * <p>Repository grants and protected-ref rules remain authoritative. Credential scopes can only
 * reduce those permissions; they never add a repository permission.
 */
public record AuthenticatedGitAccess(
    GitAccessContext context,
    SecurityCredentialKind credentialKind,
    String credentialId,
    long credentialVersion,
    Set<GitRepositoryPermission> credentialScopes) {

  private static final int MAX_CREDENTIAL_ID_LENGTH = 128;

  /** Creates a deeply immutable credential-bound access context. */
  public AuthenticatedGitAccess {
    context = Objects.requireNonNull(context, "context");
    credentialKind = Objects.requireNonNull(credentialKind, "credentialKind");
    credentialId = required("credentialId", credentialId, MAX_CREDENTIAL_ID_LENGTH);
    if (credentialVersion < 0) {
      throw new IllegalArgumentException("credentialVersion must not be negative");
    }
    credentialScopes =
        Set.copyOf(Objects.requireNonNull(credentialScopes, "credentialScopes"));
  }

  /**
   * Create an external or password-authenticated context without a credential-level restriction.
   *
   * <p>The repository ACL still decides the effective permission set.
   */
  public static AuthenticatedGitAccess unrestricted(
      GitAccessContext context,
      SecurityCredentialKind credentialKind,
      String credentialId,
      long credentialVersion) {
    if (credentialKind == SecurityCredentialKind.ACCESS_TOKEN) {
      throw new IllegalArgumentException("access tokens require explicit credential scopes");
    }
    return new AuthenticatedGitAccess(
        context,
        credentialKind,
        credentialId,
        credentialVersion,
        Set.of(GitRepositoryPermission.values()));
  }

  /**
   * Return whether this credential permits the requested permission to reach the repository ACL.
   *
   * <p>An {@link GitRepositoryPermission#ADMINISTER} scope carries every repository permission, just
   * as an authoritative repository grant with that permission does.
   */
  public boolean carries(GitRepositoryPermission permission) {
    GitRepositoryPermission requested = Objects.requireNonNull(permission, "permission");
    return credentialScopes.contains(requested)
        || credentialScopes.contains(GitRepositoryPermission.ADMINISTER);
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
