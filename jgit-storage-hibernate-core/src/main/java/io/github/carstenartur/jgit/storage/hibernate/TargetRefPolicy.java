/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

/** Policy used when publishing transferred source object IDs as target refs. */
public enum TargetRefPolicy {
  /** Create a ref only when it does not already exist; an identical existing value is a no-op. */
  CREATE_ONLY,

  /** Update an existing commit-valued ref only when the source tip is a fast-forward. */
  FAST_FORWARD_ONLY,

  /**
   * Require the exact expected target value and a fast-forward commit update.
   *
   * <p>An already-published desired value is accepted as an idempotent retry even when the original
   * expected value is now stale.
   */
  COMPARE_AND_SET,

  /**
   * Explicitly permit a non-fast-forward update.
   *
   * <p>An optional expected target value still provides stale-writer protection.
   */
  FORCE
}
