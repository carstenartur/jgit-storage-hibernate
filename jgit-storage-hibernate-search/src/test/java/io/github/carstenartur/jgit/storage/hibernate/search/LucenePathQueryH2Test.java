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
import io.github.carstenartur.jgit.storage.hibernate.search.service.CommitSearchHit;
import io.github.carstenartur.jgit.storage.hibernate.search.service.GitHistorySearchService;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;

class LucenePathQueryH2Test {

  private static final String REPOSITORY = "path-query-repository";
  private static final String RULES_PATH = "services/payments/fraud/rules.yaml";
  private static final String LIMITS_PATH = "services/payments/fraud/limits.yaml";

  @Test
  void analyzedPathTermsUseLuceneWithoutSqlEntityHydrationAndSortNewestFirst() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      Instant older = Instant.parse("2026-01-01T00:00:00Z");
      Instant newer = Instant.parse("2026-02-01T00:00:00Z");
      persist(provider, objectId(1), RULES_PATH, older);
      persist(provider, objectId(2), LIMITS_PATH, newer);
      persist(provider, objectId(3), "docs/architecture.md", newer.plusSeconds(60));

      Statistics statistics = provider.getSessionFactory().getStatistics();
      statistics.clear();
      List<CommitSearchHit> hits =
          new GitHistorySearchService(provider.getSessionFactory())
              .findChangeSummaries(
                  CommitHistoryQuery.forRepository(REPOSITORY)
                      .touchingPathTerms("SERVICES payments fraud")
                      .limit(10)
                      .build());

      assertEquals(List.of(objectId(2), objectId(1)), objectIds(hits));
      assertEquals(0L, statistics.getEntityLoadCount());
      assertEquals(0L, statistics.getQueryExecutionCount());
    }
  }

  @Test
  void exactPathMatchesOneCompleteChangedPath() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      persist(provider, objectId(1), RULES_PATH, Instant.parse("2026-01-01T00:00:00Z"));
      persist(provider, objectId(2), LIMITS_PATH, Instant.parse("2026-02-01T00:00:00Z"));

      List<CommitSearchHit> hits =
          new GitHistorySearchService(provider.getSessionFactory())
              .findChangeSummaries(
                  CommitHistoryQuery.forRepository(REPOSITORY)
                      .touchingExactPath(RULES_PATH)
                      .limit(10)
                      .build());

      assertEquals(List.of(objectId(1)), objectIds(hits));
    }
  }

  @Test
  void legacyPathFragmentRetainsRelationalLiteralSemantics() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      Instant older = Instant.parse("2026-01-01T00:00:00Z");
      Instant newer = Instant.parse("2026-02-01T00:00:00Z");
      persist(provider, objectId(1), RULES_PATH, older);
      persist(provider, objectId(2), LIMITS_PATH, newer);

      Statistics statistics = provider.getSessionFactory().getStatistics();
      statistics.clear();
      List<CommitSearchHit> hits =
          new GitHistorySearchService(provider.getSessionFactory())
              .findChangeSummaries(
                  CommitHistoryQuery.forRepository(REPOSITORY)
                      .touchingPath("PAYMENTS/fraud")
                      .limit(10)
                      .build());

      assertEquals(List.of(objectId(2), objectId(1)), objectIds(hits));
      assertEquals(0L, statistics.getEntityLoadCount());
      assertEquals(1L, statistics.getQueryExecutionCount());
    }
  }

  private static void persist(
      HibernateSessionFactoryProvider provider,
      String objectId,
      String path,
      Instant committerTime) {
    GitCommitIndex projection = new GitCommitIndex();
    projection.setRepositoryName(REPOSITORY);
    projection.setObjectId(objectId);
    projection.setShortMessage("Changed " + path);
    projection.setFullMessage("Changed path " + path);
    projection.setAuthorName("Alice");
    projection.setAuthorEmail("alice@example.com");
    projection.setAuthorTime(committerTime.minusSeconds(60));
    projection.setCommitterName("Release bot");
    projection.setCommitterEmail("release@example.com");
    projection.setCommitterTime(committerTime);
    projection.setChangedPaths(path);
    projection.setChangedText("content for " + path);

    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      session.persist(projection);
      transaction.commit();
    }
  }

  private static List<String> objectIds(List<CommitSearchHit> hits) {
    return hits.stream().map(CommitSearchHit::objectId).toList();
  }

  private static String objectId(int value) {
    return String.format("%040d", value);
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:lucene-path-query-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.generate_statistics", "true");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-heap");
    properties.put("hibernate.search.automatic_indexing.synchronization.strategy", "sync");
    return new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses());
  }
}
