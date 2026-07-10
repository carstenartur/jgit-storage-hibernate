/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture;

/** Structured change between two architecture DSL snapshots. */
public enum ArchitectureChangeKind {
  ELEMENT_ADDED,
  ELEMENT_REMOVED,
  ELEMENT_CHANGED,
  RELATION_ADDED,
  RELATION_REMOVED,
  RELATION_CHANGED,
  RULE_ADDED,
  RULE_REMOVED,
  RULE_CHANGED,
  EVIDENCE_ADDED,
  EVIDENCE_REMOVED,
  EVIDENCE_CHANGED
}
