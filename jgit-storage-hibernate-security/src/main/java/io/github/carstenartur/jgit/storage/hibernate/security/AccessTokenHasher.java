/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

/** Pluggable one-way access-token verifier contract. */
public interface AccessTokenHasher {

  /**
   * Produce a persisted one-way verifier for a high-entropy token.
   *
   * @param tokenValue complete plaintext token
   * @return versioned verifier
   */
  AccessTokenHash hash(String tokenValue);

  /**
   * Compare a token to a persisted verifier using a constant-time derived-value comparison.
   *
   * @param tokenValue complete plaintext token
   * @param expected persisted verifier
   * @return whether the token matches
   */
  boolean verify(String tokenValue, AccessTokenHash expected);
}
