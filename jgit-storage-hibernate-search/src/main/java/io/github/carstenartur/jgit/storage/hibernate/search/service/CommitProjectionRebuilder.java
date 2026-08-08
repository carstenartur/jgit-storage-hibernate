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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
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
 * <p>Both deletion and indexing are bounded. Purge pages prevent the persistence context from
 * growing with repository history, while indexing batches reuse one Git walk/object reader and one
 * transaction for multiple commit projections.
 *
 * <p>Callers must stop concurrent projection writers for the repository while a rebuild is active.
 */
public final class CommitProjectionRebuilder {

  /** Maximum projections removed in one bounded ORM/Search transaction. */
  public static final String PURGE_BATCH_SIZE_PROPERTY =
      "jgit.storage.hibernate.search.purge_batch_size";

  /** Default purge page; deliberately larger than the indexing batch. */
  public static final int DEFAULT_PURGE_BATCH_SIZE = 250;

  private final SessionFactory sessionFactory;
  private final int purgeBatchSize;

  /**
   * Create a projection rebuild service.
   *
   * @param sessionFactory application-managed persistence context containing Search entities
   */
  public CommitProjectionRebuilder(SessionFactory sessionFactory) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    purgeBatchSize = resolvePurgeBatchSize(sessionFactory);
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
   * each committed projection and for the terminal completed, failed or interrupted state. A batch
   * commits atomically before its per-commit progress events are published; consequently an
   * interruption raised by a progress listener may leave the complete current batch committed. The
   * terminal event always reports the committed count, and the next rebuild deterministically purges
   * that partial state.
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

      List<RevCommit> pending = new ArrayList<>(indexer.batchSize());
      try (RevWalk walk = new RevWalk(repository);
          ObjectReader reader = repository.newObjectReader()) {
        walk.sort(RevSort.TOPO);
        walk.sort(RevSort.REVERSE, true);
        for (ObjectId refTip : commitTips) {
          walk.markStart(walk.parseCommit(refTip));
        }
        for (RevCommit commit : walk) {
          requireNotInterrupted();
          pending.add(commit);
          visitedCommits++;
          if (pending.size() == indexer.batchSize()) {
            int batchStartVisited = visitedCommits - pending.size();
            int batchStartIndexed = indexedCommits;
            int committed = indexer.indexKnownMissingBatch(reader, walk, pending);
            requireCompleteBatch(committed, pending.size());
            indexedCommits += committed;
            for (int index = 0; index < pending.size(); index++) {
              currentObjectId = pending.get(index).name();
              publish(
                  listener,
                  progress(
                      name,
                      RebuildState.INDEXING,
                      refTips,
                      batchStartVisited + index + 1,
                      batchStartIndexed + index + 1,
                      skippedCommits,
                      removedProjections,
                      currentObjectId,
                      null));
              requireNotInterrupted();
            }
            pending.clear();
          }
        }
        if (!pending.isEmpty()) {
          int batchStartVisited = visitedCommits - pending.size();
          int batchStartIndexed = indexedCommits;
          int committed = indexer.indexKnownMissingBatch(reader, walk, pending);
          requireCompleteBatch(committed, pending.size());
          indexedCommits += committed;
          for (int index = 0; index < pending.size(); index++) {
            currentObjectId = pending.get(index).name();
            publish(
                listener,
                progress(
                    name,
                    RebuildState.INDEXING,
                    refTips,
                    batchStartVisited + index + 1,
                    batchStartIndexed + index + 1,
                    skippedCommits,
                    removedProjections,
                    currentObjectId,
                    null));
            requireNotInterrupted();
          }
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

  private int removeExisting(RepositoryName repositoryName) throws InterruptedIOException {
    int removed = 0;
    while (true) {
      requireNotInterrupted();
      int pageSize;
      try (Session session = sessionFactory.openSession()) {
        Transaction transaction = session.beginTransaction();
        try {
          List<GitCommitIndex> projections =
              session
                  .createQuery(
                      "FROM GitCommitIndex c WHERE c.repositoryName = :repo ORDER BY c.id",
                      GitCommitIndex.class)
                  .setParameter("repo", repositoryName.value())
                  .setMaxResults(purgeBatchSize)
                  .getResultList();
          pageSize = projections.size();
          if (pageSize > 0) {
            session.setJdbcBatchSize(pageSize);
            projections.forEach(session::remove);
            session.flush();
          }
          transaction.commit();
        } catch (RuntimeException exception) {
          transaction.rollback();
          throw exception;
        }
      }
      removed += pageSize;
      if (pageSize < purgeBatchSize) {
        return removed;
      }
    }
  }

  private static int resolvePurgeBatchSize(SessionFactory sessionFactory) {
    Object configured = sessionFactory.getProperties().get(PURGE_BATCH_SIZE_PROPERTY);
    if (configured == null || configured.toString().isBlank()) {
      return DEFAULT_PURGE_BATCH_SIZE;
    }
    int value;
    try {
      value = Integer.parseInt(configured.toString().trim());
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          PURGE_BATCH_SIZE_PROPERTY + " must be an integer but was '" + configured + "'",
          exception);
    }
    if (value <= 0 || value > CommitIndexer.MAX_INDEX_BATCH_SIZE) {
      throw new IllegalArgumentException(
          PURGE_BATCH_SIZE_PROPERTY
              + " must be between 1 and "
              + CommitIndexer.MAX_INDEX_BATCH_SIZE
              + " but was "
              + value);
    }
    return value;
  }

  private static void requireCompleteBatch(int committed, int expected) throws IOException {
    if (committed != expected) {
      throw new IOException(
          "Projection rebuild expected " + expected + " new commits but indexed " + committed);
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
