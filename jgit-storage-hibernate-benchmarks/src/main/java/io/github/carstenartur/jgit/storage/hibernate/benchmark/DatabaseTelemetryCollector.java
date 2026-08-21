/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

/** Captures database-native benchmark telemetry outside the timed operation. */
interface DatabaseTelemetryCollector extends AutoCloseable {

  /** Whether this collector performs database queries. */
  boolean enabled();

  /** Capture one best-effort snapshot. Unsupported capabilities remain explicit. */
  DatabaseTelemetrySnapshot capture();

  @Override
  default void close() {
    // Most collectors open one short-lived connection per snapshot.
  }
}
