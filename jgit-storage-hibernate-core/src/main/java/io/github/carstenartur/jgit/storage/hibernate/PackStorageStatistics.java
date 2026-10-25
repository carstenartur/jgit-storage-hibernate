/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate;

/**
 * Physical pack catalog totals: distinct persisted pack names and declared file bytes across
 * all extensions, including Reftables and unpublished staging rows. Database/index overhead
 * and unpersisted local staging files are excluded; chunked payload bytes are counted once.
 */
public record PackStorageStatistics(long packCount, long totalSizeBytes) {}
