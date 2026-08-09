/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.search.engine.reporting.EntityIndexingFailureContext;
import org.hibernate.search.engine.reporting.FailureContext;
import org.hibernate.search.engine.reporting.FailureHandler;

/**
 * Fail-observable background failure handler used by the asynchronous Search runtime benchmark.
 *
 * <p>Hibernate Search cannot propagate every background failure to the submitting thread. The
 * benchmark therefore installs this handler for every scenario and asserts it before accepting an
 * invocation. Production async operation must likewise configure a real failure handler instead of
 * relying on timing results alone.
 */
public final class RuntimeBenchmarkFailureHandler implements FailureHandler {

  private static final AtomicReference<Throwable> FIRST_FAILURE = new AtomicReference<>();

  public RuntimeBenchmarkFailureHandler() {}

  static void reset() {
    FIRST_FAILURE.set(null);
  }

  static void assertNoFailure() {
    Throwable failure = FIRST_FAILURE.get();
    if (failure != null) {
      throw new IllegalStateException("Hibernate Search background benchmark operation failed", failure);
    }
  }

  @Override
  public void handle(FailureContext context) {
    FIRST_FAILURE.compareAndSet(null, context.throwable());
  }

  @Override
  public void handle(EntityIndexingFailureContext context) {
    FIRST_FAILURE.compareAndSet(null, context.throwable());
  }
}
