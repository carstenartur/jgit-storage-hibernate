/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis.candidate;

/** Lifecycle states of a semantic mining candidate. */
public enum CandidateLifecycle {
  DISCOVERED,
  VALIDATED,
  TESTED,
  READY,
  PROMOTED,
  REJECTED;

  /** Returns true if this state allows a transition to the given next state. */
  public boolean canTransitionTo(CandidateLifecycle next) {
    return switch (this) {
      case DISCOVERED -> next == VALIDATED || next == REJECTED;
      case VALIDATED -> next == TESTED || next == REJECTED;
      case TESTED -> next == READY || next == REJECTED;
      case READY -> next == PROMOTED || next == REJECTED;
      case PROMOTED, REJECTED -> false;
    };
  }
}
