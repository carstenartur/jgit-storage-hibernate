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
 * Monotone byte counters for staged and database-backed pack payload traffic.
 *
 * <p>The counters describe bytes that crossed an implementation boundary, not the size of the
 * repository at the time of the snapshot. A byte may therefore appear in several counters when it
 * is written to a temporary file, read back for persistence and transferred to the database.
 * Read-ahead consumption counts each byte at most once per cached window. Unconsumed bytes become
 * overfetch when that window is evicted or the channel is closed.
 *
 * <p>Metrics are disabled by default together with the other storage diagnostics. Disabled
 * repositories return {@link #ZERO}.
 *
 * @param temporaryFileBytesWritten bytes physically appended to pack-extension staging files
 * @param temporaryFileBytesRead bytes physically read from staging files, including JGit positional
 *     reads and persistence reads
 * @param databasePayloadBytesWritten inline and chunk payload bytes committed by successful payload
 *     transactions
 * @param databasePayloadBytesRead inline and chunk payload byte arrays materialized from database
 *     queries
 * @param readAheadBytesFetched chunk bytes loaded into bounded channel-local read-ahead windows
 * @param readAheadBytesConsumed distinct fetched bytes copied to callers before their window was
 *     evicted
 * @param readAheadOverfetchBytes fetched bytes discarded without being copied to a caller
 */
public record StorageByteMetrics(
    long temporaryFileBytesWritten,
    long temporaryFileBytesRead,
    long databasePayloadBytesWritten,
    long databasePayloadBytesRead,
    long readAheadBytesFetched,
    long readAheadBytesConsumed,
    long readAheadOverfetchBytes) {

  /** Empty metrics snapshot. */
  public static final StorageByteMetrics ZERO = new StorageByteMetrics(0, 0, 0, 0, 0, 0, 0);

  /** @return physical temporary-file traffic in both directions */
  public long temporaryFileIoBytes() {
    return Math.addExact(temporaryFileBytesWritten, temporaryFileBytesRead);
  }

  /** @return database payload traffic in both directions */
  public long databasePayloadIoBytes() {
    return Math.addExact(databasePayloadBytesWritten, databasePayloadBytesRead);
  }

  /**
   * Calculate the non-negative difference from an earlier monotone snapshot.
   *
   * @param earlier earlier snapshot from the same repository instance
   * @return counter delta
   */
  public StorageByteMetrics minus(StorageByteMetrics earlier) {
    return new StorageByteMetrics(
        difference(
            temporaryFileBytesWritten,
            earlier.temporaryFileBytesWritten,
            "temporaryFileBytesWritten"),
        difference(
            temporaryFileBytesRead,
            earlier.temporaryFileBytesRead,
            "temporaryFileBytesRead"),
        difference(
            databasePayloadBytesWritten,
            earlier.databasePayloadBytesWritten,
            "databasePayloadBytesWritten"),
        difference(
            databasePayloadBytesRead,
            earlier.databasePayloadBytesRead,
            "databasePayloadBytesRead"),
        difference(
            readAheadBytesFetched, earlier.readAheadBytesFetched, "readAheadBytesFetched"),
        difference(
            readAheadBytesConsumed, earlier.readAheadBytesConsumed, "readAheadBytesConsumed"),
        difference(
            readAheadOverfetchBytes,
            earlier.readAheadOverfetchBytes,
            "readAheadOverfetchBytes"));
  }

  private static long difference(long current, long earlier, String counter) {
    long result = current - earlier;
    if (result < 0) {
      throw new IllegalArgumentException(counter + " is not a monotone snapshot");
    }
    return result;
  }
}
