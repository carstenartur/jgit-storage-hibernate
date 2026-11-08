/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class PublicationPairingTest {

  @Test
  void firstLookUsesIndependentPairedForksAndStrictMargin() {
    assertTrue(PublicationPairing.lowerBoundPercent(Collections.nCopies(24, 1.01)) > -5);
    assertFalse(PublicationPairing.lowerBoundPercent(Collections.nCopies(24, .95)) > -5);
    assertFalse(PublicationPairing.lowerBoundPercent(Collections.nCopies(96, .90)) > -5);
  }

  @Test
  void cumulativeLookIncludesAllInitialPairs() {
    List<Double> ratios = new ArrayList<>();
    for (int i = 0; i < 24; i++) {
      ratios.add(i % 2 == 0 ? .85 : 1.10);
    }
    assertTrue(PublicationPairing.lowerBoundPercent(ratios) <= -5);
    ratios.addAll(Collections.nCopies(72, 1.01));
    assertTrue(PublicationPairing.lowerBoundPercent(ratios) > -5);
  }

  @Test
  void incompleteOrNonfiniteStagesCannotStopTheRunner() {
    assertThrows(
        IllegalArgumentException.class,
        () -> PublicationPairing.lowerBoundPercent(Collections.nCopies(23, 1.01)));
    assertThrows(
        IllegalArgumentException.class,
        () -> PublicationPairing.lowerBoundPercent(Collections.nCopies(24, Double.NaN)));
    assertThrows(
        IllegalArgumentException.class,
        () -> PublicationPairing.lowerBoundPercent(Collections.nCopies(24, 0.0)));
  }

  @Test
  void alternatingOrderIsBalancedAtBothPermittedLooks() {
    for (int count : new int[] {24, 96}) {
      int sizeFirst = 0;
      for (int pair = 1; pair <= count; pair++) {
        String first = PublicationPairing.policyAt(pair, 0);
        String second = PublicationPairing.policyAt(pair, 1);
        assertFalse(first.equals(second));
        if (first.equals("size")) {
          sizeFirst++;
        }
      }
      assertEquals(count / 2, sizeFirst);
    }
    assertThrows(IllegalArgumentException.class, () -> PublicationPairing.policyAt(0, 0));
    assertThrows(IllegalArgumentException.class, () -> PublicationPairing.policyAt(1, 2));
  }
}
