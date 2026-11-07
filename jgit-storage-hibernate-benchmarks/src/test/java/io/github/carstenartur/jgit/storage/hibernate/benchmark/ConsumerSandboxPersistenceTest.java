/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaSymbolIndex;
import java.nio.file.Files;
import java.util.Set;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;

class ConsumerSandboxPersistenceTest {
  @Test
  void bodyEditAndFullRebuildProduceTheSameDurableCurrentFileProjection() throws Exception {
    var fixture = new ConsumerSandboxIndexBenchmark.Projection();
    fixture.coordinate = 10;
    fixture.setup();
    var root = fixture.root();
    try {
      fixture.prepareBefore();
      var incremental = new ConsumerSandboxIndexBenchmark.Counters();
      new ConsumerSandboxIndexBenchmark().sandboxPersistBodyEdit(fixture, incremental);
      assertEquals(1, incremental.javaFilesAnalyzed);
      assertTrue(incremental.indexBytes > 0);
      assertEquals(fixture.expectedHeadFacts(), fixture.currentFacts());
      assertEquals(Set.of(fixture.beforeCommit(), fixture.headCommit()), fixture.originCommits());
      assertEquals(11, fixture.analysisRuns());
      var symbols = fixture.pathSymbols();
      var references = fixture.methodReferences();
      assertFalse(symbols.isEmpty());
      assertFalse(references.isEmpty());
      assertTrue(references.stream().allMatch(hit -> hit.getCommitId().equals(fixture.beforeCommit())));

      var previousFactory = fixture.sessionFactory();
      fixture.reopen();
      assertTrue(previousFactory.isClosed());
      assertEquals(fixture.expectedHeadFacts(), fixture.currentFacts());
      assertEquals(symbols.size(), fixture.pathSymbols().size());
      assertEquals(references.size(), fixture.methodReferences().size());

      fixture.prepareBefore();
      var full = new ConsumerSandboxIndexBenchmark.Counters();
      new ConsumerSandboxIndexBenchmark().sandboxRebuildSemanticIndex(fixture, full);
      assertEquals(11, full.javaFilesAnalyzed);
      assertEquals(fixture.expectedHeadFacts(), fixture.currentFacts());
      assertEquals(Set.of(fixture.headCommit()), fixture.originCommits());
      assertEquals(11, fixture.analysisRuns());
    } finally {
      fixture.close();
    }
    assertFalse(Files.exists(root));
  }

  @Test
  void failedPathReplacementRollsBackRowsAndSearchDocumentsBeforeReopen() throws Exception {
    var fixture = new ConsumerSandboxIndexBenchmark.Projection();
    fixture.coordinate = 2;
    fixture.setup();
    try {
      fixture.prepareBefore();
      var original = fixture.currentFacts();
      var symbols = fixture.pathSymbols().size();
      var replacement = fixture.analyzeChanged();
      replacement.symbols().getFirst().setSimpleName(null);
      assertThrows(RuntimeException.class, () -> fixture.replaceChanged(replacement));
      assertEquals(original, fixture.currentFacts());
      assertEquals(symbols, fixture.pathSymbols().size());
      assertEquals(3, fixture.analysisRuns());
      fixture.reopen();
      assertEquals(original, fixture.currentFacts());
      assertEquals(symbols, fixture.pathSymbols().size());
    } finally {
      fixture.close();
    }
  }

  @Test
  void rejectsAnIndexedHitWhoseBlobDoesNotBelongToTheCurrentGitPath() throws Exception {
    var fixture = new ConsumerSandboxIndexBenchmark.Projection();
    fixture.coordinate = 2;
    fixture.setup();
    try {
      fixture.prepareBefore();
      long id = fixture.pathSymbols().getFirst().getId();
      fixture.sessionFactory().inTransaction(session -> {
        session.find(JavaSymbolIndex.class, id).setBlobId(ObjectId.zeroId().name());
      });
      assertThrows(IllegalStateException.class, fixture::pathSymbols);
    } finally {
      fixture.close();
    }
  }
}
