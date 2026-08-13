/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessDeniedException;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessOperation;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessRequest;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import java.util.Objects;
import org.eclipse.jgit.lib.ObjectId;

/**
 * Bounded, immutable and non-secret evidence for one repository authorization decision.
 *
 * <p>Group memberships, arbitrary access-context attributes, Git content and credential material
 * are deliberately excluded from this record.
 */
public record SecurityAccessAuditRecord(
    String principalId,
    String authenticationMethod,
    String sessionId,
    String correlationId,
    RepositoryName repositoryName,
    RepositoryAccessOperation operation,
    String refName,
    String oldObjectId,
    String newObjectId,
    SecurityAuditOutcome outcome,
    String reasonCode,
    String evidenceId,
    long policyVersion,
    String failureType) {

  private static final int MAX_SUBJECT_ID_LENGTH = 128;
  private static final int MAX_CONTEXT_ID_LENGTH = 256;
  private static final int MAX_REF_NAME_LENGTH = 1024;
  private static final int MAX_REASON_CODE_LENGTH = 128;
  private static final int MAX_EVIDENCE_ID_LENGTH = 256;
  private static final int MAX_FAILURE_TYPE_LENGTH = 256;

  /** Creates a validated audit record. */
  public SecurityAccessAuditRecord {
    principalId = required("principalId", principalId, MAX_SUBJECT_ID_LENGTH);
    authenticationMethod =
        required("authenticationMethod", authenticationMethod, MAX_CONTEXT_ID_LENGTH);
    sessionId = required("sessionId", sessionId, MAX_CONTEXT_ID_LENGTH);
    correlationId = required("correlationId", correlationId, MAX_CONTEXT_ID_LENGTH);
    Objects.requireNonNull(repositoryName, "repositoryName");
    Objects.requireNonNull(operation, "operation");
    refName = optional("refName", refName, MAX_REF_NAME_LENGTH);
    oldObjectId = objectId("oldObjectId", oldObjectId);
    newObjectId = objectId("newObjectId", newObjectId);
    Objects.requireNonNull(outcome, "outcome");
    reasonCode = required("reasonCode", reasonCode, MAX_REASON_CODE_LENGTH);
    evidenceId = optional("evidenceId", evidenceId, MAX_EVIDENCE_ID_LENGTH);
    if (policyVersion < 0) {
      throw new IllegalArgumentException("policyVersion must not be negative");
    }
    failureType = optional("failureType", failureType, MAX_FAILURE_TYPE_LENGTH);
    if (operation.refScoped() != (refName != null)) {
      throw new IllegalArgumentException("refName scope does not match operation " + operation);
    }
    if (!operation.refScoped() && (oldObjectId != null || newObjectId != null)) {
      throw new IllegalArgumentException(
          "repository-level audit records must not contain object identifiers");
    }
    if ((outcome == SecurityAuditOutcome.FAILED) != (failureType != null)) {
      throw new IllegalArgumentException(
          "failureType must be present exactly when outcome is FAILED");
    }
  }

  /** Create a record from one deterministic evaluator decision. */
  public static SecurityAccessAuditRecord decision(
      GitAccessContext context,
      RepositoryAccessRequest request,
      AuthorizationDecision decision) {
    Objects.requireNonNull(decision, "decision");
    return create(
        context,
        request,
        decision.allowed() ? SecurityAuditOutcome.ALLOWED : SecurityAuditOutcome.DENIED,
        decision.reason().name(),
        decision.evidenceId(),
        decision.policyVersion(),
        null);
  }

  /** Create a record from a Core access denial. */
  public static SecurityAccessAuditRecord denied(
      GitAccessContext context, RepositoryAccessDeniedException denied) {
    Objects.requireNonNull(denied, "denied");
    return create(
        context,
        denied.request(),
        SecurityAuditOutcome.DENIED,
        denied.reasonCode(),
        denied.evidenceId(),
        denied.policyVersion(),
        null);
  }

  /** Create a record for an authorization evaluation failure. */
  public static SecurityAccessAuditRecord failed(
      GitAccessContext context,
      RepositoryAccessRequest request,
      RuntimeException failure) {
    Objects.requireNonNull(failure, "failure");
    return create(
        context,
        request,
        SecurityAuditOutcome.FAILED,
        "AUTHORIZATION_EVALUATION_FAILED",
        null,
        0,
        boundedFailureType(failure.getClass().getName()));
  }

  private static SecurityAccessAuditRecord create(
      GitAccessContext context,
      RepositoryAccessRequest request,
      SecurityAuditOutcome outcome,
      String reasonCode,
      String evidenceId,
      long policyVersion,
      String failureType) {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(request, "request");
    return new SecurityAccessAuditRecord(
        context.principalId(),
        context.authenticationMethod(),
        context.sessionId(),
        context.correlationId(),
        request.repositoryName(),
        request.operation(),
        request.refName(),
        name(request.oldObjectId()),
        name(request.newObjectId()),
        outcome,
        reasonCode,
        evidenceId,
        policyVersion,
        failureType);
  }

  private static String name(ObjectId objectId) {
    return objectId == null ? null : objectId.name();
  }

  private static String objectId(String name, String value) {
    String validated = optional(name, value, 64);
    if (validated == null) {
      return null;
    }
    boolean hexadecimal =
        validated.chars()
            .allMatch(
                character ->
                    character >= '0' && character <= '9'
                        || character >= 'a' && character <= 'f');
    if (validated.length() < 40 || !hexadecimal) {
      throw new IllegalArgumentException(name + " must be a lowercase hexadecimal object ID");
    }
    return validated;
  }

  private static String boundedFailureType(String value) {
    return value.length() <= MAX_FAILURE_TYPE_LENGTH
        ? value
        : value.substring(0, MAX_FAILURE_TYPE_LENGTH);
  }

  private static String required(String name, String value, int maximumLength) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (value.length() > maximumLength) {
      throw new IllegalArgumentException(
          name + " must contain at most " + maximumLength + " characters");
    }
    return value;
  }

  private static String optional(String name, String value, int maximumLength) {
    if (value == null) {
      return null;
    }
    return required(name, value, maximumLength);
  }
}
