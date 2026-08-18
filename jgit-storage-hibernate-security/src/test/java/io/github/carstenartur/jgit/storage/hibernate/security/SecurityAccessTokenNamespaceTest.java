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
  void publishesCurrentAndLegacyBearerNamespaces() {
    assertEquals(1, SecurityAccessTokenNamespace.CURRENT_VERSION);
    assertEquals("jsh1_", SecurityAccessTokenNamespace.VERSION_1_BEARER_PREFIX);
    assertEquals("jsh_", SecurityAccessTokenNamespace.LEGACY_BEARER_PREFIX);
    assertEquals(
        Set.of("jsh1_", "jsh_"), SecurityAccessTokenNamespace.bearerPrefixes());
  }

  @Test
  void recognizesCurrentAndLegacyCompleteTokenSyntax() {
    String current = "jsh1_" + "A".repeat(16) + "." + "B".repeat(43);
    String legacy = "jsh_" + "C".repeat(16) + "." + "D".repeat(43);
    assertTrue(SecurityAccessTokenNamespace.isVersion1Token(current));
    assertTrue(SecurityAccessTokenNamespace.isLegacyToken(legacy));
    assertTrue(SecurityAccessTokenNamespace.isRecognizedToken(current));
    assertTrue(SecurityAccessTokenNamespace.isRecognizedToken(legacy));

    assertFalse(SecurityAccessTokenNamespace.isVersion1Token(null));
    assertFalse(SecurityAccessTokenNamespace.isLegacyToken(null));
    assertFalse(SecurityAccessTokenNamespace.isRecognizedToken(""));
    assertFalse(
        SecurityAccessTokenNamespace.isVersion1Token(
            "jsh1_" + "A".repeat(15) + "." + "B".repeat(43)));
    assertFalse(
        SecurityAccessTokenNamespace.isVersion1Token(
            "jsh1_" + "A".repeat(16) + "." + "B".repeat(42)));
    assertFalse(
        SecurityAccessTokenNamespace.isLegacyToken(
            "jwt_" + "A".repeat(16) + "." + "B".repeat(43)));
    assertFalse(
        SecurityAccessTokenNamespace.isRecognizedToken(
            "jsh_" + "A".repeat(16) + "." + "!".repeat(43)));
  }
}
