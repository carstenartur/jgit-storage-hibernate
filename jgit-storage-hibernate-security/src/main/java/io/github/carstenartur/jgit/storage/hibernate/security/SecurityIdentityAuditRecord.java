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

/**
 * Bounded, non-secret audit evidence for credential management or authentication.
 *
 * <p>This value never contains a password, token value, hash, token lookup prefix, login/display
 * name or arbitrary access-context attribute.
 */
public record SecurityIdentityAuditRecord(
    SecurityIdentityAuditOperation operation,
    SecurityAuditOutcome outcome,
    String actorPrincipalId,
    String subjectPrincipalId,
    String authenticationMethod,
    String sessionId,
    String correlationId,
    String remoteAddressHash,
    SecurityCredentialKind credentialKind,
    String credentialId,
    String reasonCode,
    String failureType) {

  private static final int MAX_PRINCIPAL_ID_LENGTH = 128;
  private static final int MAX_CONTEXT_ID_LENGTH = 256;
  private static final int MAX_CREDENTIAL_ID_LENGTH = 128;
  private static final int MAX_REASON_LENGTH = 128;
  private static final int MAX_FAILURE_TYPE_LENGTH = 256;

  /** Creates and validates immutable audit evidence. */
  public SecurityIdentityAuditRecord {
    operation = Objects.requireNonNull(operation, "operation");
    outcome = Objects.requireNonNull(outcome, "outcome");
    actorPrincipalId =
        optional("actorPrincipalId", actorPrincipalId, MAX_PRINCIPAL_ID_LENGTH);
    subjectPrincipalId =
        optional("subjectPrincipalId", subjectPrincipalId, MAX_PRINCIPAL_ID_LENGTH);
    authenticationMethod =
        required("authenticationMethod", authenticationMethod, MAX_CONTEXT_ID_LENGTH);
    sessionId = required("sessionId", sessionId, MAX_CONTEXT_ID_LENGTH);
    correlationId = required("correlationId", correlationId, MAX_CONTEXT_ID_LENGTH);
    if (remoteAddressHash != null && !remoteAddressHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException(
          "remoteAddressHash must be null or lowercase 64-character hexadecimal evidence");
    }
    credentialKind = Objects.requireNonNull(credentialKind, "credentialKind");
    credentialId = optional("credentialId", credentialId, MAX_CREDENTIAL_ID_LENGTH);
    reasonCode = required("reasonCode", reasonCode, MAX_REASON_LENGTH);
    failureType = optional("failureType", failureType, MAX_FAILURE_TYPE_LENGTH);
    if (outcome != SecurityAuditOutcome.FAILED && failureType != null) {
      throw new IllegalArgumentException("failureType is only valid for FAILED audit outcomes");
    }
  }

  /** Create evidence for an allowed or denied management operation. */
  public static SecurityIdentityAuditRecord management(
      SecurityManagementRequest request,
      SecurityCredentialKind credentialKind,
      String credentialId,
      SecurityAuditOutcome outcome,
      String reasonCode) {
    Objects.requireNonNull(request, "request");
    if (outcome == SecurityAuditOutcome.FAILED) {
      throw new IllegalArgumentException("use failedManagement for FAILED outcomes");
    }
    return new SecurityIdentityAuditRecord(
        auditOperation(request.operation()),
        outcome,
        request.actor().principalId(),
        request.subjectPrincipalId(),
        request.actor().authenticationMethod(),
        request.actor().sessionId(),
        request.actor().correlationId(),
        null,
        credentialKind,
        credentialId,
        reasonCode,
        null);
  }

  /** Create evidence for a failed management operation. */
  public static SecurityIdentityAuditRecord failedManagement(
      SecurityManagementRequest request,
      SecurityCredentialKind credentialKind,
      String credentialId,
      String reasonCode,
      Throwable failure) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(failure, "failure");
    return new SecurityIdentityAuditRecord(
        auditOperation(request.operation()),
        SecurityAuditOutcome.FAILED,
        request.actor().principalId(),
        request.subjectPrincipalId(),
        request.actor().authenticationMethod(),
        request.actor().sessionId(),
        request.actor().correlationId(),
        null,
        credentialKind,
        credentialId,
        reasonCode,
        boundedFailureType(failure));
  }

  /** Create evidence for a local authentication result. */
  public static SecurityIdentityAuditRecord authentication(
      SecurityIdentityAuditOperation operation,
      String subjectPrincipalId,
      SecurityAuthenticationTrace trace,
      SecurityCredentialKind credentialKind,
      String credentialId,
      SecurityAuditOutcome outcome,
      SecurityAuthenticationReason reason) {
    Objects.requireNonNull(trace, "trace");
    Objects.requireNonNull(reason, "reason");
    if (outcome == SecurityAuditOutcome.FAILED) {
      throw new IllegalArgumentException("use failedAuthentication for FAILED outcomes");
    }
    boolean expectedAuthenticated = outcome == SecurityAuditOutcome.ALLOWED;
    if (reason.authenticated() != expectedAuthenticated) {
      throw new IllegalArgumentException("authentication reason and audit outcome disagree");
    }
    return new SecurityIdentityAuditRecord(
        requireAuthenticationOperation(operation),
        outcome,
        null,
        subjectPrincipalId,
        authenticationMethod(credentialKind),
        trace.sessionId(),
        trace.correlationId(),
        trace.remoteAddressHash(),
        credentialKind,
        credentialId,
        reason.name(),
        null);
  }

  /** Create evidence for an unexpected local authentication failure. */
  public static SecurityIdentityAuditRecord failedAuthentication(
      SecurityIdentityAuditOperation operation,
      String subjectPrincipalId,
      SecurityAuthenticationTrace trace,
      SecurityCredentialKind credentialKind,
      String credentialId,
      Throwable failure) {
    Objects.requireNonNull(trace, "trace");
    Objects.requireNonNull(failure, "failure");
    return new SecurityIdentityAuditRecord(
        requireAuthenticationOperation(operation),
        SecurityAuditOutcome.FAILED,
        null,
        subjectPrincipalId,
        authenticationMethod(credentialKind),
        trace.sessionId(),
        trace.correlationId(),
        trace.remoteAddressHash(),
        credentialKind,
        credentialId,
        SecurityAuthenticationReason.AUTHENTICATION_FAILURE.name(),
        boundedFailureType(failure));
  }

  private static SecurityIdentityAuditOperation auditOperation(
      SecurityManagementOperation operation) {
    return switch (operation) {
      case SET_PASSWORD -> SecurityIdentityAuditOperation.PASSWORD_SET;
      case REMOVE_PASSWORD -> SecurityIdentityAuditOperation.PASSWORD_REMOVED;
      case UNLOCK_PASSWORD -> SecurityIdentityAuditOperation.PASSWORD_UNLOCKED;
      case ISSUE_ACCESS_TOKEN -> SecurityIdentityAuditOperation.ACCESS_TOKEN_ISSUED;
      case REVOKE_ACCESS_TOKEN -> SecurityIdentityAuditOperation.ACCESS_TOKEN_REVOKED;
    };
  }

  private static SecurityIdentityAuditOperation requireAuthenticationOperation(
      SecurityIdentityAuditOperation operation) {
    SecurityIdentityAuditOperation validated = Objects.requireNonNull(operation, "operation");
    if (validated != SecurityIdentityAuditOperation.PASSWORD_AUTHENTICATION
        && validated != SecurityIdentityAuditOperation.ACCESS_TOKEN_AUTHENTICATION) {
      throw new IllegalArgumentException(validated + " is not an authentication operation");
    }
    return validated;
  }

  private static String authenticationMethod(SecurityCredentialKind kind) {
    return switch (Objects.requireNonNull(kind, "credentialKind")) {
      case PASSWORD -> "password";
      case ACCESS_TOKEN -> "access_token";
      case EXTERNAL -> "external";
    };
  }

  private static String boundedFailureType(Throwable failure) {
    String name = failure.getClass().getName();
    return name.length() <= MAX_FAILURE_TYPE_LENGTH
        ? name
        : name.substring(0, MAX_FAILURE_TYPE_LENGTH);
  }

  private static String required(String name, String value, int maximumLength) {
    String validated = optional(name, value, maximumLength);
    if (validated == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return validated;
  }

  private static String optional(String name, String value, int maximumLength) {
    if (value == null) {
      return null;
    }
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
