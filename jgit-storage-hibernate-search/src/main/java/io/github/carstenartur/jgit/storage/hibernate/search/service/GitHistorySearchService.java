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
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitHistoryQuery.PathMatch;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import org.hibernate.ScrollMode;
import org.hibernate.ScrollableResults;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.search.engine.search.common.BooleanOperator;
import org.hibernate.search.engine.search.predicate.SearchPredicate;
import org.hibernate.search.engine.search.predicate.dsl.SearchPredicateFactory;
import org.hibernate.search.engine.search.query.SearchScroll;
import org.hibernate.search.engine.search.sort.SearchSort;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.scope.SearchScope;
import org.hibernate.search.mapper.orm.session.SearchSession;

/** Query service for indexed Git history. */
public class GitHistorySearchService {

  /** Hibernate configuration property bounding offset-based UI pagination. */
  public static final String MAX_OFFSET_PROPERTY = "jgit.storage.hibernate.search.max_offset";

  /** Default maximum offset for list-returning page queries. */
  public static final int DEFAULT_MAX_OFFSET = 10_000;

  /** Default result chunk size used by the closeable scrolling API. */
  public static final int DEFAULT_SCROLL_CHUNK_SIZE = 100;

  /** Hard safety bound for one scrolling result chunk. */
  public static final int MAX_SCROLL_CHUNK_SIZE = 1_000;

  private static final String SUMMARY_PROJECTION =
      "SELECT new "
          + CommitSearchHit.class.getName()
          + "(c.objectId, c.shortMessage, c.authorName, c.authorEmail, "
          + "c.committerName, c.committerEmail, c.authorTime, c.committerTime) ";

  private final SessionFactory sessionFactory;
  private final int maxOffset;

