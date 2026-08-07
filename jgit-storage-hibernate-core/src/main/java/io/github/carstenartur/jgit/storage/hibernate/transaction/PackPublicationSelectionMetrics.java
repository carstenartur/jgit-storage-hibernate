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
 * Monotone diagnostics for direct and pre-persisted logical-pack publication selections.
 *
 * <p>A selection is recorded before the selected database path starts, so failed attempts remain
 * visible. Staged payload bytes are the sum of locally completed pack extensions considered by the
 * selector; they are not Git object bytes and may include PACK, IDX, Reftable and auxiliary data.
 * Metrics are zero when storage diagnostics are disabled.
 *
 * @param directSelections logical packs selected for the single repository-locked transaction path
 * @param prePersistedSelections logical packs selected for lock-free payload persistence followed by
 *     short atomic publication
 * @param directStagedPayloadBytes staged bytes assigned to the direct path
 * @param prePersistedStagedPayloadBytes staged bytes assigned to the pre-persisted path
 */
public record PackPublicationSelectionMetrics(
    long directSelections,
    long prePersistedSelections,
    long directStagedPayloadBytes,
    long prePersistedStagedPayloadBytes) {

  /** Empty diagnostics snapshot. */
  public static final PackPublicationSelectionMetrics ZERO =
      new PackPublicationSelectionMetrics(0, 0, 0, 0);

  /** Validate the immutable non-negative counters. */
  public PackPublicationSelectionMetrics {
    requireNonNegative(directSelections, "directSelections");
    requireNonNegative(prePersistedSelections, "prePersistedSelections");
    requireNonNegative(directStagedPayloadBytes, "directStagedPayloadBytes");
    requireNonNegative(prePersistedStagedPayloadBytes, "prePersistedStagedPayloadBytes");
  }

  /** @return total logical-pack selections */
  public long totalSelections() {
    return Math.addExact(directSelections, prePersistedSelections);
  }

  /** @return total locally staged bytes considered by the selector */
  public long totalStagedPayloadBytes() {
    return Math.addExact(directStagedPayloadBytes, prePersistedStagedPayloadBytes);
  }

  /**
   * Calculate the non-negative difference from an earlier monotone snapshot.
   *
   * @param earlier earlier snapshot from the same repository instance
   * @return counter delta
   */
  public PackPublicationSelectionMetrics minus(PackPublicationSelectionMetrics earlier) {
    return new PackPublicationSelectionMetrics(
        difference(directSelections, earlier.directSelections, "directSelections"),
        difference(
            prePersistedSelections, earlier.prePersistedSelections, "prePersistedSelections"),
        difference(
            directStagedPayloadBytes,
            earlier.directStagedPayloadBytes,
            "directStagedPayloadBytes"),
        difference(
            prePersistedStagedPayloadBytes,
            earlier.prePersistedStagedPayloadBytes,
            "prePersistedStagedPayloadBytes"));
  }

  private static void requireNonNegative(long value, String counter) {
    if (value < 0) {
      throw new IllegalArgumentException(counter + " must not be negative");
    }
  }

  private static long difference(long current, long earlier, String counter) {
    long result = current - earlier;
    if (result < 0) {
      throw new IllegalArgumentException(counter + " is not a monotone snapshot");
    }
    return result;
  }
}
