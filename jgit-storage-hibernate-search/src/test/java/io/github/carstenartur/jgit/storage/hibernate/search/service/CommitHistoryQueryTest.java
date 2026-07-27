/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
    assertEquals(CommitHistoryQuery.TimestampField.COMMITTER, query.timestampField());
    assertFalse(query.hasObjectIdRestriction());
    assertEquals(List.of(), query.objectIds());
    assertEquals(0, query.limit());
  }

  @Test
  void selectsAuthorOrCommitterTimeExplicitly() {
    Instant from = Instant.parse("2026-01-01T00:00:00Z");
    Instant to = Instant.parse("2026-01-31T23:59:59Z");

    CommitHistoryQuery authorQuery =
        CommitHistoryQuery.forRepository("repository").authoredBetween(from, to).build();
    assertEquals(CommitHistoryQuery.TimestampField.AUTHOR, authorQuery.timestampField());
    assertEquals(from, authorQuery.from());
    assertEquals(to, authorQuery.to());

    CommitHistoryQuery committerQuery =
        CommitHistoryQuery.forRepository("repository").committedBetween(from, to).build();
    assertEquals(CommitHistoryQuery.TimestampField.COMMITTER, committerQuery.timestampField());
  }

  @Test
  void preservesExplicitEmptyCandidatesAndDefensivelyCopiesDistinctIds() {
    CommitHistoryQuery empty =
        CommitHistoryQuery.forRepository("repository")
            .restrictedToObjectIds(List.of())
            .build();
    assertTrue(empty.hasObjectIdRestriction());
    assertEquals(List.of(), empty.objectIds());

    List<String> supplied = new ArrayList<>(List.of(" commit-a ", "commit-b", "commit-a"));
    CommitHistoryQuery restricted =
        CommitHistoryQuery.forRepository("repository").restrictedToObjectIds(supplied).build();
    supplied.clear();

    assertTrue(restricted.hasObjectIdRestriction());
    assertEquals(List.of("commit-a", "commit-b"), restricted.objectIds());
    assertThrows(
        UnsupportedOperationException.class, () -> restricted.objectIds().add("commit-c"));
  }

  @Test
  void rejectsBlankCandidateIdsAndAnInvertedCompoundTimeRange() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CommitHistoryQuery.forRepository("repository")
                .restrictedToObjectIds(List.of(" "))
                .build());

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
