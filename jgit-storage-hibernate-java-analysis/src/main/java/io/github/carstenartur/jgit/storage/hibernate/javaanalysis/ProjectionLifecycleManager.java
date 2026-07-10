/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaProjectionState;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** In-memory lifecycle manager for Java semantic projection states. */
public final class ProjectionLifecycleManager {

  private final List<JavaProjectionState> states;
  private final Map<StateKey, JavaProjectionState> byKey;

  public ProjectionLifecycleManager(List<JavaProjectionState> states) {
    this.states = Objects.requireNonNull(states, "states");
    this.byKey = new LinkedHashMap<>();
    for (JavaProjectionState state : states) {
      byKey.put(StateKey.of(state), state);
    }
  }

  public JavaProjectionState getOrCreate(
      String repositoryName, String branchName, String commitId, String projectionVersion) {
    StateKey key = new StateKey(repositoryName, branchName, commitId, projectionVersion);
    JavaProjectionState existing = byKey.get(key);
    if (existing != null) {
      return existing;
    }
    JavaProjectionState state = new JavaProjectionState();
    state.setRepositoryName(repositoryName);
    state.setBranchName(branchName);
    state.setCommitId(commitId);
    state.setProjectionVersion(projectionVersion);
    state.setStatus(ProjectionStatus.MISSING);
    state.setIndexedFiles(0);
    state.setFailedFiles(0);
    state.setSymbolCount(0);
    state.setReferenceCount(0);
    states.add(state);
    byKey.put(key, state);
    return state;
  }

  public void startIndexing(JavaProjectionState state) {
    Objects.requireNonNull(state, "state");
    ProjectionStatus status = state.getStatus();
    if (status == ProjectionStatus.RUNNING || status == ProjectionStatus.CURRENT) {
      throw new IllegalStateException("Cannot start indexing from status " + status);
    }
    if (status != ProjectionStatus.MISSING
        && status != ProjectionStatus.STALE
        && status != ProjectionStatus.FAILED) {
      throw new IllegalStateException("Cannot start indexing from status " + status);
    }
    state.setStatus(ProjectionStatus.RUNNING);
    state.setStartedAt(Instant.now());
    state.setCompletedAt(null);
    state.setDiagnosticSummary(null);
  }

  public void completeIndexing(
      JavaProjectionState state,
      int indexedFiles,
      int failedFiles,
      int symbolCount,
      int referenceCount) {
    Objects.requireNonNull(state, "state");
    if (state.getStatus() != ProjectionStatus.RUNNING) {
      throw new IllegalStateException("Cannot complete indexing from status " + state.getStatus());
    }
    state.setIndexedFiles(indexedFiles);
    state.setFailedFiles(failedFiles);
    state.setSymbolCount(symbolCount);
    state.setReferenceCount(referenceCount);
    state.setStatus(failedFiles == 0 ? ProjectionStatus.CURRENT : ProjectionStatus.PARTIAL);
    state.setCompletedAt(Instant.now());
  }

  public void markFailed(JavaProjectionState state, String diagnosticSummary) {
    Objects.requireNonNull(state, "state");
    if (state.getStatus() != ProjectionStatus.RUNNING) {
      throw new IllegalStateException("Cannot fail indexing from status " + state.getStatus());
    }
    state.setStatus(ProjectionStatus.FAILED);
    state.setCompletedAt(Instant.now());
    state.setDiagnosticSummary(diagnosticSummary);
  }

  public void advanceBranch(
      String repositoryName, String branchName, String newCommitId, String projectionVersion) {
    for (JavaProjectionState state : states) {
      if (!Objects.equals(repositoryName, state.getRepositoryName())
          || !Objects.equals(branchName, state.getBranchName())) {
        continue;
      }
      if (state.getStatus() != ProjectionStatus.CURRENT && state.getStatus() != ProjectionStatus.PARTIAL) {
        continue;
      }
      if (!Objects.equals(newCommitId, state.getCommitId())
          || !Objects.equals(projectionVersion, state.getProjectionVersion())) {
        state.setStatus(ProjectionStatus.STALE);
      }
    }
    getOrCreate(repositoryName, branchName, newCommitId, projectionVersion);
  }

  public List<JavaProjectionState> findStale(String repositoryName) {
    return states.stream()
        .filter(state -> Objects.equals(repositoryName, state.getRepositoryName()))
        .filter(state -> state.getStatus() == ProjectionStatus.STALE)
        .toList();
  }

  public boolean isFullyCurrent(
      String repositoryName, String branchName, String commitId, String projectionVersion) {
    return states.stream().anyMatch(state -> Objects.equals(repositoryName, state.getRepositoryName())
        && Objects.equals(branchName, state.getBranchName())
        && Objects.equals(commitId, state.getCommitId())
        && Objects.equals(projectionVersion, state.getProjectionVersion())
        && state.getStatus() == ProjectionStatus.CURRENT);
  }

  public void ensureIdempotent(JavaProjectionState state) {
    Objects.requireNonNull(state, "state");
    if (state.getStatus() == ProjectionStatus.CURRENT) {
      return;
    }
  }

  private record StateKey(
      String repositoryName,
      String branchName,
      String commitId,
      String projectionVersion) {
    private static StateKey of(JavaProjectionState state) {
      return new StateKey(
          state.getRepositoryName(),
          state.getBranchName(),
          state.getCommitId(),
          state.getProjectionVersion());
    }
  }
}
