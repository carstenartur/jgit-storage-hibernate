/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaProjectionState;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProjectionLifecycleManagerTest {

  @Test
  void projectionStartsAsMissingAndTransitionsToCurrentOnSuccess() {
    List<JavaProjectionState> store = new ArrayList<>();
    ProjectionLifecycleManager manager = new ProjectionLifecycleManager(store);

    JavaProjectionState state = manager.getOrCreate("repo", "main", "c1", "v1");
    manager.startIndexing(state);
    manager.completeIndexing(state, 4, 0, 12, 7);

    assertEquals(ProjectionStatus.CURRENT, state.getStatus());
    assertEquals(4, state.getIndexedFiles());
    assertEquals(0, state.getFailedFiles());
    assertEquals(12, state.getSymbolCount());
    assertNotNull(state.getStartedAt());
    assertNotNull(state.getCompletedAt());
  }

  @Test
  void partialResultWhenFilesFailedDuringIndexing() {
    ProjectionLifecycleManager manager = new ProjectionLifecycleManager(new ArrayList<>());
    JavaProjectionState state = manager.getOrCreate("repo", "main", "c1", "v1");

    manager.startIndexing(state);
    manager.completeIndexing(state, 4, 1, 9, 4);

    assertEquals(ProjectionStatus.PARTIAL, state.getStatus());
    assertEquals(1, state.getFailedFiles());
  }

  @Test
  void advancingBranchMarksPreviousCommitStateAsStale() {
    List<JavaProjectionState> store = new ArrayList<>();
    ProjectionLifecycleManager manager = new ProjectionLifecycleManager(store);
    JavaProjectionState current = manager.getOrCreate("repo", "main", "c1", "v1");
    manager.startIndexing(current);
    manager.completeIndexing(current, 2, 0, 5, 2);

    manager.advanceBranch("repo", "main", "c2", "v1");

    JavaProjectionState next = manager.getOrCreate("repo", "main", "c2", "v1");
    assertEquals(ProjectionStatus.STALE, current.getStatus());
    assertEquals(ProjectionStatus.MISSING, next.getStatus());
    assertEquals(2, store.size());
  }

  @Test
  void reRunningIdempotentDoesNotDuplicateOrTransitionCurrentState() {
    List<JavaProjectionState> store = new ArrayList<>();
    ProjectionLifecycleManager manager = new ProjectionLifecycleManager(store);
    JavaProjectionState state = manager.getOrCreate("repo", "main", "c1", "v1");
    manager.startIndexing(state);
    manager.completeIndexing(state, 1, 0, 2, 1);

    manager.ensureIdempotent(state);
    JavaProjectionState sameState = manager.getOrCreate("repo", "main", "c1", "v1");

    assertSame(state, sameState);
    assertEquals(ProjectionStatus.CURRENT, state.getStatus());
    assertEquals(1, store.size());
    assertThrows(IllegalStateException.class, () -> manager.startIndexing(state));
  }

  @Test
  void failedProjectionAllowsRestartToRunning() {
    ProjectionLifecycleManager manager = new ProjectionLifecycleManager(new ArrayList<>());
    JavaProjectionState state = manager.getOrCreate("repo", "main", "c1", "v1");
    manager.startIndexing(state);
    manager.markFailed(state, "boom");

    manager.startIndexing(state);

    assertEquals(ProjectionStatus.RUNNING, state.getStatus());
    assertEquals(null, state.getDiagnosticSummary());
  }

  @Test
  void analyzerVersionChangeResultsInNewMissingStateAlongsideExistingStale() {
    List<JavaProjectionState> store = new ArrayList<>();
    ProjectionLifecycleManager manager = new ProjectionLifecycleManager(store);
    JavaProjectionState current = manager.getOrCreate("repo", "main", "c1", "v1");
    manager.startIndexing(current);
    manager.completeIndexing(current, 2, 0, 4, 2);

    manager.advanceBranch("repo", "main", "c1", "v2");

    JavaProjectionState replacement = manager.getOrCreate("repo", "main", "c1", "v2");
    assertEquals(ProjectionStatus.STALE, current.getStatus());
    assertEquals(ProjectionStatus.MISSING, replacement.getStatus());
    assertEquals(2, store.size());
  }

  @Test
  void findStaleReturnsOnlyStaleStatesForRepository() {
    List<JavaProjectionState> store = new ArrayList<>();
    ProjectionLifecycleManager manager = new ProjectionLifecycleManager(store);
    JavaProjectionState stale = state("repo", "main", "c1", "v1", ProjectionStatus.STALE);
    JavaProjectionState current = state("repo", "main", "c2", "v1", ProjectionStatus.CURRENT);
    JavaProjectionState otherRepo = state("other", "main", "c3", "v1", ProjectionStatus.STALE);
    store.add(stale);
    store.add(current);
    store.add(otherRepo);
    manager = new ProjectionLifecycleManager(store);

    List<JavaProjectionState> staleStates = manager.findStale("repo");

    assertEquals(1, staleStates.size());
    assertSame(stale, staleStates.getFirst());
    assertTrue(manager.isFullyCurrent("repo", "main", "c2", "v1"));
  }

  private static JavaProjectionState state(
      String repositoryName,
      String branchName,
      String commitId,
      String version,
      ProjectionStatus status) {
    JavaProjectionState state = new JavaProjectionState();
    state.setRepositoryName(repositoryName);
    state.setBranchName(branchName);
    state.setCommitId(commitId);
    state.setProjectionVersion(version);
    state.setStatus(status);
    return state;
  }
}
