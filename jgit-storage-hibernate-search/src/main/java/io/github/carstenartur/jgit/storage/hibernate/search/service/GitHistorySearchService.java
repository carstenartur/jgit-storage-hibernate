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

  private final SessionFactory sessionFactory;

  /**
   * Create a query service.
   *
   * @param sessionFactory Hibernate session factory containing search projections
   */
  public GitHistorySearchService(SessionFactory sessionFactory) {
    this.sessionFactory = Objects.requireNonNull(sessionFactory, "sessionFactory");
  }

  /**
   * Full-text search over commit messages, changed paths and indexed changed-file content.
   *
   * @param repositoryName logical repository name
   * @param query search query
   * @param limit maximum hits
   * @return matching commit projections
   */
  public List<GitCommitIndex> searchCommitText(String repositoryName, String query, int limit) {
    return findChanges(
        CommitHistoryQuery.forRepository(repositoryName)
            .matchingText(query)
            .limit(limit)
            .build());
  }

  /**
   * Find commits matching all supplied full-text, author, changed-path and time predicates.
   *
   * <p>This is the reusable projection equivalent of manually walking commits with JGit, diffing
   * every commit against its first parent and applying the predicates in application code.
   *
   * @param query compound query
   * @return matching commits, relevance-ranked when full text is present and newest-first otherwise
   */
  public List<GitCommitIndex> findChanges(CommitHistoryQuery query) {
    Objects.requireNonNull(query, "query");
    return query.text() == null ? findStructuredChanges(query) : findFullTextChanges(query);
  }

  private List<GitCommitIndex> findFullTextChanges(CommitHistoryQuery query) {
    try (Session session = sessionFactory.openSession()) {
      SearchSession searchSession = Search.session(session);
      return searchSession
          .search(GitCommitIndex.class)
          .where(
              f -> {
                var predicate =
                    f.bool()
                        .filter(
                            f.match()
                                .field("repositoryName")
                                .matching(query.repositoryName()))
                        .must(
                            f.simpleQueryString()
                                .fields(
                                    "shortMessage",
                                    "fullMessage",
                                    "changedPaths",
                                    "changedText")
                                .matching(query.text()));
                if (query.authorEmail() != null) {
                  predicate.filter(
                      f.match().field("authorEmail").matching(query.authorEmail()));
                }
                if (query.pathFragment() != null) {
                  predicate.filter(
                      f.simpleQueryString()
                          .field(GitCommitIndex.CHANGED_PATH_TERMS_FIELD)
                          .matching(query.pathFragment())
                          .defaultOperator(BooleanOperator.AND));
                }
                if (query.from() != null) {
                  predicate.filter(f.range().field("commitTime").atLeast(query.from()));
                }
                if (query.to() != null) {
                  predicate.filter(f.range().field("commitTime").atMost(query.to()));
                }
                return predicate;
              })
          .fetchHits(query.limit());
    }
  }

  private List<GitCommitIndex> findStructuredChanges(CommitHistoryQuery query) {
    StringBuilder hql =
        new StringBuilder("FROM GitCommitIndex c WHERE c.repositoryName = :repo");
    if (query.authorEmail() != null) {
      hql.append(" AND c.authorEmail = :email");
    }
    if (query.pathFragment() != null) {
      hql.append(" AND LOWER(c.changedPaths) LIKE :path ESCAPE '!'");
    }
    if (query.from() != null) {
      hql.append(" AND c.commitTime >= :from");
    }
    if (query.to() != null) {
      hql.append(" AND c.commitTime <= :to");
    }
    hql.append(" ORDER BY c.commitTime DESC");

    try (Session session = sessionFactory.openSession()) {
      var selection =
          session
              .createQuery(hql.toString(), GitCommitIndex.class)
              .setParameter("repo", query.repositoryName())
              .setMaxResults(query.limit());
      if (query.authorEmail() != null) {
        selection.setParameter("email", query.authorEmail());
      }
      if (query.pathFragment() != null) {
        selection.setParameter(
            "path",
            "%" + escapeLikePattern(query.pathFragment().toLowerCase(Locale.ROOT)) + "%");
      }
      if (query.from() != null) {
        selection.setParameter("from", query.from());
      }
      if (query.to() != null) {
        selection.setParameter("to", query.to());
      }
      return selection.getResultList();
    }
  }

  /**
   * Find commits whose changed-path list contains the given path fragment.
   *
   * @param repositoryName logical repository name
   * @param pathFragment path fragment
   * @param limit maximum hits
   * @return matching commits
   */
  public List<GitCommitIndex> findByPath(String repositoryName, String pathFragment, int limit) {
    return findChanges(
        CommitHistoryQuery.forRepository(repositoryName)
            .touchingPath(pathFragment)
            .limit(limit)
            .build());
  }

  /**
   * Return commits by author email.
   *
   * @param repositoryName logical repository name
   * @param authorEmail author email
   * @param limit maximum hits
   * @return matching commits
   */
  public List<GitCommitIndex> findByAuthorEmail(
      String repositoryName, String authorEmail, int limit) {
    return findChanges(
        CommitHistoryQuery.forRepository(repositoryName)
            .authoredBy(authorEmail)
            .limit(limit)
            .build());
  }

  /**
   * Return commits in an inclusive timestamp range.
   *
   * @param repositoryName logical repository name
   * @param from inclusive lower bound
   * @param to inclusive upper bound
   * @param limit maximum hits
   * @return matching commits
   */
  public List<GitCommitIndex> findBetween(
      String repositoryName, Instant from, Instant to, int limit) {
    return findChanges(
        CommitHistoryQuery.forRepository(repositoryName)
            .between(from, to)
            .limit(limit)
            .build());
  }

  /**
   * Count indexed commits for a repository.
   *
   * @param repositoryName logical repository name
   * @return indexed commit count
   */
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
