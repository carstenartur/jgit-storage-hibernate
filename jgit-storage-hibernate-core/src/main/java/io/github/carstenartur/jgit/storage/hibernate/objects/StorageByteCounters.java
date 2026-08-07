/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.objects;

import io.github.carstenartur.jgit.storage.hibernate.transaction.HibernateTransactionContext;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StagingSpillMetrics;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageByteMetrics;
import java.util.concurrent.atomic.LongAdder;
import org.hibernate.SessionFactory;

/** Repository-instance byte counters shared by the staged writer and readable channels. */
final class StorageByteCounters {

  private static final StorageByteCounters DISABLED = new StorageByteCounters(false);

  private final LongAdder temporaryFileBytesWritten;
  private final LongAdder temporaryFileBytesRead;
  private final LongAdder databasePayloadBytesWritten;
  private final LongAdder databasePayloadBytesRead;
  private final LongAdder readAheadBytesFetched;
  private final LongAdder readAheadBytesConsumed;
  private final LongAdder readAheadOverfetchBytes;
  private final LongAdder memoryToFileSpills;
  private final LongAdder spilledPrefixBytes;

  private StorageByteCounters(boolean enabled) {
    temporaryFileBytesWritten = enabled ? new LongAdder() : null;
    temporaryFileBytesRead = enabled ? new LongAdder() : null;
    databasePayloadBytesWritten = enabled ? new LongAdder() : null;
    databasePayloadBytesRead = enabled ? new LongAdder() : null;
    readAheadBytesFetched = enabled ? new LongAdder() : null;
    readAheadBytesConsumed = enabled ? new LongAdder() : null;
    readAheadOverfetchBytes = enabled ? new LongAdder() : null;
    memoryToFileSpills = enabled ? new LongAdder() : null;
    spilledPrefixBytes = enabled ? new LongAdder() : null;
  }

  static StorageByteCounters from(SessionFactory sessionFactory) {
    Object configured =
        sessionFactory.getProperties().get(HibernateTransactionContext.METRICS_ENABLED_PROPERTY);
    boolean enabled = configured != null && Boolean.parseBoolean(configured.toString());
    return enabled ? new StorageByteCounters(true) : DISABLED;
  }

  static StorageByteCounters disabled() {
    return DISABLED;
  }

  boolean enabled() {
    return temporaryFileBytesWritten != null;
  }

  void recordTemporaryFileBytesWritten(long bytes) {
    add(temporaryFileBytesWritten, bytes);
  }

  void recordTemporaryFileBytesRead(long bytes) {
    add(temporaryFileBytesRead, bytes);
  }

  void recordDatabasePayloadBytesWritten(long bytes) {
    add(databasePayloadBytesWritten, bytes);
  }

  void recordDatabasePayloadBytesRead(long bytes) {
    add(databasePayloadBytesRead, bytes);
  }

  void recordReadAheadBytesFetched(long bytes) {
    add(readAheadBytesFetched, bytes);
  }

  void recordReadAheadBytesConsumed(long bytes) {
    add(readAheadBytesConsumed, bytes);
  }

  void recordReadAheadOverfetchBytes(long bytes) {
    add(readAheadOverfetchBytes, bytes);
  }

  void recordMemoryToFileSpill(long prefixBytes) {
    if (memoryToFileSpills == null || prefixBytes == 0) {
      return;
    }
    if (prefixBytes < 0) {
      throw new IllegalArgumentException("prefixBytes must not be negative");
    }
    memoryToFileSpills.increment();
    spilledPrefixBytes.add(prefixBytes);
  }

  StorageByteMetrics snapshot() {
    if (!enabled()) {
      return StorageByteMetrics.ZERO;
    }
    return new StorageByteMetrics(
        temporaryFileBytesWritten.sum(),
        temporaryFileBytesRead.sum(),
        databasePayloadBytesWritten.sum(),
        databasePayloadBytesRead.sum(),
        readAheadBytesFetched.sum(),
        readAheadBytesConsumed.sum(),
        readAheadOverfetchBytes.sum());
  }

  StagingSpillMetrics stagingSpillSnapshot() {
    if (!enabled()) {
      return StagingSpillMetrics.ZERO;
    }
    return new StagingSpillMetrics(memoryToFileSpills.sum(), spilledPrefixBytes.sum());
  }

  private static void add(LongAdder counter, long bytes) {
    if (counter == null || bytes == 0) {
      return;
    }
    if (bytes < 0) {
      throw new IllegalArgumentException("bytes must not be negative");
    }
    counter.add(bytes);
  }
}
