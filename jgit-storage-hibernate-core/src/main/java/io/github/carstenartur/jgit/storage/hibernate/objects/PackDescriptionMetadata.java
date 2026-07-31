/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import io.github.carstenartur.jgit.storage.hibernate.entity.GitPackEntity;
import java.time.Instant;
import java.util.Objects;
import org.eclipse.jgit.internal.storage.dfs.DfsObjDatabase.PackSource;
import org.eclipse.jgit.internal.storage.dfs.DfsPackDescription;

/** Logical JGit metadata shared by every persisted extension of one pack description. */
record PackDescriptionMetadata(
    PackSource packSource,
    long lastModified,
    long objectCount,
    long deltaCount,
    int indexVersion,
    long minUpdateIndex,
    long maxUpdateIndex) {

  PackDescriptionMetadata {
    packSource = Objects.requireNonNull(packSource, "packSource");
    lastModified = Math.max(0, lastModified);
    objectCount = Math.max(0, objectCount);
    deltaCount = Math.max(0, deltaCount);
    indexVersion = Math.max(0, indexVersion);
    minUpdateIndex = Math.max(0, minUpdateIndex);
    maxUpdateIndex = Math.max(0, maxUpdateIndex);
  }

  static PackDescriptionMetadata fromDescription(
      DfsPackDescription description, Instant fallbackTimestamp) {
    Objects.requireNonNull(description, "description");
    long persistedLastModified = description.getLastModified();
    if (persistedLastModified <= 0 && fallbackTimestamp != null) {
      persistedLastModified = fallbackTimestamp.toEpochMilli();
    }
    return new PackDescriptionMetadata(
        description.getPackSource(),
        persistedLastModified,
        description.getObjectCount(),
        description.getDeltaCount(),
        description.getIndexVersion(),
        description.getMinUpdateIndex(),
        description.getMaxUpdateIndex());
  }

  static PackDescriptionMetadata fromStoredValues(
      String storedPackSource,
      Long storedLastModified,
      Long storedObjectCount,
      Long storedDeltaCount,
      Integer storedIndexVersion,
      Long storedMinUpdateIndex,
      Long storedMaxUpdateIndex,
      Instant committedAt,
      Instant createdAt) {
    long reconstructedLastModified = storedLastModified == null ? 0 : storedLastModified;
    if (reconstructedLastModified <= 0) {
      Instant fallbackTimestamp = committedAt != null ? committedAt : createdAt;
      if (fallbackTimestamp != null) {
        reconstructedLastModified = fallbackTimestamp.toEpochMilli();
      }
    }
    return new PackDescriptionMetadata(
        parsePackSource(storedPackSource),
        reconstructedLastModified,
        valueOrZero(storedObjectCount),
        valueOrZero(storedDeltaCount),
        storedIndexVersion == null ? 0 : storedIndexVersion,
        valueOrZero(storedMinUpdateIndex),
        valueOrZero(storedMaxUpdateIndex));
  }

  void applyTo(GitPackEntity entity) {
    entity.setPackSource(packSource.name());
    entity.setLastModified(lastModified);
    entity.setObjectCount(objectCount);
    entity.setDeltaCount(deltaCount);
    entity.setIndexVersion(indexVersion);
    entity.setMinUpdateIndex(minUpdateIndex);
    entity.setMaxUpdateIndex(maxUpdateIndex);
  }

  void applyTo(DfsPackDescription description) {
    description
        .setPackSource(packSource)
        .setLastModified(lastModified)
        .setObjectCount(objectCount)
        .setDeltaCount(deltaCount)
        .setIndexVersion(indexVersion)
        .setMinUpdateIndex(minUpdateIndex)
        .setMaxUpdateIndex(maxUpdateIndex);
  }

  private static long valueOrZero(Long value) {
    return value == null ? 0 : value;
  }

  private static PackSource parsePackSource(String value) {
    if (value == null || value.isBlank()) {
      return PackSource.INSERT;
    }
    try {
      return PackSource.valueOf(value);
    } catch (IllegalArgumentException unknownSource) {
      return PackSource.INSERT;
    }
  }
}
