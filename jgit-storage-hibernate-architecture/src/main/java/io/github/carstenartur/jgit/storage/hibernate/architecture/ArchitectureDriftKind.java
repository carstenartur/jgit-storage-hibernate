/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.architecture;

/** Difference between versioned architecture intent and observed code graph. */
public enum ArchitectureDriftKind {
  FORBIDDEN_RELATION,
  MISSING_REQUIRED_RELATION,
  UNMAPPED_CODE_SYMBOL,
  AMBIGUOUS_MAPPING,
  MISSING_EVIDENCE,
  STALE_EVIDENCE
}
