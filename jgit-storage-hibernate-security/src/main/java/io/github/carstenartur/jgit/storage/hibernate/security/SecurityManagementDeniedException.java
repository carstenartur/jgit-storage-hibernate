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

/** Fail-closed credential-management denial carrying bounded, non-secret evidence. */
public final class SecurityManagementDeniedException extends HibernateStorageException {

  private final SecurityManagementRequest request;
  private final String reasonCode;
  private final String evidenceId;
  private final long policyVersion;

  /** Create a bounded management denial. */
  public SecurityManagementDeniedException(
      SecurityManagementRequest request,
      String reasonCode,
      String evidenceId,
      long policyVersion) {
    super(message(request, reasonCode));
    this.request = Objects.requireNonNull(request, "request");
    this.reasonCode = bounded("reasonCode", reasonCode, 128);
    this.evidenceId = evidenceId == null ? null : bounded("evidenceId", evidenceId, 128);
    if (policyVersion < 0) {
      throw new IllegalArgumentException("policyVersion must not be negative");
    }
    this.policyVersion = policyVersion;
  }

  private static String message(SecurityManagementRequest request, String reasonCode) {
    Objects.requireNonNull(request, "request");
    return "Security management denied for "
        + request.operation()
        + " on principal "
        + request.subjectPrincipalId()
        + ": "
        + bounded("reasonCode", reasonCode, 128);
  }

  private static String bounded(String name, String value, int maximumLength) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (value.length() > maximumLength) {
      throw new IllegalArgumentException(
          name + " must contain at most " + maximumLength + " characters");
    }
    return value;
  }

  public SecurityManagementRequest request() {
    return request;
  }

  public String reasonCode() {
    return reasonCode;
  }

  public String evidenceId() {
    return evidenceId;
  }

  public long policyVersion() {
    return policyVersion;
  }
}
