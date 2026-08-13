/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import java.time.Duration;
import java.util.Objects;

/** Bounded local password lockout policy. */
public record PasswordLockoutPolicy(int maximumFailedAttempts, Duration lockDuration) {

  /** Conservative default: five failures lock the credential for fifteen minutes. */
  public static final PasswordLockoutPolicy DEFAULT =
      new PasswordLockoutPolicy(5, Duration.ofMinutes(15));

  /** Creates and validates a lockout policy. */
  public PasswordLockoutPolicy {
    if (maximumFailedAttempts < 1 || maximumFailedAttempts > 100) {
      throw new IllegalArgumentException(
          "maximumFailedAttempts must be between 1 and 100");
    }
    lockDuration = Objects.requireNonNull(lockDuration, "lockDuration");
    if (lockDuration.isNegative()
        || lockDuration.isZero()
        || lockDuration.compareTo(Duration.ofDays(30)) > 0) {
      throw new IllegalArgumentException(
          "lockDuration must be positive and at most 30 days");
    }
  }
}