  public GitHistorySearchService(SessionFactory sessionFactory) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
    maxOffset = configuredMaxOffset(sessionFactory);
    SearchIndexCompatibility.ensureCurrentDocumentIdentifiers(this.sessionFactory);
  }

  public List<GitCommitIndex> searchCommitText(String repositoryName, String query, int limit) {
    return findChanges(
        CommitHistoryQuery.forRepository(repositoryName)
            .matchingText(query)
            .limit(limit)
            .build());
  }

  /**
   * Search commit text and return compact values directly from the index.
   *
   * <p>Unlike {@link #searchCommitText(String, String, int)}, this method does not hydrate matching
   * {@link GitCommitIndex} entities and therefore does not materialize the large changed-text and
   * changed-path columns.
   */
  public List<CommitSearchHit> searchCommitTextSummaries(
      String repositoryName, String query, int limit) {
    return findChangeSummaries(
        CommitHistoryQuery.forRepository(repositoryName)
            .matchingText(query)
            .limit(limit)
            .build());
  }

  /**
   * Find commits matching all supplied full-text, identity, changed-path, time and candidate
   * predicates.
   *
   * <p>Results are relevance-ranked when full text is present and newest-first otherwise. Explicit
   * analyzed/exact path modes use Lucene even without a free-text expression; the original literal
   * fragment mode remains relational for backward compatibility. The chronological dimension is
   * selected by {@link CommitHistoryQuery#timestampField()} and defaults to committer time.
   */
  public List<GitCommitIndex> findChanges(CommitHistoryQuery query) {
    validatePagedQuery(query);
    if (matchesNothing(query)) {
      return List.of();
    }
    SearchIndexProfileCompatibility.requireCompatible(sessionFactory, query.repositoryName());
    return query.requiresSearchBackend() ? findIndexedChanges(query) : findStructuredChanges(query);
  }

  /**
   * Find compact commit summaries without loading the large projection entity payload.
   *
   * <p>Lucene-backed hits are projected directly from the index. Structured-only hits use a bounded
   * HQL constructor projection, so both paths return the same immutable result type without loading
   * the changed text column.
   */
  public List<CommitSearchHit> findChangeSummaries(CommitHistoryQuery query) {
    validatePagedQuery(query);
    if (matchesNothing(query)) {
      return List.of();
    }
    SearchIndexProfileCompatibility.requireCompatible(sessionFactory, query.repositoryName());
    return query.requiresSearchBackend()
        ? findIndexedSummaries(query)
        : findStructuredSummaries(query);
  }

  /**
   * Open a closeable, bounded cursor over compact history hits using a 100-hit chunk by default.
   *
   * <p>The query's {@link CommitHistoryQuery#limit()} is the maximum number of hits the cursor will
   * expose. Use {@link CommitHistoryQuery.Builder#unbounded()} for a complete export. Offset must be
   * zero: scrolling is the replacement for deep offset traversal, not another way to implement it.
   */
  public CommitSearchCursor scrollChangeSummaries(CommitHistoryQuery query) {
    return scrollChangeSummaries(query, DEFAULT_SCROLL_CHUNK_SIZE);
  }

  /**
   * Open a closeable, bounded cursor over compact history hits.
   *
   * <p>Search-backed queries use Hibernate Search's stateful scroll and a stable score/time/object-id
   * sort. Structured-only queries use a forward-only Hibernate ORM cursor with the same chronological
   * ordering as the existing list API. Both paths keep at most one configured chunk in application
   * memory and release resources when the cursor is closed.
   */
  public CommitSearchCursor scrollChangeSummaries(CommitHistoryQuery query, int chunkSize) {
    validateScrollQuery(query, chunkSize);
    if (matchesNothing(query) || query.limit() == 0) {
      return emptyCursor();
    }
    SearchIndexProfileCompatibility.requireCompatible(sessionFactory, query.repositoryName());
    return query.requiresSearchBackend()
        ? scrollIndexedSummaries(query, chunkSize)
        : scrollStructuredSummaries(query, chunkSize);
  }

  /** The configured maximum offset accepted by list-returning pagination APIs. */
  public int maxOffset() {
    return maxOffset;
  }

  private List<GitCommitIndex> findIndexedChanges(CommitHistoryQuery query) {
    String timeField = searchTimeField(query);
    try (Session session = sessionFactory.openSession()) {
      SearchSession searchSession = Search.session(session);
      SearchScope<GitCommitIndex> scope = searchSession.scope(GitCommitIndex.class);
      SearchPredicate predicate = indexedPredicate(scope.predicate(), query, timeField);
      var step = searchSession.search(scope).where(predicate);
      if (query.text() == null) {
        step = step.sort(chronologicalSort(scope, timeField));
      }
      return step.fetchHits(query.offset(), query.limit());
    }
  }

  private List<CommitSearchHit> findIndexedSummaries(CommitHistoryQuery query) {
    String timeField = searchTimeField(query);
    try (Session session = sessionFactory.openSession()) {
      SearchSession searchSession = Search.session(session);
      SearchScope<GitCommitIndex> scope = searchSession.scope(GitCommitIndex.class);
      SearchPredicate predicate = indexedPredicate(scope.predicate(), query, timeField);
      var step = searchSession.search(scope).select(CommitSearchHit.class).where(predicate);
      if (query.text() == null) {
        step = step.sort(chronologicalSort(scope, timeField));
      }
      return step.fetchHits(query.offset(), query.limit());
    }
  }

  private CommitSearchCursor scrollIndexedSummaries(CommitHistoryQuery query, int chunkSize) {
    String timeField = searchTimeField(query);
    Session session = sessionFactory.openSession();
    try {
      SearchSession searchSession = Search.session(session);
      SearchScope<GitCommitIndex> scope = searchSession.scope(GitCommitIndex.class);
      SearchPredicate predicate = indexedPredicate(scope.predicate(), query, timeField);
      var step = searchSession.search(scope).select(CommitSearchHit.class).where(predicate);
      step =
          step.sort(
              query.text() == null
                  ? chronologicalSort(scope, timeField)
                  : stableRelevanceSort(scope, timeField));
      int effectiveChunk = Math.min(chunkSize, query.limit());
      SearchScroll<CommitSearchHit> scroll = step.scroll(effectiveChunk);
      return new CommitSearchCursor(
          new CommitSearchCursor.Source() {
            private int remaining = query.limit();

            @Override
            public List<CommitSearchHit> nextChunk() {
              if (remaining == 0) {
                return List.of();
              }
              var result = scroll.next();
              if (!result.hasHits()) {
                return List.of();
              }
              List<CommitSearchHit> hits = result.hits();
              int returned = Math.min(remaining, hits.size());
              remaining -= returned;
              return returned == hits.size() ? hits : hits.subList(0, returned);
            }

            @Override
            public void close() {
              try {
                scroll.close();
              } finally {
                session.close();
              }
            }
          });
    } catch (RuntimeException | Error failure) {
      session.close();
      throw failure;
    }
  }

  private CommitSearchCursor scrollStructuredSummaries(CommitHistoryQuery query, int chunkSize) {
    String timeProperty = hqlTimeProperty(query);
    StringBuilder hql =
        new StringBuilder(SUMMARY_PROJECTION)
            .append("FROM GitCommitIndex c WHERE c.repositoryName = :repo");
    appendStructuredPredicates(hql, query, timeProperty);
    appendStructuredOrder(hql, timeProperty);

    Session session = sessionFactory.openSession();
    Transaction transaction = session.beginTransaction();
    try {
      var selection = session.createQuery(hql.toString(), CommitSearchHit.class);
      selection.setFetchSize(chunkSize);
      selection.setReadOnly(true);
      bindStructuredParameters(selection, query);
      ScrollableResults<CommitSearchHit> results = selection.scroll(ScrollMode.FORWARD_ONLY);
      return new CommitSearchCursor(
          new CommitSearchCursor.Source() {
            private int remaining = query.limit();

            @Override
            public List<CommitSearchHit> nextChunk() {
              if (remaining == 0) {
                return List.of();
              }
              int requested = Math.min(chunkSize, remaining);
              List<CommitSearchHit> hits = new ArrayList<>(requested);
              while (hits.size() < requested && results.next()) {
                if (Thread.currentThread().isInterrupted()) {
                  throw new CancellationException("search cursor cancelled by thread interruption");
                }
                hits.add(results.get());
              }
              remaining -= hits.size();
              return hits;
            }

            @Override
            public void close() {
              try {
                results.close();
              } finally {
                try {
                  if (transaction.isActive()) {
                    transaction.rollback();
                  }
                } finally {
                  session.close();
                }
              }
            }
          });
    } catch (RuntimeException | Error failure) {
      try {
        if (transaction.isActive()) {
          transaction.rollback();
        }
      } finally {
        session.close();
      }
      throw failure;
    }
  }

  private static SearchPredicate indexedPredicate(
      SearchPredicateFactory f, CommitHistoryQuery query, String timeField) {
    var predicate =
        f.bool().filter(f.match().field("repositoryName").matching(query.repositoryName()));
    if (query.text() != null) {
      predicate.must(
          f.simpleQueryString()
              .fields(
                  "shortMessage",
                  "fullMessage",
                  GitCommitIndex.CHANGED_PATH_TERMS_FIELD,
                  "changedText")
              .matching(query.text()));
    }
    if (query.hasObjectIdRestriction()) {
      predicate.filter(f.terms().field("objectId").matchingAny(query.objectIds()));
    }
    if (query.authorEmail() != null) {
      predicate.filter(f.match().field("authorEmail").matching(query.authorEmail()));
    }
    if (query.committerEmail() != null) {
      predicate.filter(f.match().field("committerEmail").matching(query.committerEmail()));
    }
    if (query.pathFragment() != null) {
      if (query.pathMatch() == PathMatch.EXACT) {
        predicate.filter(
            f.match()
                .field(GitCommitIndex.CHANGED_PATH_EXACT_FIELD)
                .matching(query.pathFragment()));
      } else {
        // ANALYZED_TERMS is explicit. LITERAL_FRAGMENT reaches this path only when a free-text
        // expression already selected Lucene, preserving the previous compound-query semantics.
        predicate.filter(
            f.simpleQueryString()
                .field(GitCommitIndex.CHANGED_PATH_TERMS_FIELD)
                .matching(query.pathFragment())
                .defaultOperator(BooleanOperator.AND));
      }
    }
    if (query.from() != null) {
      predicate.filter(f.range().field(timeField).atLeast(query.from()));
    }
    if (query.to() != null) {
      predicate.filter(f.range().field(timeField).atMost(query.to()));
    }
    return predicate.toPredicate();
  }

  private static SearchSort chronologicalSort(
      SearchScope<GitCommitIndex> scope, String timeField) {
    return scope
        .sort()
        .field(timeField)
        .desc()
        .then()
        .field("objectId")
        .asc()
        .toSort();
  }

  private static SearchSort stableRelevanceSort(
      SearchScope<GitCommitIndex> scope, String timeField) {
    return scope
        .sort()
        .score()
        .then()
        .field(timeField)
        .desc()
        .then()
        .field("objectId")
        .asc()
        .toSort();
  }

  private List<GitCommitIndex> findStructuredChanges(CommitHistoryQuery query) {
    String timeProperty = hqlTimeProperty(query);
    StringBuilder hql =
        new StringBuilder("FROM GitCommitIndex c WHERE c.repositoryName = :repo");
    appendStructuredPredicates(hql, query, timeProperty);
    appendStructuredOrder(hql, timeProperty);

    try (Session session = sessionFactory.openSession()) {
      var selection =
          session
              .createQuery(hql.toString(), GitCommitIndex.class)
              .setFirstResult(query.offset())
              .setMaxResults(query.limit());
      bindStructuredParameters(selection, query);
      return selection.getResultList();
    }
  }

  private List<CommitSearchHit> findStructuredSummaries(CommitHistoryQuery query) {
    String timeProperty = hqlTimeProperty(query);
    StringBuilder hql =
        new StringBuilder(SUMMARY_PROJECTION)
            .append("FROM GitCommitIndex c WHERE c.repositoryName = :repo");
    appendStructuredPredicates(hql, query, timeProperty);
    appendStructuredOrder(hql, timeProperty);

    try (Session session = sessionFactory.openSession()) {
      var selection =
          session
              .createQuery(hql.toString(), CommitSearchHit.class)
              .setFirstResult(query.offset())
              .setMaxResults(query.limit());
      bindStructuredParameters(selection, query);
      return selection.getResultList();
    }
  }

  private static void appendStructuredPredicates(
      StringBuilder hql, CommitHistoryQuery query, String timeProperty) {
    if (query.hasObjectIdRestriction()) {
      hql.append(" AND c.objectId IN :objectIds");
    }
    if (query.authorEmail() != null) {
      hql.append(" AND c.authorEmail = :authorEmail");
    }
    if (query.committerEmail() != null) {
      hql.append(" AND c.committerEmail = :committerEmail");
    }
    if (query.pathFragment() != null) {
      hql.append(" AND c.changedPaths ILIKE :path ESCAPE '!'");
    }
    if (query.from() != null) {
      hql.append(" AND c.").append(timeProperty).append(" >= :from");
    }
    if (query.to() != null) {
      hql.append(" AND c.").append(timeProperty).append(" <= :to");
    }
  }

  private static void appendStructuredOrder(StringBuilder hql, String timeProperty) {
    hql.append(" ORDER BY c.").append(timeProperty).append(" DESC, c.objectId ASC");
  }

  private static void bindStructuredParameters(
      org.hibernate.query.SelectionQuery<?> selection, CommitHistoryQuery query) {
    selection.setParameter("repo", query.repositoryName());
    if (query.hasObjectIdRestriction()) {
      selection.setParameter("objectIds", query.objectIds());
    }
    if (query.authorEmail() != null) {
      selection.setParameter("authorEmail", query.authorEmail());
    }
    if (query.committerEmail() != null) {
      selection.setParameter("committerEmail", query.committerEmail());
    }
    if (query.pathFragment() != null) {
      selection.setParameter(
          "path", "%" + escapeLikePattern(query.pathFragment().toLowerCase(Locale.ROOT)) + "%");
    }
    if (query.from() != null) {
      selection.setParameter("from", query.from());
    }
    if (query.to() != null) {
      selection.setParameter("to", query.to());
    }
  }

  private void validatePagedQuery(CommitHistoryQuery query) {
    validateQuery(query);
    if (query.offset() > maxOffset) {
      throw new IllegalArgumentException(
          "offset "
              + query.offset()
              + " exceeds configured maximum "
              + maxOffset
              + "; use scrollChangeSummaries for deep traversal/export or configure "
              + MAX_OFFSET_PROPERTY);
    }
  }

  private static void validateScrollQuery(CommitHistoryQuery query, int chunkSize) {
    validateQuery(query);
    if (query.offset() != 0) {
      throw new IllegalArgumentException(
          "scrolling requires offset 0; use the cursor itself instead of deep offset pagination");
    }
    if (chunkSize <= 0 || chunkSize > MAX_SCROLL_CHUNK_SIZE) {
      throw new IllegalArgumentException(
          "scroll chunk size must be between 1 and " + MAX_SCROLL_CHUNK_SIZE);
    }
  }

  private static void validateQuery(CommitHistoryQuery query) {
    Objects.requireNonNull(query, "query");
  }

  private static boolean matchesNothing(CommitHistoryQuery query) {
    return query.hasObjectIdRestriction() && query.objectIds().isEmpty();
  }

  private static String hqlTimeProperty(CommitHistoryQuery query) {
    return query.timestampField() == CommitHistoryQuery.TimestampField.AUTHOR
        ? "authorTime"
        : "committerTime";
  }

  private static String searchTimeField(CommitHistoryQuery query) {
    return hqlTimeProperty(query);
  }

  private static int configuredMaxOffset(SessionFactory sessionFactory) {
    Object configured = sessionFactory.getProperties().get(MAX_OFFSET_PROPERTY);
    if (configured == null) {
      return DEFAULT_MAX_OFFSET;
    }
    try {
      int value = Integer.parseInt(configured.toString().trim());
      if (value < 0) {
        throw new IllegalArgumentException(MAX_OFFSET_PROPERTY + " must not be negative");
      }
      return value;
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException(
          MAX_OFFSET_PROPERTY + " must be a non-negative integer", invalid);
    }
  }

  private static CommitSearchCursor emptyCursor() {
    return new CommitSearchCursor(
        new CommitSearchCursor.Source() {
          @Override
          public List<CommitSearchHit> nextChunk() {
            return List.of();
          }

          @Override
          public void close() {}
        });
  }

  /** Backward-compatible literal, case-insensitive path fragment lookup. */
  public List<GitCommitIndex> findByPath(String repositoryName, String pathFragment, int limit) {
    return findChanges(
        CommitHistoryQuery.forRepository(repositoryName)
            .touchingPath(pathFragment)
            .limit(limit)
            .build());
  }

  /** Find commits matching all analyzed path components through Lucene. */
  public List<CommitSearchHit> findSummariesByPathTerms(
      String repositoryName, String pathTerms, int limit) {
    return findChangeSummaries(
        CommitHistoryQuery.forRepository(repositoryName)
            .touchingPathTerms(pathTerms)
            .limit(limit)
            .build());
  }

  /** Find commits that changed one complete exact path through Lucene. */
  public List<CommitSearchHit> findSummariesByExactPath(
      String repositoryName, String path, int limit) {
    return findChangeSummaries(
        CommitHistoryQuery.forRepository(repositoryName)
            .touchingExactPath(path)
            .limit(limit)
            .build());
  }

  public List<GitCommitIndex> findByAuthorEmail(
      String repositoryName, String authorEmail, int limit) {
    return findChanges(
        CommitHistoryQuery.forRepository(repositoryName)
            .authoredBy(authorEmail)
            .limit(limit)
            .build());
  }

  public List<GitCommitIndex> findByCommitterEmail(
      String repositoryName, String committerEmail, int limit) {
    return findChanges(
        CommitHistoryQuery.forRepository(repositoryName)
            .committedBy(committerEmail)
            .limit(limit)
            .build());
  }

  /** Return commits whose committer timestamps are in the inclusive range. */
  public List<GitCommitIndex> findBetween(
      String repositoryName, Instant from, Instant to, int limit) {
    return findChanges(
        CommitHistoryQuery.forRepository(repositoryName)
            .committedBetween(from, to)
            .limit(limit)
            .build());
  }

  /** Return commits whose author timestamps are in the inclusive range. */
  public List<GitCommitIndex> findAuthoredBetween(
      String repositoryName, Instant from, Instant to, int limit) {
    return findChanges(
        CommitHistoryQuery.forRepository(repositoryName)
            .authoredBetween(from, to)
            .limit(limit)
            .build());
  }

  public long countIndexedCommits(String repositoryName) {
    try (Session session = sessionFactory.openSession()) {
      Long count =
          session
              .createQuery(
                  "SELECT COUNT(c) FROM GitCommitIndex c WHERE c.repositoryName = :repo", Long.class)
              .setParameter("repo", repositoryName)
              .uniqueResult();
      return count != null ? count.longValue() : 0L;
    }
  }

  private static String escapeLikePattern(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }
}
