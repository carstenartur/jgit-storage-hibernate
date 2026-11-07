/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import org.eclipse.jgit.lib.Constants;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ConsumerTaxonomyLifecycleTest {
  @Test
  void sqlAndLucenePathOperationsReturnTheSameVerifiedFixtureHits() throws Exception {
    var fixture = new ConsumerShapeBenchmark.Taxonomy();
    fixture.coordinate = 100;
    try {
      fixture.setup();
      var benchmark = new ConsumerShapeBenchmark();
      var indexed = new ConsumerShapeBenchmark.Counters();
      var structured = new ConsumerShapeBenchmark.Counters();
      fixture.clear();
      int indexedHits = benchmark.taxonomyExactPathSummary(fixture, indexed);
      fixture.clear();
      int structuredHits = benchmark.taxonomySqlPathSummary(fixture, structured);
      assertTrue(indexedHits > 0);
      assertEquals(indexedHits, structuredHits);
      assertTrue(structured.preparedStatements > indexed.preparedStatements);
    } finally {
      fixture.close();
    }
  }

  @Test
  void interruptedProjectionIsRecoveredAfterReopeningWithoutChangingTheOtherWorkspace() throws Exception {
    var fixture = new ConsumerShapeBenchmark.Taxonomy();
    fixture.coordinate = 1000;
    fixture.persistentIndex = true;
    try {
      fixture.setup();
      var originalFactory = fixture.sessionFactory();
      var recovery = fixture.verifyInterruptedRebuild();
      assertFalse(originalFactory.isOpen(), "Recovery must close the interrupted provider");
      assertEquals(100, recovery.original());
      assertEquals(50, recovery.partial());
      assertEquals(50, recovery.partialAfterRestart());
      assertEquals(100, recovery.recovered());
      assertEquals(100, new GitHistorySearchService(fixture.sessionFactory()).countIndexedCommits("taxonomy-1"));
    } finally {
      fixture.close();
    }
  }

  @Test
  void failedHistorySetupRemovesTheFilesystemIndex() throws Exception {
    var fixture = new ConsumerShapeBenchmark.Taxonomy();
    fixture.coordinate = 0;
    fixture.persistentIndex = true;
    assertThrows(IllegalArgumentException.class, fixture::setup);
    assertFalse(Files.exists(fixture.indexDirectory()));
    fixture.close();
  }

  @Test
  void reopeningClosesTheOldFactoryAndPreservesGitAndTheFilesystemProjection() throws Exception {
    var fixture = new ConsumerShapeBenchmark.Taxonomy();
    fixture.coordinate = 1000;
    fixture.persistentIndex = true;
    Path index = null;
    try {
      fixture.setup();
      index = fixture.indexDirectory();
      var oldFactory = fixture.sessionFactory();
      var oldSearch = new GitHistorySearchService(oldFactory);
      var oldRepository = fixture.repository(0);
      var head = oldRepository.resolve(Constants.HEAD);
      var hits = oldSearch.searchCommitTextSummaries("taxonomy-0", "terminology", 100);
      assertEquals(100, oldSearch.countIndexedCommits("taxonomy-0"));
      assertEquals(100, oldSearch.countIndexedCommits("taxonomy-1"));

      fixture.restart();

      assertFalse(oldFactory.isOpen());
      assertNotSame(oldFactory, fixture.sessionFactory());
      var reopenedSearch = new GitHistorySearchService(fixture.sessionFactory());
      assertEquals(head, fixture.repository(0).resolve(Constants.HEAD));
      assertEquals(100, reopenedSearch.countIndexedCommits("taxonomy-0"));
      assertEquals(100, reopenedSearch.countIndexedCommits("taxonomy-1"));
      assertEquals(hits, reopenedSearch.searchCommitTextSummaries("taxonomy-0", "terminology", 100));
    } finally {
      fixture.close();
    }
    assertFalse(Files.exists(index));
  }
}
