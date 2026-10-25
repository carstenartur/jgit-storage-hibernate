/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

/** Unique object IDs in published packs, including objects unreachable from current refs. */
public record StoredObjectStatistics(long commits, long trees, long blobs, long tags) {}
