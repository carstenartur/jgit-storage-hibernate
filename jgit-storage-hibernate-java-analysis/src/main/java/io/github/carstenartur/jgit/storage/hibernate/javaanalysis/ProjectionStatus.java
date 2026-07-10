/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

/** Lifecycle state of a rebuildable semantic projection. */
public enum ProjectionStatus {
  MISSING,
  RUNNING,
  CURRENT,
  PARTIAL,
  STALE,
  FAILED
}
