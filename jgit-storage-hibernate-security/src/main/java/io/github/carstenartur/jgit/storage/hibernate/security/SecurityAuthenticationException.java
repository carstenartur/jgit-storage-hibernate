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
import java.time.Instant;
import java.util.Objects;

/** Fail-closed local credential authentication result without secret evidence. */
public final class SecurityAuthenticationException extends HibernateStorageException {

  private final SecurityAuthenticationReason reason;
  private final Instant retryAt;

  /** Create an authentication failure without a retry time. */
  public SecurityAuthenticationException(SecurityAuthenticationReason reason) {
    this(reason, null, null);
  }

  /** Create an authentication failure with an optional retry time and cause. */
  public SecurityAuthenticationException(
      SecurityAuthenticationReason reason, Instant retryAt, Throwable cause) {
    super(message(reason, retryAt), cause);
    this.reason = requireFailure(reason);
    this.retryAt = retryAt;
  }

  private static String message(SecurityAuthenticationReason reason, Instant retryAt) {
    SecurityAuthenticationReason validated = requireFailure(reason);
    return "Security authentication failed: "
        + validated
        + (retryAt != null ? " (retry at or after " + retryAt + ")" : "");
  }

  private static SecurityAuthenticationReason requireFailure(
      SecurityAuthenticationReason reason) {
    SecurityAuthenticationReason validated = Objects.requireNonNull(reason, "reason");
    if (validated.authenticated()) {
      throw new IllegalArgumentException("an authenticated reason cannot represent an exception");
    }
    return validated;
  }

  /** Return the stable non-secret failure reason. */
  public SecurityAuthenticationReason reason() {
    return reason;
  }

  /** Return the earliest retry time for a temporary lock, or {@code null}. */
  public Instant retryAt() {
    return retryAt;
  }
}
