/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

/** Lifecycle status for a Java analysis run. */
public enum JavaAnalysisStatus {
  RUNNING,
  COMPLETED,
  COMPLETED_WITH_ERRORS,
  FAILED
}
