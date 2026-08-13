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

/** Immutable persisted access-audit event returned by bounded query methods. */
public record SecurityAccessAuditEvent(
    String auditId, Instant occurredAt, SecurityAccessAuditRecord record) {

  private static final int MAX_AUDIT_ID_LENGTH = 64;

  /** Creates a validated persisted event view. */
  public SecurityAccessAuditEvent {
    if (auditId == null || auditId.isBlank() || auditId.length() > MAX_AUDIT_ID_LENGTH) {
      throw new IllegalArgumentException("auditId must contain 1 to 64 characters");
    }
    Objects.requireNonNull(occurredAt, "occurredAt");
    Objects.requireNonNull(record, "record");
  }
}
