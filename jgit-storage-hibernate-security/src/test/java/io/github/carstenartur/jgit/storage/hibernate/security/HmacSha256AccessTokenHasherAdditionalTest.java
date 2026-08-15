/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HmacSha256AccessTokenHasherAdditionalTest {

  private static final byte[] KEY = new byte[32];

  @Test
  void rejectsNonAsciiTokensConsistentlyForHashingAndVerification() {
    HmacSha256AccessTokenHasher hasher = new HmacSha256AccessTokenHasher(KEY);
    AccessTokenHash expected = hasher.hash("jsh_ascii-token");

    assertThrows(IllegalArgumentException.class, () -> hasher.hash("jsh_töken"));
    assertFalse(hasher.verify("jsh_töken", expected));
    assertTrue(hasher.verify("jsh_ascii-token", expected));
  }
}
