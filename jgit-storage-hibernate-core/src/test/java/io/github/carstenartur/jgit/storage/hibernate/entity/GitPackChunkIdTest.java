/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GitPackChunkIdTest {

  @Test
  void supportsPersistenceConstructionAndValueEquality() {
    GitPackChunkId id = new GitPackChunkId();
    id.setPackId(17L);
    id.setChunkIndex(4);

    GitPackChunkId equal = new GitPackChunkId(17L, 4);
    GitPackChunkId differentPack = new GitPackChunkId(18L, 4);
    GitPackChunkId differentChunk = new GitPackChunkId(17L, 5);

    assertEquals(17L, id.getPackId());
    assertEquals(4, id.getChunkIndex());
    assertEquals(id, id);
    assertEquals(id, equal);
    assertEquals(id.hashCode(), equal.hashCode());
    assertNotEquals(id, differentPack);
    assertNotEquals(id, differentChunk);
    assertFalse(id.equals(null));
    assertFalse(id.equals("17:4"));
    assertTrue(equal.equals(id));
  }

  @Test
  void rejectsMissingPackIdentityInConvenienceConstructor() {
    assertThrows(NullPointerException.class, () -> new GitPackChunkId(null, 0));
  }
}
