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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CommitHistoryQueryTest {

  @Test
  void normalizesOptionalFullTextWithoutChangingExistingLimitSemantics() {
    CommitHistoryQuery query =
        CommitHistoryQuery.forRepository("  repository  ")
            .matchingText("   ")
            .authoredBy("  author@example.com  ")
            .limit(0)
            .build();

    assertEquals("repository", query.repositoryName());
    assertNull(query.text());
    assertEquals("author@example.com", query.authorEmail());
    assertEquals(0, query.limit());
  }

  @Test
  void rejectsAnInvertedCompoundTimeRange() {
    Instant earlier = Instant.parse("2026-01-01T00:00:00Z");
    Instant later = Instant.parse("2026-01-02T00:00:00Z");

    assertThrows(
        IllegalArgumentException.class,
        () ->
            CommitHistoryQuery.forRepository("repository")
                .matchingText("history")
                .between(later, earlier)
                .build());
  }
}
