/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.carstenartur.jgit.storage.hibernate.HibernateGitStorage;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder.RebuildResult;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder.RebuildState;
import io.github.carstenartur.jgit.storage.hibernate.spring.JgitRepositoryService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jgit.lib.Repository;
import org.junit.jupiter.api.Test;

class RepositoryProjectionSchedulerConcurrencyTest {

  @Test
  void requeuesRequestRaisedWhileProjectionRebuildIsRunning() throws Exception {
    JgitRepositoryService repositories = mock(JgitRepositoryService.class);
    CommitProjectionRebuilder rebuilder = mock(CommitProjectionRebuilder.class);
    HibernateGitStorage storage = mock(HibernateGitStorage.class);
    Repository repository = mock(Repository.class);
    AtomicReference<RepositoryProjectionScheduler> schedulerReference = new AtomicReference<>();
    AtomicInteger rebuilds = new AtomicInteger();

    when(repositories.open("demo")).thenReturn(storage);
    when(storage.repository()).thenReturn(repository);
    when(rebuilder.rebuild(eq(repository), any(RepositoryName.class)))
        .thenAnswer(
            ignored -> {
              int rebuild = rebuilds.incrementAndGet();
              if (rebuild == 1) {
                schedulerReference.get().schedule("demo");
              }
              return new RebuildResult(
                  "demo", RebuildState.COMPLETED, 1, rebuild + 2, rebuild + 2, 0, 0);
            });

    RepositoryProjectionScheduler scheduler =
        new RepositoryProjectionScheduler(repositories, rebuilder, Runnable::run);
    schedulerReference.set(scheduler);

    RepositoryProjectionScheduler.IndexStatus completed = scheduler.schedule("demo");

    assertEquals(RepositoryProjectionScheduler.State.COMPLETED, completed.state());
    assertEquals(4, completed.indexedCommits());
    assertEquals(2, rebuilds.get());
    verify(repositories, times(2)).open("demo");
    verify(rebuilder, times(2)).rebuild(eq(repository), any(RepositoryName.class));
    verify(storage, times(2)).close();
  }
}
