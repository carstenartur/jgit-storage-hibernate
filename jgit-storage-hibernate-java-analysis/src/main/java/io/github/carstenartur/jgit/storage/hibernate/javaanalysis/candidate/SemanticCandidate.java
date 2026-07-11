/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis.candidate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable semantic mining candidate with lifecycle metadata. */
public record SemanticCandidate(
    CandidateId candidateId,
    CandidateLifecycle lifecycle,
    List<CandidateEvidence> evidence,
    Instant discoveredAt,
    Instant lastModifiedAt,
    String notes) {

  public SemanticCandidate {
    Objects.requireNonNull(candidateId, "candidateId");
    Objects.requireNonNull(lifecycle, "lifecycle");
    evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
    Objects.requireNonNull(discoveredAt, "discoveredAt");
    Objects.requireNonNull(lastModifiedAt, "lastModifiedAt");
    notes = notes == null ? "" : notes;
  }

  public static SemanticCandidate discover(CandidateId id, CandidateEvidence initialEvidence) {
    Instant now = Instant.now();
    return new SemanticCandidate(id, CandidateLifecycle.DISCOVERED, List.of(initialEvidence), now, now, "");
  }

  public SemanticCandidate transitionTo(CandidateLifecycle newLifecycle) {
    Objects.requireNonNull(newLifecycle, "newLifecycle");
    if (!lifecycle.canTransitionTo(newLifecycle)) {
      throw new IllegalStateException("Cannot transition from " + lifecycle + " to " + newLifecycle);
    }
    return new SemanticCandidate(
        candidateId,
        newLifecycle,
        evidence,
        discoveredAt,
        Instant.now(),
        notes);
  }

  public SemanticCandidate withEvidence(CandidateEvidence additional) {
    Objects.requireNonNull(additional, "additional");
    if (evidence.contains(additional)) {
      return this;
    }
    List<CandidateEvidence> updated = new ArrayList<>(evidence);
    updated.add(additional);
    return new SemanticCandidate(candidateId, lifecycle, updated, discoveredAt, Instant.now(), notes);
  }

  public SemanticCandidate withNotes(String newNotes) {
    return new SemanticCandidate(candidateId, lifecycle, evidence, discoveredAt, Instant.now(), newNotes);
  }
}
