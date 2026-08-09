/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitHistoryQuery;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitSearchCursor;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitSearchHit;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.junit.jupiter.api.Test;

class CommitSearchScrollingH2Test {

  @Test
  void structuredCursorReturnsBoundedChronologicalChunksAndClosesAtEnd() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      persistProjections(provider, 5);
      GitHistorySearchService service = new GitHistorySearchService(provider.getSessionFactory());
      CommitHistoryQuery query =
          CommitHistoryQuery.forRepository("scroll-repository")
              .authoredBy("alice@example.com")
              .unbounded()
              .build();

      List<String> objectIds = new ArrayList<>();
      try (CommitSearchCursor cursor = service.scrollChangeSummaries(query, 2)) {
        List<CommitSearchHit> first = cursor.nextChunk();
        List<CommitSearchHit> second = cursor.nextChunk();
        List<CommitSearchHit> third = cursor.nextChunk();
        assertEquals(2, first.size());
        assertEquals(2, second.size());
        assertEquals(1, third.size());
        first.forEach(hit -> objectIds.add(hit.objectId()));
        second.forEach(hit -> objectIds.add(hit.objectId()));
        third.forEach(hit -> objectIds.add(hit.objectId()));
        assertTrue(cursor.nextChunk().isEmpty());
        assertTrue(cursor.isClosed());
      }

      assertEquals(
          List.of(objectId(5), objectId(4), objectId(3), objectId(2), objectId(1)), objectIds);
    }
  }

  @Test
  void indexedCursorUsesCompactProjectionAndHonorsTotalLimit() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      persistProjections(provider, 5);
      GitHistorySearchService service = new GitHistorySearchService(provider.getSessionFactory());
      CommitHistoryQuery query =
          CommitHistoryQuery.forRepository("scroll-repository")
              .matchingText("needle")
              .limit(3)
              .build();

      try (CommitSearchCursor cursor = service.scrollChangeSummaries(query, 2)) {
        List<CommitSearchHit> first = cursor.nextChunk();
        List<CommitSearchHit> second = cursor.nextChunk();
        assertEquals(2, first.size());
        assertEquals(1, second.size());
        assertEquals(List.of(objectId(5), objectId(4)), ids(first));
        assertEquals(List.of(objectId(3)), ids(second));
        assertTrue(cursor.nextChunk().isEmpty());
      }
    }
  }

  @Test
  void earlyCloseAndInterruptReleaseCursorResources() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      persistProjections(provider, 3);
      GitHistorySearchService service = new GitHistorySearchService(provider.getSessionFactory());
      CommitHistoryQuery query =
          CommitHistoryQuery.forRepository("scroll-repository").matchingText("needle").unbounded().build();

      CommitSearchCursor closed = service.scrollChangeSummaries(query, 2);
      closed.close();
      assertTrue(closed.isClosed());
      assertThrows(IllegalStateException.class, closed::nextChunk);

      CommitSearchCursor interrupted = service.scrollChangeSummaries(query, 2);
      try {
        Thread.currentThread().interrupt();
        assertThrows(CancellationException.class, interrupted::nextChunk);
        assertTrue(Thread.currentThread().isInterrupted());
        assertTrue(interrupted.isClosed());
      } finally {
        Thread.interrupted();
        interrupted.close();
      }
    }
  }

  @Test
  void deepOffsetIsRejectedAndChunkSizeIsBounded() {
    Properties overrides = new Properties();
    overrides.put(GitHistorySearchService.MAX_OFFSET_PROPERTY, "2");
    try (HibernateSessionFactoryProvider provider = provider(overrides)) {
      persistProjections(provider, 5);
      GitHistorySearchService service = new GitHistorySearchService(provider.getSessionFactory());
      assertEquals(2, service.maxOffset());

      CommitHistoryQuery deepPage =
          CommitHistoryQuery.forRepository("scroll-repository").offset(3).limit(1).build();
      IllegalArgumentException deepFailure =
          assertThrows(IllegalArgumentException.class, () -> service.findChangeSummaries(deepPage));
      assertTrue(deepFailure.getMessage().contains("scrollChangeSummaries"));

      CommitHistoryQuery query =
          CommitHistoryQuery.forRepository("scroll-repository").unbounded().build();
      assertThrows(IllegalArgumentException.class, () -> service.scrollChangeSummaries(query, 0));
      assertThrows(
          IllegalArgumentException.class,
          () ->
              service.scrollChangeSummaries(
                  query, GitHistorySearchService.MAX_SCROLL_CHUNK_SIZE + 1));
    }
  }

  private static List<String> ids(List<CommitSearchHit> hits) {
    return hits.stream().map(CommitSearchHit::objectId).toList();
  }

  private static void persistProjections(HibernateSessionFactoryProvider provider, int count) {
    Instant base = Instant.parse("2026-08-09T00:00:00Z");
    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      for (int i = 1; i <= count; i++) {
        GitCommitIndex projection = new GitCommitIndex();
        projection.setRepositoryName("scroll-repository");
        projection.setObjectId(objectId(i));
        projection.setShortMessage("needle change " + i);
        projection.setFullMessage("needle change " + i + "\n\nbody");
        projection.setAuthorName("Alice");
        projection.setAuthorEmail("alice@example.com");
        projection.setAuthorTime(base.plusSeconds(i));
        projection.setCommitterName("Release bot");
        projection.setCommitterEmail("release@example.com");
        projection.setCommitterTime(base.plusSeconds(i));
        projection.setChangedPaths("src/example/Change" + i + ".java");
        projection.setChangedText("needle changed line " + i);
        session.persist(projection);
      }
      transaction.commit();
    }
  }

  private static String objectId(int value) {
    return String.format("%040d", value);
  }

  private static HibernateSessionFactoryProvider provider() {
    return provider(new Properties());
  }

  private static HibernateSessionFactoryProvider provider(Properties overrides) {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:commit-search-scroll-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-heap");
    properties.putAll(overrides);
    return new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses());
  }
}
