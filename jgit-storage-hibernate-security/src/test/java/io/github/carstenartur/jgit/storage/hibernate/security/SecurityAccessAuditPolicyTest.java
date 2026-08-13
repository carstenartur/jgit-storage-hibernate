/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessDeniedException;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessOperation;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessRequest;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecurityAccessAuditPolicyTest {

  private static final RepositoryName REPOSITORY = new RepositoryName("workflows");
  private static final RepositoryAccessRequest READ =
      RepositoryAccessRequest.repository(REPOSITORY, RepositoryAccessOperation.READ);
  private static final GitAccessContext ALICE =
      new GitAccessContext(
          "alice", Set.of(), "oidc", "session-1", "correlation-1", Map.of());

  @Test
  void recordsAllowedAndDeniedDecisionsWithEvaluatorEvidence() {
    List<SecurityAccessAuditRecord> records = new ArrayList<>();
    SecurityRepositoryAccessPolicy allowPolicy =
        new SecurityRepositoryAccessPolicy(
            evaluator(SecurityEffect.ALLOW, 4), records::add);
    SecurityRepositoryAccessPolicy denyPolicy =
        new SecurityRepositoryAccessPolicy(
            evaluator(SecurityEffect.DENY, 9), records::add);

    assertDoesNotThrow(() -> allowPolicy.require(ALICE, READ));
    RepositoryAccessDeniedException denied =
        assertThrows(
            RepositoryAccessDeniedException.class,
            () -> denyPolicy.require(ALICE, READ));

    assertEquals(2, records.size());
    assertEquals(SecurityAuditOutcome.ALLOWED, records.get(0).outcome());
    assertEquals("GRANT_ALLOWED", records.get(0).reasonCode());
    assertEquals("grant-allow", records.get(0).evidenceId());
    assertEquals(4, records.get(0).policyVersion());
    assertEquals(SecurityAuditOutcome.DENIED, records.get(1).outcome());
    assertEquals("EXPLICIT_GRANT_DENY", records.get(1).reasonCode());
    assertEquals("grant-deny", records.get(1).evidenceId());
    assertEquals(9, records.get(1).policyVersion());
    assertEquals("EXPLICIT_GRANT_DENY", denied.reasonCode());
  }

  @Test
  void allowedDecisionFailsClosedWhenItsAuditCannotBeRecorded() {
    SecurityRepositoryAccessPolicy policy =
        new SecurityRepositoryAccessPolicy(
            evaluator(SecurityEffect.ALLOW, 4),
            record -> {
              throw new IllegalStateException("audit unavailable");
            });

    SecurityAuditPersistenceException failure =
        assertThrows(
            SecurityAuditPersistenceException.class,
            () -> policy.require(ALICE, READ));

    assertEquals(SecurityAuditOutcome.ALLOWED, failure.record().outcome());
    assertEquals("GRANT_ALLOWED", failure.record().reasonCode());
    assertInstanceOf(IllegalStateException.class, failure.getCause());
  }

  @Test
  void deniedDecisionRemainsDeniedWhenItsAuditCannotBeRecorded() {
    SecurityRepositoryAccessPolicy policy =
        new SecurityRepositoryAccessPolicy(
            evaluator(SecurityEffect.DENY, 9),
            record -> {
              throw new IllegalStateException("audit unavailable");
            });

    RepositoryAccessDeniedException denied =
        assertThrows(
            RepositoryAccessDeniedException.class,
            () -> policy.require(ALICE, READ));

    assertEquals("EXPLICIT_GRANT_DENY", denied.reasonCode());
    assertEquals(1, denied.getSuppressed().length);
    SecurityAuditPersistenceException auditFailure =
        assertInstanceOf(
            SecurityAuditPersistenceException.class,
            denied.getSuppressed()[0]);
    assertEquals(SecurityAuditOutcome.DENIED, auditFailure.record().outcome());
  }

  @Test
  void evaluatorFailureRemainsPrimaryAndProducesFailedAuditEvidence() {
    List<SecurityAccessAuditRecord> records = new ArrayList<>();
    IllegalStateException evaluatorFailure =
        new IllegalStateException("policy snapshot unavailable");
    SecurityRepositoryAccessPolicy policy =
        new SecurityRepositoryAccessPolicy(
            ignored -> {
              throw evaluatorFailure;
            },
            records::add);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> policy.require(ALICE, READ));

    assertSame(evaluatorFailure, thrown);
    assertEquals(1, records.size());
    assertEquals(SecurityAuditOutcome.FAILED, records.get(0).outcome());
    assertEquals(
        IllegalStateException.class.getName(), records.get(0).failureType());
  }

  @Test
  void simultaneousPolicyAndAuditFailureKeepsPolicyFailurePrimary() {
    IllegalStateException evaluatorFailure =
        new IllegalStateException("policy snapshot unavailable");
    SecurityRepositoryAccessPolicy policy =
        new SecurityRepositoryAccessPolicy(
            ignored -> {
              throw evaluatorFailure;
            },
            record -> {
              throw new IllegalArgumentException("audit unavailable");
            });

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> policy.require(ALICE, READ));

    assertSame(evaluatorFailure, thrown);
    assertEquals(1, thrown.getSuppressed().length);
    SecurityAuditPersistenceException auditFailure =
        assertInstanceOf(
            SecurityAuditPersistenceException.class,
            thrown.getSuppressed()[0]);
    assertEquals(SecurityAuditOutcome.FAILED, auditFailure.record().outcome());
  }

  private static SecurityAuthorizationEvaluator evaluator(
      SecurityEffect effect, long policyVersion) {
    String suffix = effect.name().toLowerCase();
    RepositoryGrant grant =
        new RepositoryGrant(
            "grant-" + suffix,
            SecuritySubject.principal("alice"),
            REPOSITORY,
            GitRepositoryPermission.READ,
            effect,
            policyVersion);
    return new SecurityAuthorizationEvaluator(List.of(grant), List.of());
  }
}
