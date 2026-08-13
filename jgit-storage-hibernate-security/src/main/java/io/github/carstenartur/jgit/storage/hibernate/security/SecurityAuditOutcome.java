/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

/** Outcome of one persisted repository authorization decision. */
public enum SecurityAuditOutcome {
  /** The policy allowed the requested operation. */
  ALLOWED,

  /** The policy explicitly denied the requested operation. */
  DENIED,

  /** The policy could not produce a decision because evaluation failed. */
  FAILED
}
