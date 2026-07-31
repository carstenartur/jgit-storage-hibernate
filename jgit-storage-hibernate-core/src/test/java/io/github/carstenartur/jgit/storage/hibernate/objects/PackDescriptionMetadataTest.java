/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.junit.jupiter.api.Test;

class PackDescriptionMetadataTest {

  @Test
  void capturesEveryPersistedDescriptionField() {
    DfsPackDescription description =
        new DfsPackDescription(new DfsRepositoryDescription("metadata"), "pack-test", PackSource.GC)
            .setLastModified(123456789L)
            .setObjectCount(120)
            .setDeltaCount(35)
            .setIndexVersion(2)
            .setMinUpdateIndex(41)
            .setMaxUpdateIndex(97);

    assertEquals(
        new PackDescriptionMetadata(PackSource.GC, 123456789L, 120, 35, 2, 41, 97),
        PackDescriptionMetadata.fromDescription(description, Instant.EPOCH));
  }

  @Test
  void usesCommitTimeWhenJGitDidNotAssignALastModifiedValue() {
    Instant committedAt = Instant.parse("2026-07-31T17:00:00Z");
    DfsPackDescription description =
        new DfsPackDescription(
            new DfsRepositoryDescription("metadata"), "pack-test", PackSource.RECEIVE);

    PackDescriptionMetadata metadata =
        PackDescriptionMetadata.fromDescription(description, committedAt);

    assertEquals(committedAt.toEpochMilli(), metadata.lastModified());
    assertEquals(PackSource.RECEIVE, metadata.packSource());
  }

  @Test
  void reconstructsLegacyNullColumnsFromCommittedTimestamp() {
    Instant committedAt = Instant.parse("2025-01-02T03:04:05Z");

    PackDescriptionMetadata metadata =
        PackDescriptionMetadata.fromStoredValues(
            null, null, null, null, null, null, null, committedAt, Instant.EPOCH);

    assertEquals(PackSource.INSERT, metadata.packSource());
    assertEquals(committedAt.toEpochMilli(), metadata.lastModified());
    assertEquals(0, metadata.objectCount());
    assertEquals(0, metadata.deltaCount());
    assertEquals(0, metadata.indexVersion());
    assertEquals(0, metadata.minUpdateIndex());
    assertEquals(0, metadata.maxUpdateIndex());
  }

  @Test
  void toleratesAnUnknownFuturePackSource() {
    PackDescriptionMetadata metadata =
        PackDescriptionMetadata.fromStoredValues(
            "FUTURE_SOURCE", 1L, 2L, 3L, 4, 5L, 6L, null, null);

    assertEquals(PackSource.INSERT, metadata.packSource());
    assertEquals(1L, metadata.lastModified());
  }
}
