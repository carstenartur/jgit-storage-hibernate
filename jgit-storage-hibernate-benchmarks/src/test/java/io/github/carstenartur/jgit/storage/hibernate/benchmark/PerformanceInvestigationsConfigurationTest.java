/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PerformanceInvestigationsConfigurationTest {

  @Test
  void absentShardSelectionRetainsAndCopiesTheCompleteMatrix() {
    String[] defaults = {"hsqldb", "postgresql", "postgresql-hikari"};

    String[] selected =
        PerformanceInvestigationParameters.select("backend", null, defaults, defaults);

    assertArrayEquals(defaults, selected);
    assertNotSame(defaults, selected);
  }

  @Test
  void explicitShardSelectionProducesOneTrimmedParameterValue() {
    String[] selected =
        PerformanceInvestigationParameters.select(
            "cache-state", " warm ", new String[] {"cold", "warm"}, "cold", "warm");

    assertArrayEquals(new String[] {"warm"}, selected);
  }

  @Test
  void unsupportedShardSelectionFailsBeforeJmhStarts() {
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                PerformanceInvestigationParameters.select(
                    "backend",
                    "filesystem",
                    new String[] {"hsqldb", "postgresql"},
                    "hsqldb",
                    "postgresql"));

    assertTrue(failure.getMessage().contains("filesystem"));
    assertTrue(failure.getMessage().contains("hsqldb, postgresql"));
  }
}
