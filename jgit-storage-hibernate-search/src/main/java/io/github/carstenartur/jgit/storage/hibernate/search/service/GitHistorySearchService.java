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
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.search.engine.search.common.BooleanOperator;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.session.SearchSession;

/** Query service for indexed Git history. */
public class GitHistorySearchService {

  private static final String SUMMARY_PROJECTION =
      "SELECT new "
          + CommitSearchHit.class.getName()
          + "(c.objectId, c.shortMessage, c.authorName, c.authorEmail, "
          + "c.committerName, c.committerEmail, c.authorTime, c.committerTime) ";

  private final SessionFactory sessionFactory;

  public GitHistorySearchService(SessionFactory sessionFactory) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
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
   * <p>Results are relevance-ranked when full text is present and newest-first otherwise. The
   * chronological dimension is selected by {@link CommitHistoryQuery#timestampField()} and defaults
   * to committer time. Offset and limit are always applied after ordering.
   */
  public List<GitCommitIndex> findChanges(CommitHistoryQuery query) {
    validateQuery(query);
    if (matchesNothing(query)) {
      return List.of();
    }
    return query.text() == null ? findStructuredChanges(query) : findFullTextChanges(query);
  }

  /**
   * Find compact commit summaries without loading the large projection entity payload.
   *
   * <p>Full-text hits are projected directly from Lucene. Structured-only hits use a bounded HQL
   * constructor projection, so both paths return the same immutable result type without loading the
   * changed text column.
   */
  public List<CommitSearchHit> findChangeSummaries(CommitHistoryQuery query) {
    validateQuery(query);
    if (matchesNothing(query)) {
      return List.of();
    }
    return query.text() == null
        ? findStructuredSummaries(query)
        : findFullTextSummaries(query);
  }

  private List<GitCommitIndex> findFullTextChanges(CommitHistoryQuery query) {
    String timeField = searchTimeField(query);
    try (Session session = sessionFactory.openSession()) {
      SearchSession searchSession = Search.session(session);
      return searchSession
          .search(GitCommitIndex.class)
          .where(f -> fullTextPredicate(f, query, timeField))
          .fetchHits(query.offset(), query.limit());
    }
  }

  private List<CommitSearchHit> findFullTextSummaries(CommitHistoryQuery query) {
    String timeField = searchTimeField(query);
    try (Session session = sessionFactory.openSession()) {
      SearchSession searchSession = Search.session(session);
      return searchSession
          .search(GitCommitIndex.class)
          .select(CommitSearchHit.class)
          .where(f -> fullTextPredicate(f, query, timeField))
          .fetchHits(query.offset(), query.limit());
    }
  }

  private static org.hibernate.search.engine.search.predicate.dsl.SearchPredicateFinalStep
      fullTextPredicate(
          org.hibernate.search.engine.search.predicate.dsl.SearchPredicateFactory f,
          CommitHistoryQuery query,
          String timeField) {
    var predicate =
        f.bool()
            .filter(f.match().field("repositoryName").matching(query.repositoryName()))
            .must(
                f.simpleQueryString()
                    .fields("shortMessage", "fullMessage", "changedPaths", "changedText")
                    .matching(query.text()));
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
      predicate.filter(
          f.simpleQueryString()
              .field(GitCommitIndex.CHANGED_PATH_TERMS_FIELD)
              .matching(query.pathFragment())
              .defaultOperator(BooleanOperator.AND));
    }
    if (query.from() != null) {
      predicate.filter(f.range().field(timeField).atLeast(query.from()));
    }
    if (query.to() != null) {
      predicate.filter(f.range().field(timeField).atMost(query.to()));
    }
    return predicate;
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

  public List<GitCommitIndex> findByPath(String repositoryName, String pathFragment, int limit) {
    return findChanges(
        CommitHistoryQuery.forRepository(repositoryName)
            .touchingPath(pathFragment)
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
