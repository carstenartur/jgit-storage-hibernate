/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import java.time.Instant;
import java.util.Objects;

/** Persisted credential lifecycle audit event. */
public record SecurityIdentityAuditEvent(
    String auditId, Instant occurredAt, SecurityIdentityAuditRecord record) {

  /** Creates and validates one immutable persisted event. */
  public SecurityIdentityAuditEvent {
    if (auditId == null || auditId.isBlank() || auditId.length() > 64) {
      throw new IllegalArgumentException("auditId must contain 1 to 64 characters");
    }
    occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
    record = Objects.requireNonNull(record, "record");
  }
}
