/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import java.util.Objects;

/** Fail-closed result raised by an optional {@link RepositoryAccessPolicy}. */
public final class RepositoryAccessDeniedException extends HibernateStorageException {

  private final RepositoryAccessRequest request;
  private final String reasonCode;
  private final String evidenceId;
  private final long policyVersion;

  /**
   * Create a bounded denial carrying non-secret authorization evidence.
   *
   * @param request denied operation
   * @param reasonCode stable non-secret reason code
   * @param evidenceId optional stable matching policy identifier
   * @param policyVersion policy snapshot version, or zero when unavailable
   */
  public RepositoryAccessDeniedException(
      RepositoryAccessRequest request,
      String reasonCode,
      String evidenceId,
      long policyVersion) {
    super(message(request, reasonCode));
    this.request = Objects.requireNonNull(request, "request");
    this.reasonCode = bounded("reasonCode", reasonCode, 128);
    this.evidenceId =
        evidenceId == null ? null : bounded("evidenceId", evidenceId, 128);
    if (policyVersion < 0) {
      throw new IllegalArgumentException("policyVersion must not be negative");
    }
    this.policyVersion = policyVersion;
  }

  private static String message(RepositoryAccessRequest request, String reasonCode) {
    Objects.requireNonNull(request, "request");
    return "Repository access denied for "
        + request.operation()
        + " on "
        + request.repositoryName()
        + (request.refScoped() ? " (" + request.refName() + ")" : "")
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

  /** @return denied operation */
  public RepositoryAccessRequest request() {
    return request;
  }

  /** @return stable non-secret reason code */
  public String reasonCode() {
    return reasonCode;
  }

  /** @return matching evidence identifier, or {@code null} */
  public String evidenceId() {
    return evidenceId;
  }

  /** @return policy snapshot version */
  public long policyVersion() {
    return policyVersion;
  }
}
