/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import java.util.ArrayList;
import java.util.List;

/** A finite diagnostic sequence; failures are observations and never trigger retries. */
final class PublicationDiagnosticSequence {
  private PublicationDiagnosticSequence() {}

  static final List<String> POLICIES =
      List.of("size", "contention-aware", "contention-aware", "size");

  static List<Outcome> run(ForkAction action, Checkpoint checkpoint) throws Exception {
    return run(POLICIES, action, checkpoint);
  }

  static List<Outcome> run(List<String> coordinates, ForkAction action, Checkpoint checkpoint) throws Exception {
    List<String> sequence = List.copyOf(coordinates);
    List<Outcome> outcomes = new ArrayList<>();
    for (int index = 0; index < sequence.size(); index++) {
      int ordinal = index + 1;
      String policy = sequence.get(index);
      // Only started actions have a trial manifest. Do not invent an outcome for
      // a fork skipped because cancellation arrived before its action began.
      if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
      try {
        action.run(ordinal, policy);
        outcomes.add(new Outcome(ordinal, policy, "completed", null));
      } catch (InterruptedException interrupted) {
        outcomes.add(new Outcome(ordinal, policy, "interrupted", interrupted.getClass().getName()));
        try {
          checkpoint.retain(List.copyOf(outcomes));
        } finally {
          Thread.currentThread().interrupt();
        }
        throw interrupted;
      } catch (ForkFailure failure) {
        outcomes.add(new Outcome(ordinal, policy, "failed", failure.getCause().getClass().getName()));
      }
      checkpoint.retain(List.copyOf(outcomes));
    }
    return List.copyOf(outcomes);
  }

  @FunctionalInterface
  interface ForkAction {
    void run(int ordinal, String policy) throws Exception;
  }

  @FunctionalInterface
  interface Checkpoint {
    void retain(List<Outcome> outcomes) throws Exception;
  }

  static final class ForkFailure extends Exception {
    ForkFailure(Exception cause) {
      super(cause);
    }
  }

  record Outcome(int ordinal, String policy, String status, String failureType) {}
}
