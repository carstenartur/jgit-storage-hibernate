/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

/** Structured changes detected between two Java project snapshots. */
public enum SemanticChangeKind {
  ADDED,
  REMOVED,
  MOVED,
  RENAMED,
  SIGNATURE_CHANGED,
  VISIBILITY_CHANGED,
  ANNOTATIONS_CHANGED,
  BINDING_QUALITY_CHANGED
}
