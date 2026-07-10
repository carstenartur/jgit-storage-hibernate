/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

/** Semantic relations represented in a versioned Java software graph. */
public enum JavaGraphEdgeKind {
  CALLS,
  CONSTRUCTS,
  READS_FIELD,
  REFERENCES_TYPE,
  EXTENDS,
  IMPLEMENTS,
  OVERRIDES,
  ANNOTATED_WITH
}
