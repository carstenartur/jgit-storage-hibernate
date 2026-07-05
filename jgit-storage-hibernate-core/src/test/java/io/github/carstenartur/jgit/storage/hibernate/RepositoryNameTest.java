/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
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

  @Test
  void rejectsMissingName() {
    String missingName = null;
    assertThrows(NullPointerException.class, () -> new RepositoryName(missingName));
  }
}
