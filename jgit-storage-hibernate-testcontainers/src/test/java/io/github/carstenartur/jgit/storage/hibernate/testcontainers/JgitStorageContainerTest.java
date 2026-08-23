/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class JgitStorageContainerTest {

  @Test
  void validatesTheSameRepositoryNameSubsetAsTheServer() {
    assertEquals("demo-1", JgitStorageContainer.requireRepositoryName("demo-1"));
    assertThrows(
        IllegalArgumentException.class,
        () -> JgitStorageContainer.requireRepositoryName("../escape"));
    assertThrows(
        IllegalArgumentException.class,
        () -> JgitStorageContainer.requireRepositoryName("demo.git"));
  }
}
