/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import java.util.Objects;

/** One deterministic desired repository grant inside an externally managed policy snapshot. */
public record DesiredRepositoryGrant(
    String entryKey,
    SecuritySubject subject,
    GitRepositoryPermission permission,
    SecurityEffect effect) {

  private static final int MAX_ENTRY_KEY_LENGTH = 256;

  /** Creates a validated desired grant. */
  public DesiredRepositoryGrant {
    entryKey = requiredEntryKey(entryKey);
    subject = Objects.requireNonNull(subject, "subject");
    permission = Objects.requireNonNull(permission, "permission");
    effect = Objects.requireNonNull(effect, "effect");
  }

  static String requiredEntryKey(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("entryKey must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException("entryKey must not contain surrounding whitespace");
    }
    if (value.length() > MAX_ENTRY_KEY_LENGTH) {
      throw new IllegalArgumentException(
          "entryKey must contain at most " + MAX_ENTRY_KEY_LENGTH + " characters");
    }
    if (value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("entryKey must not contain control characters");
    }
    return value;
  }
}
