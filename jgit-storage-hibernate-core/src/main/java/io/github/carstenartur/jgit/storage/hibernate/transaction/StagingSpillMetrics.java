/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.transaction;

/**
 * Monotone diagnostics for memory-first pack-extension staging spills.
 *
 * <p>A spill is counted only when bytes already retained in memory are copied to a temporary file.
 * An extension whose first write is already too large starts file-backed with a zero-byte prefix and
 * is therefore not a memory-to-file spill. Metrics are zero when storage diagnostics are disabled.
 *
 * @param memoryToFileSpills completed transitions with a non-empty in-memory prefix
 * @param spilledPrefixBytes bytes copied from memory into the new staging file during those spills
 */
public record StagingSpillMetrics(long memoryToFileSpills, long spilledPrefixBytes) {

  /** Empty diagnostics snapshot. */
  public static final StagingSpillMetrics ZERO = new StagingSpillMetrics(0, 0);

  /** Validate the immutable non-negative counters. */
  public StagingSpillMetrics {
    if (memoryToFileSpills < 0) {
      throw new IllegalArgumentException("memoryToFileSpills must not be negative");
    }
    if (spilledPrefixBytes < 0) {
      throw new IllegalArgumentException("spilledPrefixBytes must not be negative");
    }
  }

  /**
   * Calculate the non-negative difference from an earlier monotone snapshot.
   *
   * @param earlier earlier snapshot from the same repository instance
   * @return counter delta
   */
  public StagingSpillMetrics minus(StagingSpillMetrics earlier) {
    return new StagingSpillMetrics(
        difference(memoryToFileSpills, earlier.memoryToFileSpills, "memoryToFileSpills"),
        difference(spilledPrefixBytes, earlier.spilledPrefixBytes, "spilledPrefixBytes"));
  }

  private static long difference(long current, long earlier, String counter) {
    long result = current - earlier;
    if (result < 0) {
      throw new IllegalArgumentException(counter + " is not a monotone snapshot");
    }
    return result;
  }
}
