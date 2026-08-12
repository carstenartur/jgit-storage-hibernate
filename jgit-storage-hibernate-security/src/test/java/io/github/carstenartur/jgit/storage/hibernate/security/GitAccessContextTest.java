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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GitAccessContextTest {

  @Test
  void snapshotsMutableInputAndRejectsMissingPrincipalEvidence() {
    Set<String> groups = new LinkedHashSet<>(Set.of("maintainers"));
    Map<String, String> attributes = new LinkedHashMap<>(Map.of("tenant", "north"));

    GitAccessContext context =
        new GitAccessContext(
            "principal-1", groups, "oidc", "session-1", "correlation-1", attributes);
    groups.add("late-group");
    attributes.put("late", "value");

    assertEquals(Set.of("maintainers"), context.groupIds());
    assertEquals(Map.of("tenant", "north"), context.attributes());
    assertThrows(
        IllegalArgumentException.class,
        () -> new GitAccessContext(" ", Set.of(), "oidc", "session", "correlation", Map.of()));
    assertThrows(UnsupportedOperationException.class, () -> context.groupIds().add("other"));
  }

  @Test
  void principalAndGroupIdentifiersMatchPersistedSchemaLimits() {
    String maximum = "x".repeat(128);
    GitAccessContext context =
        new GitAccessContext(
            maximum, Set.of(maximum), "oidc", "session", "correlation", Map.of());

    assertEquals(maximum, context.principalId());
    assertEquals(Set.of(maximum), context.groupIds());

    String tooLong = "x".repeat(129);
    IllegalArgumentException principalException =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new GitAccessContext(
                    tooLong, Set.of(), "oidc", "session", "correlation", Map.of()));
    IllegalArgumentException groupException =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                new GitAccessContext(
                    "principal", Set.of(tooLong), "oidc", "session", "correlation", Map.of()));

    assertEquals(
        "principalId must contain at most 128 characters", principalException.getMessage());
    assertEquals("groupId must contain at most 128 characters", groupException.getMessage());
  }
}
