/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryAccessDeniedException;
import java.util.Objects;

/** Shared fail-closed audit semantics for Security authorization policies. */
final class SecurityAuditSupport {

  private SecurityAuditSupport() {}

  static void recordAllowed(
      SecurityAccessAuditRecorder recorder, SecurityAccessAuditRecord record) {
    try {
      Objects.requireNonNull(recorder, "recorder").record(record);
    } catch (RuntimeException failure) {
      throw persistenceFailure(record, failure);
    }
  }

  static void deny(
      SecurityAccessAuditRecorder recorder,
      SecurityAccessAuditRecord record,
      RepositoryAccessDeniedException denied) {
    try {
      Objects.requireNonNull(recorder, "recorder").record(record);
    } catch (RuntimeException failure) {
      denied.addSuppressed(persistenceFailure(record, failure));
    }
    throw denied;
  }

  static void fail(
      SecurityAccessAuditRecorder recorder,
      SecurityAccessAuditRecord record,
      RuntimeException authorizationFailure) {
    try {
      Objects.requireNonNull(recorder, "recorder").record(record);
    } catch (RuntimeException auditFailure) {
      authorizationFailure.addSuppressed(persistenceFailure(record, auditFailure));
    }
    throw authorizationFailure;
  }

  private static SecurityAuditPersistenceException persistenceFailure(
      SecurityAccessAuditRecord record, RuntimeException failure) {
    return failure instanceof SecurityAuditPersistenceException persistenceFailure
        ? persistenceFailure
        : new SecurityAuditPersistenceException(record, failure);
  }
}
