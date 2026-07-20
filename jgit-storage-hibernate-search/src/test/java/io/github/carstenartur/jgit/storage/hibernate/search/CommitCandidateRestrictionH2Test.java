/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.search;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitHistoryQuery;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.junit.jupiter.api.Test;

/** Verifies exact candidate filtering before full-text ranking or structured result limiting. */
class CommitCandidateRestrictionH2Test {

  private static final AtomicInteger TEST_COUNTER = new AtomicInteger();
  private static final String REPOSITORY_NAME = "candidate-query";

  @Test
  void appliesExactCandidatesInsideFullTextAndStructuredQueries() {
    try (HibernateSessionFactoryProvider provider =
        new HibernateSessionFactoryProvider(
            h2Properties("commit-candidates-" + TEST_COUNTER.incrementAndGet()),
            SearchEntities.annotatedClasses())) {
      persist(
          provider,
          commit(
              "1111111111111111111111111111111111111111",
              "Initial threshold",
              "alice@example.com",
              "2026-01-01T00:00:00Z",
              "services/fraud/rules.yaml",
              "threshold: 1000"),
          commit(
              "2222222222222222222222222222222222222222",
              "Tune threshold",
              "alice@example.com",
              "2026-02-01T00:00:00Z",
              "services/fraud/rules.yaml",
              "threshold: 500"),
          commit(
              "3333333333333333333333333333333333333333",
              "Profile theme",
              "alice@example.com",
              "2026-03-01T00:00:00Z",
              "services/profile/ui.yaml",
              "theme: dark"),
          commit(
              "4444444444444444444444444444444444444444",
              "Emergency threshold",
              "bob@example.com",
              "2026-04-01T00:00:00Z",
              "services/fraud/rules.yaml",
              "threshold: 100"));

      GitHistorySearchService history =
          new GitHistorySearchService(provider.getSessionFactory());

      assertEquals(
          List.of("1111111111111111111111111111111111111111"),
          objectIds(
              history.findChanges(
                  CommitHistoryQuery.forRepository(REPOSITORY_NAME)
                      .matchingText("threshold")
                      .restrictedToObjectIds(
                          List.of(
                              "1111111111111111111111111111111111111111",
                              "3333333333333333333333333333333333333333"))
                      .limit(10)
                      .build())));

      assertEquals(
          List.of("3333333333333333333333333333333333333333"),
          objectIds(
              history.findChanges(
                  CommitHistoryQuery.forRepository(REPOSITORY_NAME)
                      .authoredBy("alice@example.com")
                      .restrictedToObjectIds(
                          List.of(
                              "1111111111111111111111111111111111111111",
                              "3333333333333333333333333333333333333333"))
                      .limit(1)
                      .build())),
          "candidate filtering must happen before newest-first limiting");

      assertEquals(
          List.of("1111111111111111111111111111111111111111"),
          objectIds(
              history.findChanges(
                  CommitHistoryQuery.forRepository(REPOSITORY_NAME)
                      .authoredBy("alice@example.com")
                      .touchingPath("fraud")
                      .restrictedToObjectIds(
                          List.of(
                              "1111111111111111111111111111111111111111",
                              "3333333333333333333333333333333333333333"))
                      .limit(1)
                      .build())));

      assertEquals(
          List.of(),
          history.findChanges(
              CommitHistoryQuery.forRepository(REPOSITORY_NAME)
                  .matchingText("threshold")
                  .restrictedToObjectIds(List.of())
                  .limit(10)
                  .build()));
      assertEquals(
          List.of(),
          history.findChanges(
              CommitHistoryQuery.forRepository(REPOSITORY_NAME)
                  .authoredBy("alice@example.com")
                  .restrictedToObjectIds(List.of())
                  .limit(10)
                  .build()));
    }
  }

  private static void persist(
      HibernateSessionFactoryProvider provider, GitCommitIndex... commits) {
    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      for (GitCommitIndex commit : commits) {
        session.persist(commit);
      }
      transaction.commit();
    }
  }

  private static GitCommitIndex commit(
      String objectId,
      String message,
      String authorEmail,
      String timestamp,
      String changedPath,
      String changedText) {
    GitCommitIndex commit = new GitCommitIndex();
    commit.setRepositoryName(REPOSITORY_NAME);
    commit.setObjectId(objectId);
    commit.setShortMessage(message);
    commit.setFullMessage(message);
    commit.setAuthorName(authorEmail.substring(0, authorEmail.indexOf('@')));
    commit.setAuthorEmail(authorEmail);
    commit.setCommitTime(Instant.parse(timestamp));
    commit.setChangedPaths(changedPath);
    commit.setChangedText(changedText);
    return commit;
  }

  private static List<String> objectIds(List<GitCommitIndex> commits) {
    return commits.stream().map(GitCommitIndex::getObjectId).toList();
  }

  private static Properties h2Properties(String databaseName) {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url", "jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-heap");
    return properties;
  }
}
