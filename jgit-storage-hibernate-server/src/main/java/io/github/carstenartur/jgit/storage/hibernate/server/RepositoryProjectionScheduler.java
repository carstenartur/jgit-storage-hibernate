/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.server;

import io.github.carstenartur.jgit.storage.hibernate.HibernateGitStorage;
import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitProjectionRebuilder.RebuildResult;
import io.github.carstenartur.jgit.storage.hibernate.spring.JgitRepositoryService;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.core.task.TaskExecutor;

/** Coalesces post-push Search rebuilds per repository on a bounded Spring executor. */
public final class RepositoryProjectionScheduler {

  /** Observable lifecycle of the rebuildable history projection. */
  public enum State {
    IDLE,
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED
  }

  /** Immutable indexing status returned by the administration API. */
  public record IndexStatus(
      String repositoryName,
      State state,
      Instant requestedAt,
      Instant startedAt,
      Instant completedAt,
      int indexedCommits,
      String message) {}

  private final JgitRepositoryService repositories;
  private final CommitProjectionRebuilder rebuilder;
  private final TaskExecutor executor;
  private final ConcurrentMap<String, ProjectionState> states = new ConcurrentHashMap<>();

  public RepositoryProjectionScheduler(
      JgitRepositoryService repositories,
      CommitProjectionRebuilder rebuilder,
      TaskExecutor executor) {
    this.repositories = Objects.requireNonNull(repositories, "repositories");
    this.rebuilder = Objects.requireNonNull(rebuilder, "rebuilder");
    this.executor = Objects.requireNonNull(executor, "executor");
  }

  /** Schedule or coalesce one complete projection rebuild. */
  public IndexStatus schedule(String repositoryName) {
    RepositoryName name = JgitRepositoryService.requireSafeName(repositoryName);
    ProjectionState projection =
        states.computeIfAbsent(name.value(), ignored -> new ProjectionState(name.value()));
    Instant now = Instant.now();
    projection.dirty.set(true);
    projection.status =
        new IndexStatus(
            name.value(),
            State.QUEUED,
            now,
            projection.status.startedAt(),
            projection.status.completedAt(),
            projection.status.indexedCommits(),
            "Projection rebuild queued");
    startIfNecessary(projection);
    return projection.status;
  }

  /** Return the current status without starting any work. */
  public IndexStatus status(String repositoryName) {
    RepositoryName name = JgitRepositoryService.requireSafeName(repositoryName);
    ProjectionState projection = states.get(name.value());
    return projection == null ? ProjectionState.idle(name.value()) : projection.status;
  }

  private void startIfNecessary(ProjectionState projection) {
    if (!projection.running.compareAndSet(false, true)) {
      return;
    }
    try {
      executor.execute(() -> runLoop(projection));
    } catch (RuntimeException rejected) {
      projection.running.set(false);
      projection.status =
          new IndexStatus(
              projection.repositoryName,
              State.FAILED,
              projection.status.requestedAt(),
              projection.status.startedAt(),
              Instant.now(),
              projection.status.indexedCommits(),
              "Projection executor rejected the task: " + rejected.getClass().getSimpleName());
      throw rejected;
    }
  }

  private void runLoop(ProjectionState projection) {
    try {
      do {
        projection.dirty.set(false);
        Instant started = Instant.now();
        projection.status =
            new IndexStatus(
                projection.repositoryName,
                State.RUNNING,
                projection.status.requestedAt(),
                started,
                null,
                projection.status.indexedCommits(),
                "Projection rebuild running");
        try (HibernateGitStorage storage = repositories.open(projection.repositoryName)) {
          RebuildResult result =
              rebuilder.rebuild(
                  storage.repository(), new RepositoryName(projection.repositoryName));
          projection.status =
              new IndexStatus(
                  projection.repositoryName,
                  State.COMPLETED,
                  projection.status.requestedAt(),
                  started,
                  Instant.now(),
                  result.indexedCommits(),
                  "Projection rebuild completed");
        } catch (Exception failure) {
          projection.status =
              new IndexStatus(
                  projection.repositoryName,
                  State.FAILED,
                  projection.status.requestedAt(),
                  started,
                  Instant.now(),
                  projection.status.indexedCommits(),
                  failure.getClass().getSimpleName() + ": " + safeMessage(failure));
        }
      } while (projection.dirty.get());
    } finally {
      projection.running.set(false);
      if (projection.dirty.get()) {
        startIfNecessary(projection);
      }
    }
  }

  private static String safeMessage(Throwable failure) {
    String message = failure.getMessage();
    return message == null || message.isBlank() ? "no detail" : message;
  }

  private static final class ProjectionState {
    private final String repositoryName;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean dirty = new AtomicBoolean();
    private volatile IndexStatus status;

    private ProjectionState(String repositoryName) {
      this.repositoryName = repositoryName;
      status = idle(repositoryName);
    }

    private static IndexStatus idle(String repositoryName) {
      return new IndexStatus(repositoryName, State.IDLE, null, null, null, 0, "Not indexed yet");
    }
  }
}
