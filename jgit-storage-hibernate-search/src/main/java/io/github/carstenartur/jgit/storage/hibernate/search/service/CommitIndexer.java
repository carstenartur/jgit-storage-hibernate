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
import io.github.carstenartur.jgit.storage.hibernate.search.profile.SearchContentPolicy;
import io.github.carstenartur.jgit.storage.hibernate.search.profile.SearchIndexingProfile;
import io.github.carstenartur.jgit.storage.hibernate.search.profile.SearchIndexingProfile.ContentMode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.HistogramDiff;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
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
 *
 * <p>Reachable-history indexing uses bounded groups. One existence query covers the complete group,
 * only missing commits are extracted, and all new projections are written in one transaction. The
 * same {@link RevWalk} and {@link ObjectReader} remain open for the walk, avoiding reparsing and
 * reopening Git storage once per commit.
 */
public class CommitIndexer {

  /** Configurable number of commit projections handled by one bounded indexing transaction. */
  public static final String INDEX_BATCH_SIZE_PROPERTY =
      "jgit.storage.hibernate.search.index_batch_size";

  /** Balanced default for incremental indexing and rebuilds. */
  public static final int DEFAULT_INDEX_BATCH_SIZE = 50;

  /** Safety ceiling for SQL {@code IN} lists and one persistence context. */
  public static final int MAX_INDEX_BATCH_SIZE = 1_000;

  private static final int PREVIOUS_TREE = 0;
  private static final int CURRENT_TREE = 1;

  private final SessionFactory sessionFactory;
  private final String repositoryName;
  private final int batchSize;
  private final SearchIndexingProfile indexingProfile;
  private final SearchContentPolicy contentPolicy;

