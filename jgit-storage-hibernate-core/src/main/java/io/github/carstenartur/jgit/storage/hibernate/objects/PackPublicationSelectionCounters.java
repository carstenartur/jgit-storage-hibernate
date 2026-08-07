/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import io.github.carstenartur.jgit.storage.hibernate.transaction.PackPublicationSelectionMetrics;
import java.util.concurrent.atomic.LongAdder;

/** Repository-instance counters for the adaptive logical-pack publication selector. */
final class PackPublicationSelectionCounters {

  private static final PackPublicationSelectionCounters DISABLED =
      new PackPublicationSelectionCounters(false);

  private final LongAdder directSelections;
  private final LongAdder prePersistedSelections;
  private final LongAdder directStagedPayloadBytes;
  private final LongAdder prePersistedStagedPayloadBytes;

  private PackPublicationSelectionCounters(boolean enabled) {
    directSelections = enabled ? new LongAdder() : null;
    prePersistedSelections = enabled ? new LongAdder() : null;
    directStagedPayloadBytes = enabled ? new LongAdder() : null;
    prePersistedStagedPayloadBytes = enabled ? new LongAdder() : null;
  }

  static PackPublicationSelectionCounters from(StorageByteCounters storageByteCounters) {
    return storageByteCounters.enabled()
        ? new PackPublicationSelectionCounters(true)
        : DISABLED;
  }

  void record(boolean prePersisted, long stagedPayloadBytes) {
    if (directSelections == null) {
      return;
    }
    if (stagedPayloadBytes < 0) {
      throw new IllegalArgumentException("stagedPayloadBytes must not be negative");
    }
    if (prePersisted) {
      prePersistedSelections.increment();
      prePersistedStagedPayloadBytes.add(stagedPayloadBytes);
    } else {
      directSelections.increment();
      directStagedPayloadBytes.add(stagedPayloadBytes);
    }
  }

  PackPublicationSelectionMetrics snapshot() {
    if (directSelections == null) {
      return PackPublicationSelectionMetrics.ZERO;
    }
    return new PackPublicationSelectionMetrics(
        directSelections.sum(),
        prePersistedSelections.sum(),
        directStagedPayloadBytes.sum(),
        prePersistedStagedPayloadBytes.sum());
  }
}
