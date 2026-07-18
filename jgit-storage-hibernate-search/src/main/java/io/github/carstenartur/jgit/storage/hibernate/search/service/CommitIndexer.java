/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search.service;

import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

/**
 * Indexes Git commits into a generic Hibernate Search projection.
 *
 * <p>Changed paths and changed text use first-parent diff semantics. Every path in a root commit is
 * considered changed. For merge commits, the projection describes the result relative to the first
 * parent.
 */
public class CommitIndexer {

  private static final int MAX_INDEXED_BLOB_BYTES = 256 * 1024;
  private static final int MAX_CHANGED_TEXT_CHARS = 250_000;
  private static final int CURRENT_TREE = 1;

  private final SessionFactory sessionFactory;
  private final String repositoryName;

  /**
   * Create an indexer.
   *
   * @param sessionFactory Hibernate session factory containing the search entities
   * @param repositoryName logical repository name
   */
  public CommitIndexer(SessionFactory sessionFactory, String repositoryName) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    this.repositoryName = Objects.requireNonNull(repositoryName, "repositoryName");
  }

  /**
   * Index one commit.
   *
   * @param repository repository containing the commit
   * @param commitId commit object id
   * @return indexed or updated projection
   * @throws IOException if the commit cannot be read
   */
  public GitCommitIndex indexCommit(Repository repository, ObjectId commitId) throws IOException {
    try (RevWalk revWalk = new RevWalk(repository)) {
      RevCommit commit = revWalk.parseCommit(commitId);
      GitCommitIndex projection = toProjection(repository, revWalk, commit);
      upsert(projection);
      return projection;
    }
  }

  /**
   * Walk commits reachable from a starting commit and index commits that are not indexed yet.
   *
   * @param repository repository to read
   * @param start starting commit
   * @param limit maximum commits to inspect, or a negative value for no limit
   * @return number of newly indexed commits
   * @throws IOException if the repository cannot be read
   */
  public int indexCommitsFrom(Repository repository, ObjectId start, int limit) throws IOException {
    int indexed = 0;
    int visited = 0;
    try (RevWalk revWalk = new RevWalk(repository)) {
      revWalk.markStart(revWalk.parseCommit(start));
      for (RevCommit commit : revWalk) {
        if (limit >= 0 && visited++ >= limit) {
          break;
        }
        if (findExisting(commit.name()) == null) {
          upsert(toProjection(repository, revWalk, commit));
          indexed++;
        }
      }
    }
    return indexed;
  }

  private GitCommitIndex toProjection(
      Repository repository, RevWalk revWalk, RevCommit commit) throws IOException {
    GitCommitIndex projection = new GitCommitIndex();
    projection.setRepositoryName(repositoryName);
    projection.setObjectId(commit.name());
    projection.setShortMessage(commit.getShortMessage());
    projection.setFullMessage(commit.getFullMessage());
    if (commit.getAuthorIdent() != null) {
      projection.setAuthorName(commit.getAuthorIdent().getName());
      projection.setAuthorEmail(commit.getAuthorIdent().getEmailAddress());
      projection.setCommitTime(commit.getAuthorIdent().getWhenAsInstant());
    }

    TreeText treeText = readChangedTreeText(repository, revWalk, commit);
    projection.setChangedPaths(String.join("\n", treeText.paths()));
    projection.setChangedText(treeText.text());
    return projection;
  }

  private TreeText readChangedTreeText(
      Repository repository, RevWalk revWalk, RevCommit commit) throws IOException {
    List<String> paths = new ArrayList<>();
    StringBuilder text = new StringBuilder();

    try (ObjectReader reader = repository.newObjectReader();
        TreeWalk treeWalk = new TreeWalk(reader)) {
      if (commit.getParentCount() == 0) {
        treeWalk.addTree(new EmptyTreeIterator());
      } else {
        treeWalk.addTree(revWalk.parseCommit(commit.getParent(0)).getTree());
      }
      treeWalk.addTree(commit.getTree());
      treeWalk.setRecursive(true);
      treeWalk.setFilter(TreeFilter.ANY_DIFF);

      while (treeWalk.next()) {
        String path = treeWalk.getPathString();
        paths.add(path);

        if (text.length() >= MAX_CHANGED_TEXT_CHARS
            || FileMode.MISSING.equals(treeWalk.getFileMode(CURRENT_TREE))) {
          continue;
        }

        ObjectId objectId = treeWalk.getObjectId(CURRENT_TREE);
        ObjectLoader loader = reader.open(objectId);
        if (loader.getType() != Constants.OBJ_BLOB || loader.getSize() > MAX_INDEXED_BLOB_BYTES) {
          continue;
        }

        byte[] bytes = loader.getBytes();
        text.append('\n').append("--- ").append(path).append(" ---\n");
        text.append(new String(bytes, StandardCharsets.UTF_8));
      }
    }

    return new TreeText(paths, truncate(text.toString(), MAX_CHANGED_TEXT_CHARS));
  }

  private static String truncate(String value, int maxLength) {
    return value.length() <= maxLength ? value : value.substring(0, maxLength);
  }

  private GitCommitIndex findExisting(String objectId) {
    try (Session session = sessionFactory.openSession()) {
      return session
          .createQuery(
              "FROM GitCommitIndex c WHERE c.repositoryName = :repo AND c.objectId = :objectId",
              GitCommitIndex.class)
          .setParameter("repo", repositoryName)
          .setParameter("objectId", objectId)
          .uniqueResult();
    }
  }

  private void upsert(GitCommitIndex projection) {
    try (Session session = sessionFactory.openSession()) {
      Transaction transaction = session.beginTransaction();
      try {
        GitCommitIndex existing =
            session
                .createQuery(
                    "FROM GitCommitIndex c WHERE c.repositoryName = :repo AND c.objectId = :objectId",
                    GitCommitIndex.class)
                .setParameter("repo", projection.getRepositoryName())
                .setParameter("objectId", projection.getObjectId())
                .uniqueResult();
        if (existing == null) {
          session.persist(projection);
        } else {
          existing.setShortMessage(projection.getShortMessage());
          existing.setFullMessage(projection.getFullMessage());
          existing.setAuthorName(projection.getAuthorName());
          existing.setAuthorEmail(projection.getAuthorEmail());
          existing.setCommitTime(projection.getCommitTime());
          existing.setChangedPaths(projection.getChangedPaths());
          existing.setChangedText(projection.getChangedText());
        }
        transaction.commit();
      } catch (RuntimeException e) {
        transaction.rollback();
        throw e;
      }
    }
  }

  private record TreeText(List<String> paths, String text) {}
}
