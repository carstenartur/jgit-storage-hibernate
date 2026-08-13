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

/** Explicit actor, subject and target evidence for one credential-management operation. */
public record SecurityManagementRequest(
    GitAccessContext actor,
    SecurityManagementOperation operation,
    String subjectPrincipalId,
    String credentialId) {

  private static final int MAX_PRINCIPAL_ID_LENGTH = 128;
  private static final int MAX_CREDENTIAL_ID_LENGTH = 128;

  /** Creates and validates a management request. */
  public SecurityManagementRequest {
    actor = Objects.requireNonNull(actor, "actor");
    operation = Objects.requireNonNull(operation, "operation");
    subjectPrincipalId =
        required("subjectPrincipalId", subjectPrincipalId, MAX_PRINCIPAL_ID_LENGTH);
    if (operation == SecurityManagementOperation.REVOKE_ACCESS_TOKEN) {
      credentialId = required("credentialId", credentialId, MAX_CREDENTIAL_ID_LENGTH);
    } else if (credentialId != null) {
      throw new IllegalArgumentException(
          "credentialId is only accepted for REVOKE_ACCESS_TOKEN requests");
    }
  }

  /** Create a password-management request. */
  public static SecurityManagementRequest password(
      GitAccessContext actor,
      SecurityManagementOperation operation,
      String subjectPrincipalId) {
    if (operation != SecurityManagementOperation.SET_PASSWORD
        && operation != SecurityManagementOperation.REMOVE_PASSWORD
        && operation != SecurityManagementOperation.UNLOCK_PASSWORD) {
      throw new IllegalArgumentException(operation + " is not a password operation");
    }
    return new SecurityManagementRequest(actor, operation, subjectPrincipalId, null);
  }

  /** Create a token-issuance request. */
  public static SecurityManagementRequest issueToken(
      GitAccessContext actor, String subjectPrincipalId) {
    return new SecurityManagementRequest(
        actor, SecurityManagementOperation.ISSUE_ACCESS_TOKEN, subjectPrincipalId, null);
  }

  /** Create a token-revocation request. */
  public static SecurityManagementRequest revokeToken(
      GitAccessContext actor, String subjectPrincipalId, String tokenId) {
    return new SecurityManagementRequest(
        actor, SecurityManagementOperation.REVOKE_ACCESS_TOKEN, subjectPrincipalId, tokenId);
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
