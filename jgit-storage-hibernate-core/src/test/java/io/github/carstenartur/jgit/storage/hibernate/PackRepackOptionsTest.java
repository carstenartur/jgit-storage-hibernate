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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PackRepackOptionsTest {

  @Test
  void exposesReadOptimizedAndConservativeDefaults() {
    PackRepackOptions optimized = PackRepackOptions.optimizedForReads();
    assertTrue(optimized.singlePack());
    assertTrue(optimized.buildBitmaps());
    assertTrue(optimized.writeCommitGraph());
    assertTrue(optimized.writeBloomFilter());
    assertTrue(optimized.compactReftables());

    PackRepackOptions compactOnly = PackRepackOptions.compactOnly();
    assertTrue(compactOnly.singlePack());
    assertFalse(compactOnly.buildBitmaps());
    assertFalse(compactOnly.writeCommitGraph());
    assertFalse(compactOnly.writeBloomFilter());
    assertTrue(compactOnly.compactReftables());
  }

  @Test
  void rejectsUnsafeOrInvalidCombinations() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PackRepackOptions(
                true, true, false, true, true, Duration.ofDays(1), 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PackRepackOptions(
                true, true, true, true, true, Duration.ofSeconds(-1), 1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PackRepackOptions(
                true, true, true, true, true, Duration.ZERO, -1));
  }

  @Test
  void derivesStructuralResultValues() {
    PackRepackResult result = new PackRepackResult(true, 12, 2, 7, 1, 1_000, 800, 12, 2, 99);
    assertEquals(-200, result.storedByteDelta());
    assertEquals(10, result.packReduction());
    assertEquals(Duration.ofNanos(99), result.elapsed());

    assertThrows(
        IllegalArgumentException.class,
        () -> new PackRepackResult(true, -1, 0, 0, 0, 0, 0, 0, 0, 0));
  }
}
