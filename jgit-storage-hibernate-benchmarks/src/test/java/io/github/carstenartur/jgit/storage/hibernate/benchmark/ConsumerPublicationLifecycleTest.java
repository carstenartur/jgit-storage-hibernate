/* Copyright (C) 2026, Carsten Hammer and contributors. SPDX-License-Identifier: BSD-3-Clause */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConsumerPublicationLifecycleTest {
  @Test
  void nativeDurabilityRequiresEveryObservedSettingOn() {
    var settings = Map.of("fsync", "on", "synchronous_commit", "on", "full_page_writes", "on");
    assertDoesNotThrow(() -> ConsumerPublicationStore.requireDurability(settings));
    for (String key : settings.keySet()) {
      var off = new HashMap<>(settings);
      off.put(key, "off");
      assertThrows(IllegalStateException.class, () -> ConsumerPublicationStore.requireDurability(off), key);
      var missing = new HashMap<>(settings);
      missing.remove(key);
      assertThrows(IllegalStateException.class, () -> ConsumerPublicationStore.requireDurability(missing), key);
    }
  }

  @Test
  void serialAndConcurrentPublicationHaveIdenticalGitRefsAndCompletePersistentProjections()
      throws Exception {
    List<ConsumerPublicationBenchmark.Verification> verified = new ArrayList<>();
    for (int workers : List.of(1, 4)) {
      try (var fixture = new ConsumerPublicationBenchmark.Taxonomy()) {
        fixture.coordinate = 64;
        fixture.parallelism = workers;
        fixture.setup();
        var before = fixture.verify();
        assertEquals(64, before.gitCommits());
        fixture.begin();
        var counters = new ConsumerPublicationBenchmark.Counters();
        assertEquals(4, new ConsumerPublicationBenchmark().taxonomyPublishDelta(fixture, counters));
        assertEquals(4, counters.workspacesPublished);
        assertEquals(4, counters.commitsIndexed);
        assertTrue(counters.preparedStatements > 0);
        var published = fixture.verify();
        assertEquals(68, published.gitCommits());
        assertEquals(68, published.sqlCommits());
        assertEquals(68, published.indexedCommits());
        assertNotEquals(before.heads(), published.heads());
        assertEquals(workers, fixture.lastWorkerCount());
        fixture.restart();
        assertEquals(published, fixture.verify());
        verified.add(published);
        fixture.begin();
        new ConsumerPublicationBenchmark().taxonomyPublishDelta(fixture, counters);
        assertEquals(72, fixture.verify().gitCommits());
      }
    }
    assertEquals(verified.get(0), verified.get(1), "Only scheduling may differ between worker counts");
  }

  @Test
  void verificationRejectsMissingSqlProjectionEvenWhenGitAndLuceneStillContainIt()
      throws Exception {
    try (var fixture = new ConsumerPublicationBenchmark.Taxonomy()) {
      fixture.coordinate = 32;
      fixture.setup();
      try (var session = fixture.sessionFactory().openSession()) {
        var tx = session.beginTransaction();
        session.createMutationQuery("delete from GitCommitIndex where repositoryName = :name")
            .setParameter("name", "publication-taxonomy-0").executeUpdate();
        tx.commit();
      }
      assertThrows(IllegalStateException.class, fixture::verify);
    }
  }

  @Test
  void verificationRejectsWrongProjectionContentEvenWithTheCorrectObjectIds() throws Exception {
    try (var fixture = new ConsumerPublicationBenchmark.Taxonomy()) {
      fixture.coordinate = 32;
      fixture.setup();
      fixture.begin();
      new ConsumerPublicationBenchmark().taxonomyPublishDelta(fixture, new ConsumerPublicationBenchmark.Counters());
      String head = fixture.verify().heads().getFirst();
      try (var session = fixture.sessionFactory().openSession()) {
        var tx = session.beginTransaction();
        session.createMutationQuery("update GitCommitIndex set fullMessage = :message where repositoryName = :name and objectId = :id")
            .setParameter("message", "incorrect projection")
            .setParameter("name", "publication-taxonomy-0").setParameter("id", head).executeUpdate();
        tx.commit();
      }
      assertThrows(IllegalStateException.class, fixture::verify);
    }
  }

  @Test
  void audioPublicationPreservesBothBranchesPayloadAndReflogAfterRestartWithoutSearch()
      throws Exception {
    try (var fixture = new ConsumerPublicationBenchmark.Audio()) {
      fixture.coordinate = 4096;
      fixture.analysisMiB = 1;
      fixture.setup();
      assertFalse(fixture.sessionFactory().getMetamodel().getEntities().stream()
          .anyMatch(entity -> entity.getName().equals("GitCommitIndex")));
      fixture.begin();
      var counters = new ConsumerPublicationBenchmark.Counters();
      assertEquals(4096, new ConsumerPublicationBenchmark().audioPublishWithWal(fixture, counters));
      assertEquals(4096, counters.payloadBytes);
      assertTrue(counters.databaseBytesWritten >= 4096);
      assertEquals(1024 * 1024, counters.retainedAnalysisBytes);
      var published = fixture.verify();
      assertEquals(4, published.gitCommits());
      assertEquals(2, published.heads().size());
      fixture.restart();
      assertEquals(published, fixture.verify());
    }
  }
}
