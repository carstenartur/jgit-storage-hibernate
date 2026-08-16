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

/** One deterministic desired protected-ref rule in a managed policy snapshot. */
public record DesiredRepositoryRefRule(
    String entryKey,
    String refPattern,
    GitRepositoryPermission permission,
    SecurityEffect effect,
    int priority,
    SecuritySubject subject) {

  private static final int MAX_ABSOLUTE_PRIORITY = 1_000_000;

  /** Creates a validated desired ref rule; {@code subject == null} means every subject. */
  public DesiredRepositoryRefRule {
    entryKey = DesiredRepositoryGrant.requiredEntryKey(entryKey);
    GitRefPattern.validate(refPattern);
    permission = Objects.requireNonNull(permission, "permission");
    effect = Objects.requireNonNull(effect, "effect");
    if (priority < -MAX_ABSOLUTE_PRIORITY || priority > MAX_ABSOLUTE_PRIORITY) {
      throw new IllegalArgumentException(
          "priority must be between -"
              + MAX_ABSOLUTE_PRIORITY
              + " and "
              + MAX_ABSOLUTE_PRIORITY);
    }
  }

  /** Create a rule that applies to every principal and group. */
  public static DesiredRepositoryRefRule global(
      String entryKey,
      String refPattern,
      GitRepositoryPermission permission,
      SecurityEffect effect,
      int priority) {
    return new DesiredRepositoryRefRule(
        entryKey, refPattern, permission, effect, priority, null);
  }
}
