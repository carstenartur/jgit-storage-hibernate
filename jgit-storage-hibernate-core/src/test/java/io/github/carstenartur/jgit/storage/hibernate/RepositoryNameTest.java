/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RepositoryNameTest {
  @Test
  void acceptsNonBlankName() {
    RepositoryName name = new RepositoryName("audio-workflows");
    assertEquals("audio-workflows", name.value());
    assertEquals("audio-workflows", name.toString());
  }

  @Test
  void rejectsBlankName() {
    assertThrows(IllegalArgumentException.class, () -> new RepositoryName("  "));
  }
}
