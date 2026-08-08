/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search;

import org.hibernate.SessionEventListener;

/** Per-thread JDBC execution counter for Search batching integration tests. */
public final class SearchJdbcBatchEventListener implements SessionEventListener {

  private static final ThreadLocal<Counters> COUNTERS =
      ThreadLocal.withInitial(Counters::new);

  @Override
  public void jdbcExecuteBatchStart() {
    COUNTERS.get().batches++;
  }

  @Override
  public void jdbcExecuteStatementStart() {
    COUNTERS.get().statements++;
  }

  static void reset() {
    COUNTERS.get().batches = 0;
    COUNTERS.get().statements = 0;
  }

  static Snapshot snapshot() {
    Counters counters = COUNTERS.get();
    return new Snapshot(counters.batches, counters.statements);
  }

  record Snapshot(long batches, long statements) {}

  private static final class Counters {
    private long batches;
    private long statements;
  }
}
