/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

/** Framework-neutral sink for bounded repository authorization audit records. */
@FunctionalInterface
public interface SecurityAccessAuditRecorder {

  /** Explicit no-op recorder for deployments that have not enabled persistent audit. */
  SecurityAccessAuditRecorder NONE = record -> {};

  /**
   * Persist or forward one immutable audit record.
   *
   * @param record non-secret authorization evidence
   */
  void record(SecurityAccessAuditRecord record);
}
