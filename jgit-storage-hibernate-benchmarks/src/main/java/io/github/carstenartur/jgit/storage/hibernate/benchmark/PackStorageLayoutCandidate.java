/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

/**
 * One benchmark-only pack storage layout candidate.
 *
 * <p>The production storage format remains unchanged while candidates are measured. In particular,
 * the current one-MiB chunk size and 256-KiB inline threshold stay authoritative for ordinary
 * repositories. This value object makes the retained writer budget and byte-based read-ahead window
 * explicit without introducing an unversioned production format change.
 */
public record PackStorageLayoutCandidate(
    int chunkBytes,
    int inlineThresholdBytes,
    long retainedPayloadBudgetBytes,
    int readAheadBytes) {

  public static final int MIN_CHUNK_BYTES = 256 * 1024;
  public static final int MAX_CHUNK_BYTES = 4 * 1024 * 1024;
  public static final int MIN_INLINE_BYTES = 64 * 1024;
  public static final int MAX_INLINE_BYTES = 1024 * 1024;
  public static final long MIN_RETAINED_BUDGET_BYTES = 8L * 1024L * 1024L;
  public static final long MAX_RETAINED_BUDGET_BYTES = 32L * 1024L * 1024L;
  public static final int MAX_READ_AHEAD_BYTES = 16 * 1024 * 1024;

  /** Validate the bounded powers-of-two matrix requested by issue #188. */
  public PackStorageLayoutCandidate {
    requirePowerOfTwoRange("chunkBytes", chunkBytes, MIN_CHUNK_BYTES, MAX_CHUNK_BYTES);
    requirePowerOfTwoRange(
        "inlineThresholdBytes", inlineThresholdBytes, MIN_INLINE_BYTES, MAX_INLINE_BYTES);
    if (retainedPayloadBudgetBytes < MIN_RETAINED_BUDGET_BYTES
        || retainedPayloadBudgetBytes > MAX_RETAINED_BUDGET_BYTES) {
      throw new IllegalArgumentException(
          "retainedPayloadBudgetBytes must be between "
              + MIN_RETAINED_BUDGET_BYTES
              + " and "
              + MAX_RETAINED_BUDGET_BYTES
              + " but was "
              + retainedPayloadBudgetBytes);
    }
    if (retainedPayloadBudgetBytes < chunkBytes) {
      throw new IllegalArgumentException(
          "retainedPayloadBudgetBytes must retain at least one complete chunk");
    }
    requirePowerOfTwoRange("readAheadBytes", readAheadBytes, MIN_CHUNK_BYTES, MAX_READ_AHEAD_BYTES);
  }

  /** Return whether a payload uses the benchmark candidate's inline representation. */
  public boolean inline(long payloadBytes) {
    requirePayload(payloadBytes);
    return payloadBytes <= inlineThresholdBytes;
  }

  /** Return the number of chunk rows required by a non-inline payload. */
  public int chunkCount(long payloadBytes) {
    requirePayload(payloadBytes);
    if (inline(payloadBytes)) {
      return 0;
    }
    return Math.toIntExact(ceilDiv(payloadBytes, chunkBytes));
  }

  /** Return complete chunks retained in one writer batch under the byte budget. */
  public int chunksPerBatch() {
    return Math.toIntExact(retainedPayloadBudgetBytes / chunkBytes);
  }

  /** Return the actual retained bytes after rounding the budget down to complete chunks. */
  public long retainedChunkBytes() {
    return Math.multiplyExact((long) chunksPerBatch(), chunkBytes);
  }

  /** Return chunks required to cover a byte-based read-ahead request. */
  public int readAheadChunks() {
    return Math.toIntExact(ceilDiv(readAheadBytes, chunkBytes));
  }

  /** Return the persisted layout version represented by the benchmark candidate. */
  public int proposedLayoutVersion() {
    return chunkBytes == 1024 * 1024 && inlineThresholdBytes == 256 * 1024 ? 1 : 2;
  }

  private static long ceilDiv(long value, long divisor) {
    return 1L + (value - 1L) / divisor;
  }

  private static void requirePayload(long payloadBytes) {
    if (payloadBytes < 1) {
      throw new IllegalArgumentException("payloadBytes must be positive");
    }
  }

  private static void requirePowerOfTwoRange(String name, int value, int minimum, int maximum) {
    if (value < minimum || value > maximum || Integer.bitCount(value) != 1) {
      throw new IllegalArgumentException(
          name
              + " must be a power of two between "
              + minimum
              + " and "
              + maximum
              + " but was "
              + value);
    }
  }
}
