/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

/** Pluggable salted password-verifier contract. */
public interface PasswordHasher {

  /**
   * Create a new salted verifier.
   *
   * <p>Implementations must not retain the supplied array. Callers remain responsible for clearing
   * their own password array after use.
   *
   * @param password plaintext password characters
   * @return versioned verifier
   */
  PasswordHash hash(char[] password);

  /**
   * Verify a password in constant time with respect to the derived verifier comparison.
   *
   * <p>Malformed or unsupported stored values must fail closed and return {@code false}.
   *
   * @param password plaintext password characters
   * @param expected persisted verifier
   * @return whether the password matches
   */
  boolean verify(char[] password, PasswordHash expected);

  /**
   * Return whether a successfully verified value should be replaced with a fresh verifier.
   *
   * @param existing persisted verifier
   * @return whether rehashing is recommended
   */
  default boolean needsRehash(PasswordHash existing) {
    return false;
  }
}
