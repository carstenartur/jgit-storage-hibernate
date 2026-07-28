/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

/** Result of deleting expired, uncommitted pack payloads. */
public record PackCleanupResult(long packRows, long chunkRows, long payloadBytes) {

  public PackCleanupResult {
    if (packRows < 0 || chunkRows < 0 || payloadBytes < 0) {
      throw new IllegalArgumentException("cleanup counts must not be negative");
    }
  }

  /** Return whether any abandoned persistence state was deleted. */
  public boolean deletedAnything() {
    return packRows > 0 || chunkRows > 0;
  }
}
