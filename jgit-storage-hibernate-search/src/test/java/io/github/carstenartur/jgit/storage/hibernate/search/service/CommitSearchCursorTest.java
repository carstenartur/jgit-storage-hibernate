/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class CommitSearchCursorTest {

  @ParameterizedTest
  @MethodSource("fetchAndCloseFailures")
  void fetchFailureRemainsPrimaryWhenCleanupAlsoFails(Throwable fetchFailure, Throwable closeFailure) {
    var source = new FailingSource(fetchFailure, closeFailure);
    var cursor = new CommitSearchCursor(source);

    Throwable actual = assertThrows(fetchFailure.getClass(), cursor::nextChunk);

    assertSame(fetchFailure, actual);
    assertEquals(List.of(closeFailure), List.of(actual.getSuppressed()));
    assertEquals(1, source.fetches);
    assertReleased(cursor, source);
  }

  @ParameterizedTest
  @MethodSource("closeFailures")
  void cancellationRemainsPrimaryWhenCleanupAlsoFails(Throwable closeFailure) {
    var source = new FailingSource(null, closeFailure);
    var cursor = new CommitSearchCursor(source);

    try {
      Thread.currentThread().interrupt();
      CancellationException actual = assertThrows(CancellationException.class, cursor::nextChunk);

      assertEquals(List.of(closeFailure), List.of(actual.getSuppressed()));
      assertTrue(Thread.currentThread().isInterrupted());
      assertEquals(0, source.fetches, "An interrupted cursor must close before fetching");
      assertReleased(cursor, source);
    } finally {
      Thread.interrupted();
      cursor.close();
    }
  }

  @ParameterizedTest
  @MethodSource("closeFailures")
  void sameFetchAndCleanupFailureDoesNotReplaceOriginalWithSelfSuppression(Throwable failure) {
    var source = new FailingSource(failure, failure);
    var cursor = new CommitSearchCursor(source);

    Throwable actual = assertThrows(failure.getClass(), cursor::nextChunk);

    assertSame(failure, actual);
    assertEquals(0, actual.getSuppressed().length);
    assertReleased(cursor, source);
  }

  @ParameterizedTest
  @MethodSource("closeFailures")
  void exhaustionPropagatesCleanupFailureAndStillReleasesCursor(Throwable closeFailure) {
    var source = new FailingSource(null, closeFailure);
    var cursor = new CommitSearchCursor(source);

    Throwable actual = assertThrows(closeFailure.getClass(), cursor::nextChunk);

    assertSame(closeFailure, actual);
    assertEquals(0, actual.getSuppressed().length);
    assertReleased(cursor, source);
  }

  @ParameterizedTest
  @MethodSource("closeFailures")
  void explicitClosePropagatesCleanupFailureAndStillReleasesCursor(Throwable closeFailure) {
    var source = new FailingSource(null, closeFailure);
    var cursor = new CommitSearchCursor(source);

    assertSame(closeFailure, assertThrows(closeFailure.getClass(), cursor::close));
    assertEquals(0, source.fetches);
    assertReleased(cursor, source);
  }

  private static Stream<Arguments> fetchAndCloseFailures() {
    return Stream.of(
        Arguments.of(new IllegalStateException("fetch"), new IllegalStateException("close")),
        Arguments.of(new IllegalStateException("fetch"), new AssertionError("close")),
        Arguments.of(new AssertionError("fetch"), new IllegalStateException("close")),
        Arguments.of(new AssertionError("fetch"), new AssertionError("close")));
  }

  private static Stream<Throwable> closeFailures() {
    return Stream.of(new IllegalStateException("close"), new AssertionError("close"));
  }

  private static void assertReleased(CommitSearchCursor cursor, FailingSource source) {
    assertTrue(cursor.isClosed());
    cursor.close();
    assertEquals(1, source.closes, "Cleanup must be attempted exactly once");
    assertThrows(IllegalStateException.class, cursor::nextChunk);
  }

  private static final class FailingSource implements CommitSearchCursor.Source {
    private final Throwable fetchFailure;
    private final Throwable closeFailure;
    private int fetches;
    private int closes;

    FailingSource(Throwable fetchFailure, Throwable closeFailure) {
      this.fetchFailure = fetchFailure;
      this.closeFailure = closeFailure;
    }

    @Override
    public List<CommitSearchHit> nextChunk() {
      fetches++;
      throwUnchecked(fetchFailure);
      return List.of();
    }

    @Override
    public void close() {
      closes++;
      throwUnchecked(closeFailure);
    }

    private static void throwUnchecked(Throwable failure) {
      if (failure instanceof RuntimeException runtimeFailure) {
        throw runtimeFailure;
      }
      if (failure instanceof Error error) {
        throw error;
      }
    }
  }
}
