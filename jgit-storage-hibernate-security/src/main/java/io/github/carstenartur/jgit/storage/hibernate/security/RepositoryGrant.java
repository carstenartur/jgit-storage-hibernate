/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package io.github.carstenartur.jgit.storage.hibernate.security;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import java.util.Objects;

/** Immutable repository grant loaded from persistent policy state. */
public record RepositoryGrant(
    String id,
    SecuritySubject subject,
    RepositoryName repositoryName,
    GitRepositoryPermission permission,
    SecurityEffect effect,
    long securityVersion) {

  private static final int MAX_ID_LENGTH = 128;

  /** Creates a validated grant. */
  public RepositoryGrant {
    id = requiredId(id);
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(repositoryName, "repositoryName");
    Objects.requireNonNull(permission, "permission");
    Objects.requireNonNull(effect, "effect");
    if (securityVersion < 0) {
      throw new IllegalArgumentException("securityVersion must not be negative");
    }
  }

  private static String requiredId(String value) {
    if (value == null || value.isBlank() || value.length() > MAX_ID_LENGTH) {
      throw new IllegalArgumentException(
          "grant id must contain 1 to " + MAX_ID_LENGTH + " characters");
    }
    return value;
  }
}
