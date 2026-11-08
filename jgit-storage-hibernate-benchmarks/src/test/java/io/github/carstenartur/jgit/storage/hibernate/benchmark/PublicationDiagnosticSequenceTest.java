/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublicationDiagnosticSequenceTest {
  @Test
  void doesNotInventAnAttemptWhenInterruptedBeforeStarting() {
    List<Integer> attempts = new ArrayList<>();
    List<PublicationDiagnosticSequence.Outcome> retained = new ArrayList<>();
    try {
      Thread.currentThread().interrupt();
      assertThrows(InterruptedException.class, () -> PublicationDiagnosticSequence.run(
          (ordinal, policy) -> attempts.add(ordinal), retained::addAll));
      assertTrue(Thread.currentThread().isInterrupted());
      assertTrue(attempts.isEmpty());
      assertTrue(retained.isEmpty(), "An unstarted fork must not acquire an outcome");
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void interruptionBetweenForksPreservesOnlyAttemptedOutcomes() {
    List<Integer> attempts = new ArrayList<>();
    List<PublicationDiagnosticSequence.Outcome> retained = new ArrayList<>();
    try {
      assertThrows(InterruptedException.class, () -> PublicationDiagnosticSequence.run(
          (ordinal, policy) -> attempts.add(ordinal),
          outcomes -> {
            retained.clear();
            retained.addAll(outcomes);
            Thread.currentThread().interrupt();
          }));
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(List.of(1), attempts);
      assertEquals(List.of("completed"),
          retained.stream().map(PublicationDiagnosticSequence.Outcome::status).toList());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void retainsAFailedForkAndAttemptsEveryPredeterminedPolicyOnce() throws Exception {
    List<String> attempts = new ArrayList<>();
    List<Integer> checkpoints = new ArrayList<>();
    var outcomes =
        PublicationDiagnosticSequence.run(
            (ordinal, policy) -> {
              attempts.add(policy);
              if (ordinal == 2) {
                throw new PublicationDiagnosticSequence.ForkFailure(
                    new IOException("injected child failure"));
              }
            },
            retained -> {
              checkpoints.add(retained.size());
              assertThrows(UnsupportedOperationException.class, () -> retained.clear());
            });
    assertEquals(List.of("size", "contention-aware", "contention-aware", "size"), attempts);
    assertEquals(List.of(1, 2, 3, 4), checkpoints);
    assertEquals(List.of("completed", "failed", "completed", "completed"),
        outcomes.stream().map(PublicationDiagnosticSequence.Outcome::status).toList());
    assertEquals("java.io.IOException", outcomes.get(1).failureType());
  }

  @Test
  void doesNotExtendAnAllSuccessfulSequence() throws Exception {
    List<Integer> attempts = new ArrayList<>();
    var outcomes = PublicationDiagnosticSequence.run((ordinal, policy) -> attempts.add(ordinal),
        retained -> {});
    assertEquals(List.of(1, 2, 3, 4), attempts);
    assertEquals(4, outcomes.size());
    assertTrue(outcomes.stream().allMatch(outcome -> outcome.status().equals("completed")));
  }

  @Test
  void retainsInterruptionAndStopsWithoutPretendingTheSequenceCompleted() {
    List<Integer> attempts = new ArrayList<>();
    List<PublicationDiagnosticSequence.Outcome> retained = new ArrayList<>();
    try {
      assertThrows(InterruptedException.class, () -> PublicationDiagnosticSequence.run(
          (ordinal, policy) -> {
            attempts.add(ordinal);
            if (ordinal == 2) throw new InterruptedException("stop");
          },
          outcomes -> {
            retained.clear();
            retained.addAll(outcomes);
          }));
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(List.of(1, 2), attempts);
      assertEquals(List.of("completed", "interrupted"),
          retained.stream().map(PublicationDiagnosticSequence.Outcome::status).toList());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void stopsIfEvidenceCannotBeCheckpointed() {
    List<Integer> attempts = new ArrayList<>();
    assertThrows(IOException.class, () -> PublicationDiagnosticSequence.run(
        (ordinal, policy) -> attempts.add(ordinal),
        retained -> { throw new IOException("checkpoint unavailable"); }));
    assertEquals(List.of(1), attempts);
  }

  @Test
  void doesNotTreatAnInfrastructureFailureAsAnOrdinaryFailedChild() {
    List<Integer> attempts = new ArrayList<>();
    assertThrows(IOException.class, () -> PublicationDiagnosticSequence.run(
        (ordinal, policy) -> {
          attempts.add(ordinal);
          throw new IOException("manifest unavailable before launch");
        }, retained -> {}));
    assertEquals(List.of(1), attempts);
  }
}
