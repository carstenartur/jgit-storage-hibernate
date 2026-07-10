/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.candidate.CandidateEvidence;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.candidate.CandidateId;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.candidate.CandidateLifecycle;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.candidate.CandidateRegistry;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.candidate.CandidateValidator;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.candidate.SemanticCandidate;
import org.junit.jupiter.api.Test;

class SemanticCandidateLifecycleTest {

  @Test
  void candidateIdIsDeterministicAndUnique() {
    CandidateId first = CandidateId.of("repo", "c1", "refactoring", "demo", "payload");
    CandidateId second = CandidateId.of("repo", "c1", "refactoring", "demo", "payload");
    CandidateId different = CandidateId.of("repo", "c2", "refactoring", "demo", "payload");

    assertEquals(first.id(), second.id());
    assertNotEquals(first.id(), different.id());
  }

  @Test
  void repeatedDiscoveryAccumulatesEvidenceWithoutDuplicates() {
    CandidateRegistry registry = new CandidateRegistry();
    CandidateId id = CandidateId.of("repo", "c1", "refactoring", "demo", "payload");
    CandidateEvidence first = CandidateEvidence.of("first");
    CandidateEvidence second = CandidateEvidence.of("second");

    registry.register(id, first);
    registry.register(id, first);
    SemanticCandidate candidate = registry.register(id, second);

    assertEquals(1, registry.size());
    assertEquals(2, candidate.evidence().size());
  }

  @Test
  void lifecycleTransitionsAreValidated() {
    assertTrue(CandidateLifecycle.DISCOVERED.canTransitionTo(CandidateLifecycle.VALIDATED));
    assertTrue(CandidateLifecycle.READY.canTransitionTo(CandidateLifecycle.REJECTED));
    assertFalse(CandidateLifecycle.PROMOTED.canTransitionTo(CandidateLifecycle.VALIDATED));
    assertFalse(CandidateLifecycle.REJECTED.canTransitionTo(CandidateLifecycle.READY));
  }

  @Test
  void invalidTransitionThrowsIllegalStateException() {
    SemanticCandidate candidate = SemanticCandidate.discover(
        CandidateId.of("repo", "c1", "refactoring", "demo", "payload"),
        CandidateEvidence.of("first"));

    assertThrows(IllegalStateException.class, () -> candidate.transitionTo(CandidateLifecycle.READY));
  }

  @Test
  void candidatesAreTraceableToSourceCommit() {
    CandidateId id = CandidateId.of("repo", "commit-123", "refactoring", "demo", "payload");

    assertEquals("commit-123", id.sourceCommitId());
  }

  @Test
  void jsonRoundTripPreservesLifecycleAndEvidenceCount() {
    CandidateRegistry registry = new CandidateRegistry();
    CandidateId id = CandidateId.of("repo", "c1", "refactoring", "demo", "payload");
    registry.register(id, CandidateEvidence.of("first"));
    registry.register(id, CandidateEvidence.of("second"));
    registry.transition(id, CandidateLifecycle.VALIDATED);

    String json = registry.exportJson();
    CandidateRegistry imported = new CandidateRegistry();
    SemanticCandidate candidate = imported.importJson(json).getFirst();

    assertEquals(1, imported.size());
    assertEquals(CandidateLifecycle.VALIDATED, candidate.lifecycle());
    assertEquals(2, candidate.evidence().size());
  }

  @Test
  void candidateValidatorSpiCanAcceptOrReject() {
    SemanticCandidate candidate = SemanticCandidate.discover(
        CandidateId.of("repo", "c1", "refactoring", "demo", "payload"),
        CandidateEvidence.of("first"));
    CandidateValidator pass = ignored -> CandidateValidator.pass();
    CandidateValidator fail = ignored -> CandidateValidator.fail("missing evidence");

    assertTrue(pass.validate(candidate).passed());
    assertFalse(fail.validate(candidate).passed());
    assertEquals("missing evidence", fail.validate(candidate).message());
  }
}
