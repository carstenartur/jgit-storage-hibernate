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

/** Stable principal or group identity used by authorization policy. */
public record SecuritySubject(SecuritySubjectType type, String id) {

  private static final int MAX_ID_LENGTH = 128;

  /** Creates a principal subject. */
  public static SecuritySubject principal(String principalId) {
    return new SecuritySubject(SecuritySubjectType.PRINCIPAL, principalId);
  }

  /** Creates a group subject. */
  public static SecuritySubject group(String groupId) {
    return new SecuritySubject(SecuritySubjectType.GROUP, groupId);
  }

  /** Creates a validated subject. */
  public SecuritySubject {
    Objects.requireNonNull(type, "type");
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("subject id must not be blank");
    }
    if (id.length() > MAX_ID_LENGTH) {
      throw new IllegalArgumentException(
          "subject id must contain at most " + MAX_ID_LENGTH + " characters");
    }
  }

  boolean matches(GitAccessContext context) {
    return switch (type) {
      case PRINCIPAL -> id.equals(context.principalId());
      case GROUP -> context.groupIds().contains(id);
    };
  }
}
