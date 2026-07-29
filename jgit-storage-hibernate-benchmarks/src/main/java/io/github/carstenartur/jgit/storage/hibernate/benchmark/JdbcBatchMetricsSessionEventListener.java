/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import org.hibernate.SessionEventListener;

/** Per-thread low-level JDBC event counter used only by the benchmark module. */
public final class JdbcBatchMetricsSessionEventListener implements SessionEventListener {

  private static final ThreadLocal<MutableCounters> COUNTERS =
      ThreadLocal.withInitial(MutableCounters::new);

  @Override
  public void jdbcExecuteBatchStart() {
    COUNTERS.get().batchExecutions++;
  }

  @Override
  public void jdbcExecuteStatementStart() {
    COUNTERS.get().statementExecutions++;
  }

  static void resetCurrentThread() {
    COUNTERS.get().reset();
  }

  static Snapshot snapshotCurrentThread() {
    MutableCounters counters = COUNTERS.get();
    return new Snapshot(counters.batchExecutions, counters.statementExecutions);
  }

  record Snapshot(long batchExecutions, long statementExecutions) {}

  private static final class MutableCounters {
    private long batchExecutions;
    private long statementExecutions;

    private void reset() {
      batchExecutions = 0;
      statementExecutions = 0;
    }
  }
}
