/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessDeniedException;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessOperation;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessRequest;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;

class SecurityAccessAuditRecordTest {

  private static final RepositoryName REPOSITORY = new RepositoryName("workflows");
  private static final ObjectId OLD_ID =
      ObjectId.fromString("1111111111111111111111111111111111111111");
  private static final ObjectId NEW_ID =
      ObjectId.fromString("2222222222222222222222222222222222222222");
  private static final GitAccessContext CONTEXT =
      new GitAccessContext(
          "alice",
          Set.of("contributors"),
          "oidc",
          "session-1",
          "correlation-1",
          Map.of("unpersisted", "context attribute"));

  @Test
  void decisionFactoryCopiesOnlyBoundedNonSecretEvidence() {
    RepositoryAccessRequest request =
        RepositoryAccessRequest.ref(
            REPOSITORY,
            RepositoryAccessOperation.UPDATE_REF,
            "refs/heads/topic",
            OLD_ID,
            NEW_ID);
    AuthorizationDecision decision =
        new AuthorizationDecision(
            true,
            AuthorizationReason.GRANT_ALLOWED,
            "grant-1",
            7,
            Set.of(GitRepositoryPermission.UPDATE_REF));

    SecurityAccessAuditRecord record =
        SecurityAccessAuditRecord.decision(CONTEXT, request, decision);

    assertEquals("alice", record.principalId());
    assertEquals("oidc", record.authenticationMethod());
    assertEquals("session-1", record.sessionId());
    assertEquals("correlation-1", record.correlationId());
    assertEquals(REPOSITORY, record.repositoryName());
    assertEquals(RepositoryAccessOperation.UPDATE_REF, record.operation());
    assertEquals("refs/heads/topic", record.refName());
    assertEquals(OLD_ID.name(), record.oldObjectId());
    assertEquals(NEW_ID.name(), record.newObjectId());
    assertEquals(SecurityAuditOutcome.ALLOWED, record.outcome());
    assertEquals("GRANT_ALLOWED", record.reasonCode());
    assertEquals("grant-1", record.evidenceId());
    assertEquals(7, record.policyVersion());
    assertNull(record.failureType());
  }

  @Test
  void denialAndFailureFactoriesPreservePrimaryEvidence() {
    RepositoryAccessRequest request =
        RepositoryAccessRequest.repository(REPOSITORY, RepositoryAccessOperation.READ);
    RepositoryAccessDeniedException denied =
        new RepositoryAccessDeniedException(request, "NO_MATCHING_GRANT", null, 3);

    SecurityAccessAuditRecord deniedRecord =
        SecurityAccessAuditRecord.denied(CONTEXT, denied);
    SecurityAccessAuditRecord failedRecord =
        SecurityAccessAuditRecord.failed(
            CONTEXT, request, new IllegalStateException("database unavailable"));

    assertEquals(SecurityAuditOutcome.DENIED, deniedRecord.outcome());
    assertEquals("NO_MATCHING_GRANT", deniedRecord.reasonCode());
    assertEquals(3, deniedRecord.policyVersion());
    assertNull(deniedRecord.failureType());
    assertEquals(SecurityAuditOutcome.FAILED, failedRecord.outcome());
    assertEquals("AUTHORIZATION_EVALUATION_FAILED", failedRecord.reasonCode());
    assertEquals(IllegalStateException.class.getName(), failedRecord.failureType());
  }

  @Test
  void constructorRejectsInvalidScopeIdentifiersAndFailureShape() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            record(
                " ",
                RepositoryAccessOperation.READ,
                null,
                null,
                null,
                SecurityAuditOutcome.ALLOWED,
                null,
                0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            record(
                "alice",
                RepositoryAccessOperation.READ,
                "refs/heads/main",
                null,
                null,
                SecurityAuditOutcome.ALLOWED,
                null,
                0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            record(
                "alice",
                RepositoryAccessOperation.READ,
                null,
                OLD_ID.name(),
                null,
                SecurityAuditOutcome.ALLOWED,
                null,
                0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            record(
                "alice",
                RepositoryAccessOperation.UPDATE_REF,
                "refs/heads/main",
                "not-an-object-id",
                NEW_ID.name(),
                SecurityAuditOutcome.ALLOWED,
                null,
                0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            record(
                "alice",
                RepositoryAccessOperation.UPDATE_REF,
                "refs/heads/main",
                OLD_ID.name(),
                NEW_ID.name(),
                SecurityAuditOutcome.FAILED,
                null,
                0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            record(
                "alice",
                RepositoryAccessOperation.UPDATE_REF,
                "refs/heads/main",
                OLD_ID.name(),
                NEW_ID.name(),
                SecurityAuditOutcome.ALLOWED,
                "failure.Type",
                0));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            record(
                "alice",
                RepositoryAccessOperation.READ,
                null,
                null,
                null,
                SecurityAuditOutcome.ALLOWED,
                null,
                -1));
  }

  @Test
  void persistedEventValidatesIdentityTimeAndRecord() {
    SecurityAccessAuditRecord record =
        record(
            "alice",
            RepositoryAccessOperation.READ,
            null,
            null,
            null,
            SecurityAuditOutcome.ALLOWED,
            null,
            1);
    Instant occurredAt = Instant.parse("2026-08-13T00:00:00Z");

    SecurityAccessAuditEvent event =
        new SecurityAccessAuditEvent("audit-1", occurredAt, record);

    assertEquals("audit-1", event.auditId());
    assertEquals(occurredAt, event.occurredAt());
    assertEquals(record, event.record());
    assertThrows(
        IllegalArgumentException.class,
        () -> new SecurityAccessAuditEvent(" ", occurredAt, record));
    assertThrows(
        NullPointerException.class,
        () -> new SecurityAccessAuditEvent("audit-1", null, record));
    assertThrows(
        NullPointerException.class,
        () -> new SecurityAccessAuditEvent("audit-1", occurredAt, null));
  }

  private static SecurityAccessAuditRecord record(
      String principalId,
      RepositoryAccessOperation operation,
      String refName,
      String oldObjectId,
      String newObjectId,
      SecurityAuditOutcome outcome,
      String failureType,
      long policyVersion) {
    return new SecurityAccessAuditRecord(
        principalId,
        "oidc",
        "session-1",
        "correlation-1",
        REPOSITORY,
        operation,
        refName,
        oldObjectId,
        newObjectId,
        outcome,
        outcome == SecurityAuditOutcome.FAILED
            ? "AUTHORIZATION_EVALUATION_FAILED"
            : "GRANT_ALLOWED",
        null,
        policyVersion,
        failureType);
  }
}
