/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import io.github.carstenartur.jgit.storage.hibernate.HibernateStorageException;
import java.util.Objects;

/** Fail-closed result raised when an allowed authorization decision cannot be audited. */
public final class SecurityAuditPersistenceException extends HibernateStorageException {

  private final SecurityAccessAuditRecord record;

  /**
   * Create an exception carrying the bounded record that could not be persisted.
   *
   * @param record non-secret audit record
   * @param cause persistence failure
   */
  public SecurityAuditPersistenceException(
      SecurityAccessAuditRecord record, RuntimeException cause) {
    super(message(record), Objects.requireNonNull(cause, "cause"));
    this.record = Objects.requireNonNull(record, "record");
  }

  private static String message(SecurityAccessAuditRecord record) {
    Objects.requireNonNull(record, "record");
    return "Could not persist security audit for "
        + record.operation()
        + " on "
        + record.repositoryName();
  }

  /** @return immutable non-secret record that could not be persisted */
  public SecurityAccessAuditRecord record() {
    return record;
  }
}
