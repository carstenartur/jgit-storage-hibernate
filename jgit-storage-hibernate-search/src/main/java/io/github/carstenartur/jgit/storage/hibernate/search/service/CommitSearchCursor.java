/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search.service;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Bounded, closeable cursor over compact Git commit-history search hits.
 *
 * <p>Each call to {@link #nextChunk()} returns at most the configured chunk size used to create the
 * cursor. An empty list marks the end of the result set and closes the underlying resources
 * automatically. Callers that stop early must close the cursor, preferably with try-with-resources.
 *
 * <p>The cursor is deliberately single-threaded. Interrupting the consuming thread cancels the
 * cursor before another chunk is fetched and preserves the thread's interrupted status.
 */
public final class CommitSearchCursor implements AutoCloseable {

  interface Source {
    List<CommitSearchHit> nextChunk();

    void close();
  }

  private Source source;

  CommitSearchCursor(Source source) {
    this.source = Objects.requireNonNull(source, "source");
  }

  /**
   * Return the next bounded chunk, or an empty list when the cursor is exhausted.
   *
   * @throws CancellationException when the consuming thread has been interrupted
   * @throws IllegalStateException when called after an explicit close
   */
  public List<CommitSearchHit> nextChunk() {
    Source current = source;
    if (current == null) {
      throw new IllegalStateException("search cursor is closed");
    }
    if (Thread.currentThread().isInterrupted()) {
      close();
      throw new CancellationException("search cursor cancelled by thread interruption");
    }
    try {
      List<CommitSearchHit> hits = List.copyOf(current.nextChunk());
      if (hits.isEmpty()) {
        close();
      }
      return hits;
    } catch (RuntimeException | Error failure) {
      try {
        close();
      } catch (RuntimeException closeFailure) {
        failure.addSuppressed(closeFailure);
      }
      throw failure;
    }
  }

  /** Whether the underlying Search/ORM resources have already been released. */
  public boolean isClosed() {
    return source == null;
  }

  @Override
  public void close() {
    Source current = source;
    if (current == null) {
      return;
    }
    source = null;
    current.close();
  }
}
