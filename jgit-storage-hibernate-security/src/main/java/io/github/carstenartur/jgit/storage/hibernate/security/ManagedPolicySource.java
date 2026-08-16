/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

/** Stable ownership namespace for application-managed repository grants and ref rules. */
public record ManagedPolicySource(String sourceId, String sourceInstanceId) {

  private static final int MAX_IDENTIFIER_LENGTH = 128;

  /** Creates a validated source namespace. */
  public ManagedPolicySource {
    sourceId = required("sourceId", sourceId);
    sourceInstanceId = required("sourceInstanceId", sourceInstanceId);
  }

  private static String required(String name, String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(name + " must not contain surrounding whitespace");
    }
    if (value.length() > MAX_IDENTIFIER_LENGTH) {
      throw new IllegalArgumentException(
          name + " must contain at most " + MAX_IDENTIFIER_LENGTH + " characters");
    }
    if (value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(name + " must not contain control characters");
    }
    return value;
  }
}
