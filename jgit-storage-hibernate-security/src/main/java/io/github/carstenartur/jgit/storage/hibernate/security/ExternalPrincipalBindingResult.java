/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Stable principal binding returned after an already authenticated external login. */
public record ExternalPrincipalBindingResult(
    String principalId,
    ExternalPrincipalBindingOutcome outcome,
    String displayName,
    long securityVersion) {

  private static final int MAX_PRINCIPAL_ID_LENGTH = 128;
  private static final int MAX_DISPLAY_NAME_LENGTH = 256;

  /** Creates a validated binding result. */
  public ExternalPrincipalBindingResult {
    principalId = required("principalId", principalId, MAX_PRINCIPAL_ID_LENGTH);
    outcome = Objects.requireNonNull(outcome, "outcome");
    displayName = optional("displayName", displayName, MAX_DISPLAY_NAME_LENGTH);
    if (securityVersion < 1) {
      throw new IllegalArgumentException("securityVersion must be positive");
    }
  }

  /**
   * Build the explicit Git authorization context from host-verified groups and request evidence.
   */
  public GitAccessContext toGitAccessContext(
      Set<String> verifiedGroupIds,
      String authenticationMethod,
      SecurityAuthenticationTrace trace,
      Map<String, String> attributes) {
    SecurityAuthenticationTrace evidence = Objects.requireNonNull(trace, "trace");
    return new GitAccessContext(
        principalId,
        verifiedGroupIds,
        authenticationMethod,
        evidence.sessionId(),
        evidence.correlationId(),
        attributes);
  }

  /**
   * Build an unrestricted external credential envelope.
   *
   * <p>"Unrestricted" applies only to credential scopes. Repository grants and ref rules remain
   * authoritative and can still deny every operation.
   */
  public AuthenticatedGitAccess toAuthenticatedGitAccess(
      Set<String> verifiedGroupIds,
      String authenticationMethod,
      SecurityAuthenticationTrace trace,
      Map<String, String> attributes) {
    return AuthenticatedGitAccess.unrestricted(
        toGitAccessContext(verifiedGroupIds, authenticationMethod, trace, attributes),
        SecurityCredentialKind.EXTERNAL,
        principalId,
        securityVersion);
  }

  private static String required(String name, String value, int maximumLength) {
    if (value == null || value.isBlank() || value.length() > maximumLength) {
      throw new IllegalArgumentException(
          name + " must contain 1 to " + maximumLength + " characters");
    }
    return value;
  }

  private static String optional(String name, String value, int maximumLength) {
    return value == null ? null : required(name, value, maximumLength);
  }
}
