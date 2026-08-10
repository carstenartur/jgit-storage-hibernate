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
  /** Create a ref only when it does not already exist. */
  CREATE_ONLY,

  /** Update an existing ref only when the new commit is a fast-forward. */
  FAST_FORWARD_ONLY,

  /** Update a ref only when its current value equals the request's expected value. */
  COMPARE_AND_SET,

  /** Explicitly permit a non-fast-forward update. */
  FORCE
}
