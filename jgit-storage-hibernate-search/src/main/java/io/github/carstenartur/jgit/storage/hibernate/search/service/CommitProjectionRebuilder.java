/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search.service;

import io.github.carstenartur.jgit.storage.hibernate.RepositoryName;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevWalk;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

/**
 * Rebuilds the generic commit projection from authoritative Git history.
 *
 * <p>The service first removes every existing projection for the logical repository, including its
 * Hibernate Search/Lucene document, then indexes every commit reachable from a ref. A failed or
 * interrupted run may leave a partial derived projection; invoking the service again removes that
 * partial state and starts a deterministic rebuild. Git objects and refs are never modified.
 *
 * <p>Callers must stop concurrent projection writers for the repository while a rebuild is active.
 */
public final class CommitProjectionRebuilder {

  private final SessionFactory sessionFactory;

  /**
   * Create a projection rebuild service.
   *
   * @param sessionFactory application-managed persistence context containing Search entities
   */
  public CommitProjectionRebuilder(SessionFactory sessionFactory) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
  }

  /**
   * Remove the current projection and rebuild all commits reachable from repository refs.
   *
   * @param repository authoritative JGit repository
   * @param repositoryName logical repository identity used by the persistence projection
   * @return deterministic rebuild evidence
   * @throws IOException if refs or commits cannot be read, or the thread is interrupted
   */
  public RebuildResult rebuild(Repository repository, RepositoryName repositoryName)
      throws IOException {
    return rebuild(repository, repositoryName, RebuildProgressListener.NONE);
  }

  /**
   * Rebuild with machine-readable lifecycle and failure events.
   *
   * <p>The listener is invoked before destructive projection clearing, after ref discovery, after
   * each indexed commit and for the terminal completed, failed or interrupted state. Failure events
   * contain the exception type and message; the original exception is still thrown to preserve normal
   * Java error handling.
   *
   * @param repository authoritative JGit repository
   * @param repositoryName logical repository identity used by the persistence projection
   * @param listener progress listener used by maintenance commands and operational adapters
   * @return deterministic completed rebuild evidence
   * @throws IOException if refs or commits cannot be read, or the thread is interrupted
   */
  public RebuildResult rebuild(
      Repository repository,
      RepositoryName repositoryName,
      RebuildProgressListener listener)
      throws IOException {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(repositoryName, "repositoryName");
    Objects.requireNonNull(listener, "listener");

    String name = repositoryName.value();
    int removedProjections = 0;
    int refTips = 0;
    int visitedCommits = 0;
    int indexedCommits = 0;
    int skippedCommits = 0;
    String currentObjectId = null;

    publish(
        listener,
        progress(
            name,
            RebuildState.CLEARING,
            refTips,
            visitedCommits,
            indexedCommits,
            skippedCommits,
            removedProjections,
            currentObjectId,
            null));

    try {
      requireNotInterrupted();
      removedProjections = removeExisting(repositoryName);

      publish(
          listener,
          progress(
              name,
              RebuildState.DISCOVERING,
              refTips,
              visitedCommits,
              indexedCommits,
              skippedCommits,
              removedProjections,
              currentObjectId,
              null));

      Set<ObjectId> commitTips = resolveCommitTips(repository);
      refTips = commitTips.size();
      CommitIndexer indexer = new CommitIndexer(sessionFactory, name);

      publish(
          listener,
          progress(
              name,
              RebuildState.INDEXING,
              refTips,
              visitedCommits,
              indexedCommits,
              skippedCommits,
              removedProjections,
              currentObjectId,
              null));

      try (RevWalk walk = new RevWalk(repository)) {
        walk.sort(RevSort.TOPO);
        walk.sort(RevSort.REVERSE, true);
        for (ObjectId refTip : commitTips) {
          walk.markStart(walk.parseCommit(refTip));
        }
        for (RevCommit commit : walk) {
          requireNotInterrupted();
          currentObjectId = commit.name();
          visitedCommits++;
          indexer.indexCommit(repository, commit);
          indexedCommits++;
          publish(
              listener,
              progress(
                  name,
                  RebuildState.INDEXING,
                  refTips,
                  visitedCommits,
                  indexedCommits,
                  skippedCommits,
                  removedProjections,
                  currentObjectId,
                  null));
        }
      }

      currentObjectId = null;
      RebuildResult result =
          new RebuildResult(
              name,
              RebuildState.COMPLETED,
              refTips,
              visitedCommits,
              indexedCommits,
              skippedCommits,
              removedProjections);
      publish(
          listener,
          progress(
              name,
              result.state(),
              result.refTips(),
              result.visitedCommits(),
              result.indexedCommits(),
              result.skippedCommits(),
              result.removedProjections(),
              currentObjectId,
              null));
      return result;
    } catch (InterruptedIOException exception) {
      publishFailure(
          listener,
          progress(
              name,
              RebuildState.INTERRUPTED,
              refTips,
              visitedCommits,
              indexedCommits,
              skippedCommits,
              removedProjections,
              currentObjectId,
              exception),
          exception);
      throw exception;
    } catch (IOException | RuntimeException exception) {
      publishFailure(
          listener,
          progress(
              name,
              RebuildState.FAILED,
              refTips,
              visitedCommits,
              indexedCommits,
              skippedCommits,
              removedProjections,
              currentObjectId,
              exception),
          exception);
      throw exception;
    }
  }

  private int removeExisting(RepositoryName repositoryName) {
    try (Session session = sessionFactory.openSession()) {
      Transaction transaction = session.beginTransaction();
      try {
        List<GitCommitIndex> projections =
            session
                .createQuery(
                    "FROM GitCommitIndex c WHERE c.repositoryName = :repo",
                    GitCommitIndex.class)
                .setParameter("repo", repositoryName.value())
                .getResultList();
        projections.forEach(session::remove);
        transaction.commit();
        return projections.size();
      } catch (RuntimeException exception) {
        transaction.rollback();
        throw exception;
      }
    }
  }

  private static Set<ObjectId> resolveCommitTips(Repository repository) throws IOException {
    Set<ObjectId> tips = new LinkedHashSet<>();
    for (Ref ref : repository.getRefDatabase().getRefsByPrefix(Constants.R_REFS)) {
      ObjectId commitId = repository.resolve(ref.getName() + "^{commit}");
      if (commitId != null) {
        tips.add(commitId);
      }
    }
    return tips;
  }

  private static void requireNotInterrupted() throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted()) {
      throw new InterruptedIOException("Commit projection rebuild was interrupted");
    }
  }

  private static RebuildProgress progress(
      String repositoryName,
      RebuildState state,
      int refTips,
      int visitedCommits,
      int indexedCommits,
      int skippedCommits,
      int removedProjections,
      String currentObjectId,
      Throwable failure) {
    return new RebuildProgress(
        repositoryName,
        state,
        refTips,
        visitedCommits,
        indexedCommits,
        skippedCommits,
        removedProjections,
        currentObjectId,
        failure == null ? null : failure.getClass().getName(),
        failure == null ? null : failure.getMessage());
  }

  private static void publish(RebuildProgressListener listener, RebuildProgress progress) {
    listener.onProgress(progress);
  }

  private static void publishFailure(
      RebuildProgressListener listener, RebuildProgress progress, Throwable originalFailure) {
    try {
      listener.onProgress(progress);
    } catch (RuntimeException listenerFailure) {
      originalFailure.addSuppressed(listenerFailure);
    }
  }

  /** Lifecycle state of a projection rebuild. */
  public enum RebuildState {
    CLEARING,
    DISCOVERING,
    INDEXING,
    COMPLETED,
    FAILED,
    INTERRUPTED
  }

  /** Immutable progress or terminal failure evidence for maintenance tooling. */
  public record RebuildProgress(
      String repositoryName,
      RebuildState state,
      int refTips,
      int visitedCommits,
      int indexedCommits,
      int skippedCommits,
      int removedProjections,
      String currentObjectId,
      String failureType,
      String failureMessage) {}

  /** Receives deterministic rebuild lifecycle events. */
  @FunctionalInterface
  public interface RebuildProgressListener {

    RebuildProgressListener NONE = ignored -> {};

    void onProgress(RebuildProgress progress);
  }

  /** Machine-readable evidence for one completed projection rebuild. */
  public record RebuildResult(
      String repositoryName,
      RebuildState state,
      int refTips,
      int visitedCommits,
      int indexedCommits,
      int skippedCommits,
      int removedProjections) {}
}
