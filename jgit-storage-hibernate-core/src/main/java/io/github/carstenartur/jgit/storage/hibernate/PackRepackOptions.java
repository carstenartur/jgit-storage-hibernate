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
import java.util.Objects;

/** Configuration for a JGit DFS garbage-collection and repack maintenance run. */
public record PackRepackOptions(
    boolean singlePack,
    boolean buildBitmaps,
    boolean writeCommitGraph,
    boolean writeBloomFilter,
    boolean compactReftables,
    Duration garbageTtl,
    long coalesceGarbageLimitBytes) {

  private static final long DEFAULT_COALESCE_GARBAGE_LIMIT = 50L << 20;

  /** Validate the immutable maintenance configuration. */
  public PackRepackOptions {
    Objects.requireNonNull(garbageTtl, "garbageTtl");
    if (garbageTtl.isNegative()) {
      throw new IllegalArgumentException("garbageTtl must not be negative");
    }
    if (coalesceGarbageLimitBytes < 0) {
      throw new IllegalArgumentException("coalesceGarbageLimitBytes must not be negative");
    }
    if (writeBloomFilter && !writeCommitGraph) {
      throw new IllegalArgumentException("Bloom filters require commit-graph generation");
    }
  }

  /**
   * Return a configuration optimized for repeated clone, fetch, history and path-history reads.
   *
   * <p>The maintenance run performs the expensive graph traversal outside request processing and
   * creates one primary GC pack with bitmaps, a commit graph and changed-path Bloom filters.
   * Reftables are compacted in the same logical publication.
   *
   * @return read-optimized defaults
   */
  public static PackRepackOptions optimizedForReads() {
    return new PackRepackOptions(
        true,
        true,
        true,
        true,
        true,
        Duration.ofDays(1),
        DEFAULT_COALESCE_GARBAGE_LIMIT);
  }

  /**
   * Return conservative portable defaults that compact packs and reftables without auxiliary graph
   * indexes.
   *
   * @return conservative defaults
   */
  public static PackRepackOptions compactOnly() {
    return new PackRepackOptions(
        true,
        false,
        false,
        false,
        true,
        Duration.ofDays(1),
        DEFAULT_COALESCE_GARBAGE_LIMIT);
  }
}
