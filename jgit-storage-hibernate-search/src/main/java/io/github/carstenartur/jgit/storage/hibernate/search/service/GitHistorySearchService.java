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
import java.util.Objects;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
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
   * Full-text search over commit messages, paths and indexed text content.
   *
   * @param repositoryName logical repository name
   * @param query search query
   * @param limit maximum hits
   * @return matching commit projections
   */
  public List<GitCommitIndex> searchCommitText(String repositoryName, String query, int limit) {
    if (query == null || query.isBlank()) {
      return latestCommits(repositoryName, limit);
    }
    try (Session session = sessionFactory.openSession()) {
      SearchSession searchSession = Search.session(session);
      return searchSession
          .search(GitCommitIndex.class)
          .where(
              f ->
                  f.bool()
                      .must(f.match().field("repositoryName").matching(repositoryName))
                      .must(
                          f.simpleQueryString()
                              .fields("shortMessage", "fullMessage", "changedPaths", "changedText")
                              .matching(query)))
          .fetchHits(limit);
    }
  }

  /**
   * Find commits whose indexed path list contains the given path fragment.
   *
   * @param repositoryName logical repository name
   * @param pathFragment path fragment
   * @param limit maximum hits
   * @return matching commits
   */
  public List<GitCommitIndex> findByPath(String repositoryName, String pathFragment, int limit) {
    try (Session session = sessionFactory.openSession()) {
      return session
          .createQuery(
              "FROM GitCommitIndex c WHERE c.repositoryName = :repo AND c.changedPaths LIKE :path "
                  + "ORDER BY c.commitTime DESC",
              GitCommitIndex.class)
          .setParameter("repo", repositoryName)
          .setParameter("path", "%" + pathFragment + "%")
          .setMaxResults(limit)
          .getResultList();
    }
  }

  /**
   * Return commits by author email.
   *
   * @param repositoryName logical repository name
   * @param authorEmail author email
   * @param limit maximum hits
   * @return matching commits
   */
  public List<GitCommitIndex> findByAuthorEmail(String repositoryName, String authorEmail, int limit) {
    try (Session session = sessionFactory.openSession()) {
      return session
          .createQuery(
              "FROM GitCommitIndex c WHERE c.repositoryName = :repo AND c.authorEmail = :email "
                  + "ORDER BY c.commitTime DESC",
              GitCommitIndex.class)
          .setParameter("repo", repositoryName)
          .setParameter("email", authorEmail)
          .setMaxResults(limit)
          .getResultList();
    }
  }

  /**
   * Return commits in a timestamp range.
   *
   * @param repositoryName logical repository name
   * @param from inclusive lower bound
   * @param to inclusive upper bound
   * @param limit maximum hits
   * @return matching commits
   */
  public List<GitCommitIndex> findBetween(
      String repositoryName, Instant from, Instant to, int limit) {
    try (Session session = sessionFactory.openSession()) {
      return session
          .createQuery(
              "FROM GitCommitIndex c WHERE c.repositoryName = :repo "
                  + "AND c.commitTime >= :from AND c.commitTime <= :to ORDER BY c.commitTime DESC",
              GitCommitIndex.class)
          .setParameter("repo", repositoryName)
          .setParameter("from", from)
          .setParameter("to", to)
          .setMaxResults(limit)
          .getResultList();
    }
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

  private List<GitCommitIndex> latestCommits(String repositoryName, int limit) {
    try (Session session = sessionFactory.openSession()) {
      return session
          .createQuery(
              "FROM GitCommitIndex c WHERE c.repositoryName = :repo ORDER BY c.commitTime DESC",
              GitCommitIndex.class)
          .setParameter("repo", repositoryName)
          .setMaxResults(limit)
          .getResultList();
    }
  }
}
