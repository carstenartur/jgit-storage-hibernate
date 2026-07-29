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
 * Monotone diagnostic counters for committed pack-extension reads that reached the database.
 *
 * <p>Catalogued chunked extensions do not appear here because they open without the database
 * metadata fallback. Successful counters therefore identify the extensions for which the fallback
 * transaction loaded inline bytes or discovered a chunked row. Missing lookups are recorded
 * separately. Metrics are zero when repository diagnostics are disabled.
 *
 * @param packInlineReads inline PACK rows loaded from the database
 * @param packChunkedReads chunked PACK rows opened through the database fallback
 * @param indexInlineReads inline IDX rows loaded from the database
 * @param indexChunkedReads chunked IDX rows opened through the database fallback
 * @param reftableInlineReads inline Reftable rows loaded from the database
 * @param reftableChunkedReads chunked Reftable rows opened through the database fallback
 * @param otherInlineReads inline rows for other pack extensions
 * @param otherChunkedReads chunked rows for other pack extensions
 * @param missingReads committed extension lookups that did not find a row
 */
public record PackFileReadMetrics(
    long packInlineReads,
    long packChunkedReads,
    long indexInlineReads,
    long indexChunkedReads,
    long reftableInlineReads,
    long reftableChunkedReads,
    long otherInlineReads,
    long otherChunkedReads,
    long missingReads) {

  /** Empty metrics snapshot. */
  public static final PackFileReadMetrics ZERO =
      new PackFileReadMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0);

  /** @return successful database fallback reads across all extensions and storage modes */
  public long successfulReads() {
    return packInlineReads
        + packChunkedReads
        + indexInlineReads
        + indexChunkedReads
        + reftableInlineReads
        + reftableChunkedReads
        + otherInlineReads
        + otherChunkedReads;
  }

  /** @return every database fallback lookup, including missing rows */
  public long totalLookups() {
    return successfulReads() + missingReads;
  }

  /**
   * Calculate the non-negative difference from an earlier monotone snapshot.
   *
   * @param earlier earlier snapshot from the same repository instance
   * @return counter delta
   */
  public PackFileReadMetrics minus(PackFileReadMetrics earlier) {
    return new PackFileReadMetrics(
        difference(packInlineReads, earlier.packInlineReads, "packInlineReads"),
        difference(packChunkedReads, earlier.packChunkedReads, "packChunkedReads"),
        difference(indexInlineReads, earlier.indexInlineReads, "indexInlineReads"),
        difference(indexChunkedReads, earlier.indexChunkedReads, "indexChunkedReads"),
        difference(reftableInlineReads, earlier.reftableInlineReads, "reftableInlineReads"),
        difference(reftableChunkedReads, earlier.reftableChunkedReads, "reftableChunkedReads"),
        difference(otherInlineReads, earlier.otherInlineReads, "otherInlineReads"),
        difference(otherChunkedReads, earlier.otherChunkedReads, "otherChunkedReads"),
        difference(missingReads, earlier.missingReads, "missingReads"));
  }

  private static long difference(long current, long earlier, String counter) {
    long result = current - earlier;
    if (result < 0) {
      throw new IllegalArgumentException(counter + " is not a monotone snapshot");
    }
    return result;
  }
}