  /**
   * Create an indexer.
   *
   * @param sessionFactory Hibernate session factory containing the search entities
   * @param repositoryName logical repository name
   */
  public CommitIndexer(SessionFactory sessionFactory, String repositoryName) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    this.repositoryName = Objects.requireNonNull(repositoryName, "repositoryName");
    SearchIndexCompatibility.ensureCurrentDocumentIdentifiers(this.sessionFactory);
    indexingProfile = SearchIndexingProfile.resolve(sessionFactory);
    contentPolicy = SearchContentPolicy.resolve(sessionFactory);
    SearchIndexProfileCompatibility.requireCompatible(this.sessionFactory, repositoryName);
    batchSize = resolveBatchSize(sessionFactory);
  }

  /** Return the semantic profile used by this indexer. */
  public SearchIndexingProfile indexingProfile() {
    return indexingProfile;
  }

  /** Return the bounded content policy used by this indexer. */
  public SearchContentPolicy contentPolicy() {
    return contentPolicy;
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
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(commitId, "commitId");
    try (RevWalk revWalk = new RevWalk(repository);
        ObjectReader reader = repository.newObjectReader()) {
      RevCommit commit = revWalk.parseCommit(commitId);
      GitCommitIndex projection = toProjection(reader, revWalk, commit);
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
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(start, "start");
    int indexed = 0;
    int visited = 0;
    List<RevCommit> pending = new ArrayList<>(batchSize);
    try (RevWalk revWalk = new RevWalk(repository);
        ObjectReader reader = repository.newObjectReader()) {
      revWalk.markStart(revWalk.parseCommit(start));
      for (RevCommit commit : revWalk) {
        if (limit >= 0 && visited++ >= limit) {
          break;
        }
        pending.add(commit);
        if (pending.size() == batchSize) {
          indexed += indexParsedBatch(reader, revWalk, pending, false);
          pending.clear();
        }
      }
      if (!pending.isEmpty()) {
        indexed += indexParsedBatch(reader, revWalk, pending, false);
      }
    }
    return indexed;
  }

  /**
   * Persist parsed commits that are known to be absent, normally after a projection purge.
   *
   * <p>The method is package-private so {@link CommitProjectionRebuilder} can preserve one Git walk
   * and one object reader while still delegating extraction and transactional persistence here.
   */
  int indexKnownMissingBatch(
      ObjectReader reader, RevWalk revWalk, List<RevCommit> commits) throws IOException {
    return indexParsedBatch(reader, revWalk, commits, true);
  }

  int batchSize() {
    return batchSize;
  }

  static int resolveBatchSize(SessionFactory sessionFactory) {
    Object configured = sessionFactory.getProperties().get(INDEX_BATCH_SIZE_PROPERTY);
    if (configured == null || configured.toString().isBlank()) {
      return DEFAULT_INDEX_BATCH_SIZE;
    }
    int value;
    try {
      value = Integer.parseInt(configured.toString().trim());
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(
          INDEX_BATCH_SIZE_PROPERTY + " must be an integer but was '" + configured + "'",
          exception);
    }
    if (value <= 0 || value > MAX_INDEX_BATCH_SIZE) {
      throw new IllegalArgumentException(
          INDEX_BATCH_SIZE_PROPERTY
              + " must be between 1 and "
              + MAX_INDEX_BATCH_SIZE
              + " but was "
              + value);
    }
    return value;
  }

  private int indexParsedBatch(
      ObjectReader reader,
      RevWalk revWalk,
      List<RevCommit> commits,
      boolean knownMissing)
      throws IOException {
    if (commits.isEmpty()) {
      return 0;
    }
    Set<String> existing = knownMissing ? Set.of() : findExistingObjectIds(commits);
    List<GitCommitIndex> projections = new ArrayList<>(commits.size());
    for (RevCommit commit : commits) {
      if (!existing.contains(commit.name())) {
        projections.add(toProjection(reader, revWalk, commit));
      }
    }
    persistNewBatch(projections);
    return projections.size();
  }

  private GitCommitIndex toProjection(
      ObjectReader reader, RevWalk revWalk, RevCommit commit) throws IOException {
    GitCommitIndex projection = new GitCommitIndex();
    projection.setIndexProfile(indexingProfile.id());
    projection.setRepositoryName(repositoryName);
    projection.setObjectId(commit.name());
    projection.setShortMessage(commit.getShortMessage());
    projection.setFullMessage(commit.getFullMessage());

    PersonIdent author = commit.getAuthorIdent();
    if (author != null) {
      projection.setAuthorName(author.getName());
      projection.setAuthorEmail(author.getEmailAddress());
      projection.setAuthorTime(author.getWhenAsInstant());
    }

    PersonIdent committer = commit.getCommitterIdent();
    if (committer != null) {
      projection.setCommitterName(committer.getName());
      projection.setCommitterEmail(committer.getEmailAddress());
      projection.setCommitterTime(committer.getWhenAsInstant());
    }

    TreeText treeText = readChangedTreeText(reader, revWalk, commit);
    projection.setChangedPaths(String.join("\n", treeText.paths()));
    projection.setChangedText(treeText.text());
    return projection;
  }

  private TreeText readChangedTreeText(
      ObjectReader reader, RevWalk revWalk, RevCommit commit) throws IOException {
    if (!indexingProfile.indexesPaths()) {
      return new TreeText(List.of(), null);
    }

    List<String> paths = new ArrayList<>();
    StringBuilder text = new StringBuilder();

    try (TreeWalk treeWalk = new TreeWalk(reader)) {
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

        if (!indexingProfile.indexesContent()
            || text.length() >= contentPolicy.maxCommitChars()
            || FileMode.MISSING.equals(treeWalk.getFileMode(CURRENT_TREE))
            || !contentPolicy.acceptsPath(path)) {
          continue;
        }

        String currentText = readTextCandidate(reader, treeWalk, CURRENT_TREE, path);
        if (currentText == null) {
          continue;
        }

        String indexedText = currentText;
        if (indexingProfile.contentMode() == ContentMode.DIFF_HUNKS) {
          String previousText =
              FileMode.MISSING.equals(treeWalk.getFileMode(PREVIOUS_TREE))
                  ? ""
                  : readTextCandidate(reader, treeWalk, PREVIOUS_TREE, path);
          if (previousText == null) {
            continue;
          }
          indexedText = addedOrModifiedLines(previousText, currentText);
          if (indexedText.isEmpty()) {
            continue;
          }
        }

        appendBounded(text, "\n--- " + path + " ---\n", contentPolicy.maxCommitChars());
        appendBounded(text, indexedText, contentPolicy.maxCommitChars());
      }
    }

    return new TreeText(paths, text.isEmpty() ? null : text.toString());
  }

  private String readTextCandidate(
      ObjectReader reader, TreeWalk treeWalk, int treeIndex, String path) throws IOException {
    ObjectId objectId = treeWalk.getObjectId(treeIndex);
    ObjectLoader loader = reader.open(objectId);
    if (loader.getType() != Constants.OBJ_BLOB || loader.getSize() > contentPolicy.maxFileBytes()) {
      return null;
    }
    return contentPolicy.decode(path, loader.getBytes());
  }

  private static String addedOrModifiedLines(String previous, String current) {
    RawText before = new RawText(previous.getBytes(StandardCharsets.UTF_8));
    RawText after = new RawText(current.getBytes(StandardCharsets.UTF_8));
    EditList edits = new HistogramDiff().diff(RawTextComparator.DEFAULT, before, after);
    StringBuilder result = new StringBuilder();
    for (Edit edit : edits) {
      for (int line = edit.getBeginB(); line < edit.getEndB(); line++) {
        result.append(after.getString(line)).append('\n');
      }
    }
    return result.toString();
  }

  private static void appendBounded(StringBuilder target, String value, int maximum) {
    if (value == null || value.isEmpty() || target.length() >= maximum) {
      return;
    }
    int remaining = maximum - target.length();
    target.append(value, 0, Math.min(value.length(), remaining));
  }

  private Set<String> findExistingObjectIds(List<RevCommit> commits) {
    List<String> objectIds = commits.stream().map(RevCommit::name).toList();
    try (Session session = sessionFactory.openSession()) {
      return new HashSet<>(
          session
              .createQuery(
                  "SELECT c.objectId FROM GitCommitIndex c WHERE c.repositoryName = :repo "
                      + "AND c.objectId IN :objectIds",
                  String.class)
              .setParameter("repo", repositoryName)
              .setParameter("objectIds", objectIds)
              .getResultList());
    }
  }

  private void persistNewBatch(List<GitCommitIndex> projections) {
    if (projections.isEmpty()) {
      return;
    }
    try (Session session = sessionFactory.openSession()) {
      Transaction transaction = session.beginTransaction();
      try {
        session.setJdbcBatchSize(Math.min(batchSize, projections.size()));
        for (GitCommitIndex projection : projections) {
          session.persist(projection);
        }
        session.flush();
        transaction.commit();
      } catch (RuntimeException exception) {
        transaction.rollback();
        throw exception;
      }
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
          copyMutableProjection(existing, projection);
        }
        transaction.commit();
      } catch (RuntimeException exception) {
        transaction.rollback();
        throw exception;
      }
    }
  }

  private static void copyMutableProjection(
      GitCommitIndex target, GitCommitIndex source) {
    target.setIndexProfile(source.getIndexProfile());
    target.setShortMessage(source.getShortMessage());
    target.setFullMessage(source.getFullMessage());
    target.setAuthorName(source.getAuthorName());
    target.setAuthorEmail(source.getAuthorEmail());
    target.setAuthorTime(source.getAuthorTime());
    target.setCommitterName(source.getCommitterName());
    target.setCommitterEmail(source.getCommitterEmail());
    target.setCommitterTime(source.getCommitterTime());
    target.setChangedPaths(source.getChangedPaths());
    target.setChangedText(source.getChangedText());
  }

  private record TreeText(List<String> paths, String text) {}
}
