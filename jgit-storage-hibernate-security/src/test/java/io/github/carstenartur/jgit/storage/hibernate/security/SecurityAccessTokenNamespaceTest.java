/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class SecurityAccessTokenNamespaceTest {

  @Test
  void publishesTheFrozenVersionOneBearerNamespace() {
    assertEquals(1, SecurityAccessTokenNamespace.CURRENT_VERSION);
    assertEquals("jsh_", SecurityAccessTokenNamespace.VERSION_1_BEARER_PREFIX);
    assertEquals(
        Set.of("jsh_"), SecurityAccessTokenNamespace.recognizedBearerPrefixes());
  }

  @Test
  void recognizesOnlyTheCompleteVersionOneTokenSyntax() {
    String valid = "jsh_" + "A".repeat(16) + "." + "B".repeat(43);
    assertTrue(SecurityAccessTokenNamespace.isVersion1Token(valid));

    assertFalse(SecurityAccessTokenNamespace.isVersion1Token(null));
    assertFalse(SecurityAccessTokenNamespace.isVersion1Token(""));
    assertFalse(
        SecurityAccessTokenNamespace.isVersion1Token(
            "jsh_" + "A".repeat(15) + "." + "B".repeat(43)));
    assertFalse(
        SecurityAccessTokenNamespace.isVersion1Token(
            "jsh_" + "A".repeat(16) + "." + "B".repeat(42)));
    assertFalse(
        SecurityAccessTokenNamespace.isVersion1Token(
            "jwt_" + "A".repeat(16) + "." + "B".repeat(43)));
    assertFalse(
        SecurityAccessTokenNamespace.isVersion1Token(
            "jsh_" + "A".repeat(16) + "." + "!".repeat(43)));
  }
}
