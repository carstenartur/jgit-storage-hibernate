/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

import java.time.Duration;

/** Structural outcome of one JGit DFS garbage-collection and repack maintenance run. */
public record PackRepackResult(
    boolean successful,
    int packsBefore,
    int packsAfter,
    int reftablesBefore,
    int reftablesAfter,
    long storedBytesBefore,
    long storedBytesAfter,
    int sourcePackDescriptions,
    int newPackDescriptions,
    long elapsedNanos) {

  /** Validate non-negative inventory and timing values. */
  public PackRepackResult {
    requireNonNegative(packsBefore, "packsBefore");
    requireNonNegative(packsAfter, "packsAfter");
    requireNonNegative(reftablesBefore, "reftablesBefore");
    requireNonNegative(reftablesAfter, "reftablesAfter");
    requireNonNegative(storedBytesBefore, "storedBytesBefore");
    requireNonNegative(storedBytesAfter, "storedBytesAfter");
    requireNonNegative(sourcePackDescriptions, "sourcePackDescriptions");
    requireNonNegative(newPackDescriptions, "newPackDescriptions");
    requireNonNegative(elapsedNanos, "elapsedNanos");
  }

  /**
   * Return the change in stored logical pack-extension bytes.
   *
   * <p>A negative value means the repack reduced storage. A positive value is possible when
   * read-acceleration extensions such as bitmaps and commit graphs are added.
   *
   * @return after minus before, in bytes
   */
  public long storedByteDelta() {
    return storedBytesAfter - storedBytesBefore;
  }

  /**
   * Return the number of ordinary pack files removed from the active pack list.
   *
   * @return before minus after
   */
  public int packReduction() {
    return packsBefore - packsAfter;
  }

  /**
   * Return the measured end-to-end maintenance duration.
   *
   * @return elapsed duration
   */
  public Duration elapsed() {
    return Duration.ofNanos(elapsedNanos);
  }

  private static void requireNonNegative(long value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
  }
}
