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

class CommitSearchProjectionH2Test {

  @Test
  void fullTextSummaryComesFromLuceneWithoutHydratingTheLargeEntity() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      Instant when = Instant.parse("2026-08-08T00:00:00Z");
      persistProjection(provider, when);
      Statistics statistics = provider.getSessionFactory().getStatistics();
      statistics.clear();

      List<CommitSearchHit> hits =
          new GitHistorySearchService(provider.getSessionFactory())
              .searchCommitTextSummaries("projection-repository", "needle", 10);

      assertEquals(1, hits.size());
      assertEquals("0123456789012345678901234567890123456789", hits.get(0).objectId());
      assertEquals("Needle in a compact subject", hits.get(0).shortMessage());
      assertEquals("alice@example.com", hits.get(0).authorEmail());
      assertEquals(when, hits.get(0).committerTime());
      assertEquals(0L, statistics.getEntityLoadCount());
    }
  }

  @Test
  void structuredSummaryUsesAConstructorProjectionWithoutEntityHydration() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      Instant when = Instant.parse("2026-08-08T00:00:00Z");
      persistProjection(provider, when);
      Statistics statistics = provider.getSessionFactory().getStatistics();
      statistics.clear();

      List<CommitSearchHit> hits =
          new GitHistorySearchService(provider.getSessionFactory())
              .findChangeSummaries(
                  CommitHistoryQuery.forRepository("projection-repository")
                      .authoredBy("alice@example.com")
                      .committedBetween(when, when)
                      .limit(10)
                      .build());

      assertEquals(1, hits.size());
      assertEquals("Alice", hits.get(0).authorName());
      assertEquals("Release bot", hits.get(0).committerName());
      assertEquals(0L, statistics.getEntityLoadCount());
      assertEquals(1L, statistics.getQueryExecutionCount());
    }
  }

  private static void persistProjection(HibernateSessionFactoryProvider provider) {
    persistProjection(provider, Instant.parse("2026-08-08T00:00:00Z"));
  }

  private static void persistProjection(
      HibernateSessionFactoryProvider provider, Instant when) {
    GitCommitIndex projection = new GitCommitIndex();
    projection.setRepositoryName("projection-repository");
    projection.setObjectId("0123456789012345678901234567890123456789");
    projection.setShortMessage("Needle in a compact subject");
    projection.setFullMessage("Needle in a compact subject\n\nFull message body");
    projection.setAuthorName("Alice");
    projection.setAuthorEmail("alice@example.com");
    projection.setAuthorTime(when.minusSeconds(60));
    projection.setCommitterName("Release bot");
    projection.setCommitterEmail("release@example.com");
    projection.setCommitterTime(when);
    projection.setChangedPaths("services/payments/fraud/rules.yaml");
    projection.setChangedText("needle\n" + "large changed text ".repeat(4_000));

    try (Session session = provider.getSessionFactory().openSession()) {
      Transaction transaction = session.beginTransaction();
      session.persist(projection);
      transaction.commit();
    }
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:commit-search-projection-"
            + UUID.randomUUID()
            + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.generate_statistics", "true");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-heap");
    return new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses());
  }
}
