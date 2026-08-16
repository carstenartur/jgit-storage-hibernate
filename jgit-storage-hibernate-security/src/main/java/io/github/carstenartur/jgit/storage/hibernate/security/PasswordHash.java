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

/** Versioned, encoded password verifier produced by a {@link PasswordHasher}. */
public record PasswordHash(String algorithm, int version, String encodedHash) {

  private static final int MAX_ALGORITHM_LENGTH = 64;
  private static final int MAX_ENCODED_HASH_LENGTH = 2048;

  /** Creates and validates a password verifier without exposing plaintext password data. */
  public PasswordHash {
    algorithm = required("algorithm", algorithm, MAX_ALGORITHM_LENGTH);
    if (version < 1) {
      throw new IllegalArgumentException("version must be positive");
    }
    encodedHash = required("encodedHash", encodedHash, MAX_ENCODED_HASH_LENGTH);
  }

  private static String required(String name, String value, int maximumLength) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (value.length() > maximumLength) {
      throw new IllegalArgumentException(
          name + " must contain at most " + maximumLength + " characters");
    }
    return value;
  }

  /** Never include the encoded verifier in accidental logs. */
  @Override
  public String toString() {
    return "PasswordHash[algorithm=" + algorithm + ", version=" + version + ", encodedHash=<redacted>]";
  }
}
