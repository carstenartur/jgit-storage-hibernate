/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PackStorageLayoutCandidateTest {

  @Test
  void requestedMatrixKeepsRetainedPayloadBudgetsBounded() {
    for (int chunkKiB : new int[] {256, 1024, 2048, 4096}) {
      for (int inlineKiB : new int[] {64, 256, 1024}) {
        for (int retainedMiB : new int[] {8, 16, 32}) {
          PackStorageLayoutCandidate candidate =
              candidate(chunkKiB, inlineKiB, retainedMiB, 1024);
          assertTrue(candidate.chunksPerBatch() >= 1);
          assertTrue(candidate.retainedChunkBytes() <= candidate.retainedPayloadBudgetBytes());
          assertTrue(
              candidate.retainedPayloadBudgetBytes() - candidate.retainedChunkBytes()
                  < candidate.chunkBytes());
        }
      }
    }
  }

  @Test
  void inlineAndChunkCountsCoverBoundaryPayloads() {
    PackStorageLayoutCandidate current = candidate(1024, 256, 16, 1024);

    assertTrue(current.inline(64L * 1024L));
    assertTrue(current.inline(256L * 1024L));
    assertFalse(current.inline(256L * 1024L + 1L));
    assertEquals(0, current.chunkCount(256L * 1024L));
    assertEquals(1, current.chunkCount(1024L * 1024L));
    assertEquals(17, current.chunkCount(16L * 1024L * 1024L + 1L));
    assertEquals(16, current.chunksPerBatch());
    assertEquals(1, current.proposedLayoutVersion());
  }

  @Test
  void byteBasedReadAheadIsIndependentOfTheChunkCountDefault() {
    assertEquals(16, candidate(256, 256, 8, 4096).readAheadChunks());
    assertEquals(4, candidate(1024, 256, 8, 4096).readAheadChunks());
    assertEquals(1, candidate(4096, 256, 8, 4096).readAheadChunks());
  }

  @Test
  void nonLegacyCandidateRequiresAProposedVersionedLayout() {
    assertEquals(2, candidate(4096, 256, 16, 4096).proposedLayoutVersion());
    assertEquals(2, candidate(1024, 1024, 16, 1024).proposedLayoutVersion());
  }

  @Test
  void invalidAndUnboundedCandidatesAreRejected() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new PackStorageLayoutCandidate(768 * 1024, 256 * 1024, 16L << 20, 1 << 20));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PackStorageLayoutCandidate(1 << 20, 32 * 1024, 16L << 20, 1 << 20));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PackStorageLayoutCandidate(4 << 20, 256 * 1024, 2L << 20, 1 << 20));
    assertThrows(
        IllegalArgumentException.class,
        () -> new PackStorageLayoutCandidate(1 << 20, 256 * 1024, 16L << 20, 32 << 20));
    assertThrows(IllegalArgumentException.class, () -> candidate(1024, 256, 16, 1024).inline(0));
  }

  private static PackStorageLayoutCandidate candidate(
      int chunkKiB, int inlineKiB, int retainedMiB, int readAheadKiB) {
    return new PackStorageLayoutCandidate(
        chunkKiB * 1024,
        inlineKiB * 1024,
        (long) retainedMiB * 1024L * 1024L,
        readAheadKiB * 1024);
  }
}
