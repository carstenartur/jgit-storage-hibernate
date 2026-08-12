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

/** Immutable protected-ref rule loaded from persistent policy state. */
public record RepositoryRefRule(
    String id,
    RepositoryName repositoryName,
    String refPattern,
    GitRepositoryPermission permission,
    SecurityEffect effect,
    int priority,
    SecuritySubject subject,
    long securityVersion) {

  private static final int MAX_ID_LENGTH = 128;

  /** Creates a validated rule. A {@code null} subject applies to every authorized subject. */
  public RepositoryRefRule {
    if (id == null || id.isBlank() || id.length() > MAX_ID_LENGTH) {
      throw new IllegalArgumentException(
          "rule id must contain 1 to " + MAX_ID_LENGTH + " characters");
    }
    Objects.requireNonNull(repositoryName, "repositoryName");
    GitRefPattern.validate(refPattern);
    Objects.requireNonNull(permission, "permission");
    Objects.requireNonNull(effect, "effect");
    if (securityVersion < 0) {
      throw new IllegalArgumentException("securityVersion must not be negative");
    }
  }

  boolean matches(GitAccessContext context, RepositoryAuthorizationRequest request) {
    return repositoryName.equals(request.repositoryName())
        && permission.includes(request.permission())
        && request.refScoped()
        && GitRefPattern.matches(refPattern, request.refName())
        && (subject == null || subject.matches(context));
  }

  int specificity() {
    return GitRefPattern.specificity(refPattern);
  }
}
