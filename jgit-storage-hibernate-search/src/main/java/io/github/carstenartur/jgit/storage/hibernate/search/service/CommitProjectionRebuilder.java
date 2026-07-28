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
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(repositoryName, "repositoryName");

    int removedProjections = removeExisting(repositoryName);
    Set<ObjectId> refTips = resolveCommitTips(repository);
    CommitIndexer indexer = new CommitIndexer(sessionFactory, repositoryName.value());
    int visitedCommits = 0;
    int indexedCommits = 0;

    try (RevWalk walk = new RevWalk(repository)) {
      walk.sort(RevSort.TOPO);
      walk.sort(RevSort.REVERSE, true);
      for (ObjectId refTip : refTips) {
        walk.markStart(walk.parseCommit(refTip));
      }
      for (RevCommit commit : walk) {
        requireNotInterrupted();
        visitedCommits++;
        indexer.indexCommit(repository, commit);
        indexedCommits++;
      }
    }

    return new RebuildResult(
        repositoryName.value(),
        refTips.size(),
        visitedCommits,
        indexedCommits,
        removedProjections);
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

  /** Machine-readable evidence for one completed projection rebuild. */
  public record RebuildResult(
      String repositoryName,
      int refTips,
      int visitedCommits,
      int indexedCommits,
      int removedProjections) {}
}
