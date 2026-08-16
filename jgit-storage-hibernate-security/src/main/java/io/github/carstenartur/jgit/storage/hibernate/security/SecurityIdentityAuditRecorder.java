/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

/** Append-only credential lifecycle audit sink. */
@FunctionalInterface
public interface SecurityIdentityAuditRecorder {

  /** Explicit no-op recorder for consumers that deliberately do not select persistent audit. */
  SecurityIdentityAuditRecorder NONE = ignored -> {};

  /** Append one bounded, non-secret audit record or throw on persistence failure. */
  void record(SecurityIdentityAuditRecord record);
}
