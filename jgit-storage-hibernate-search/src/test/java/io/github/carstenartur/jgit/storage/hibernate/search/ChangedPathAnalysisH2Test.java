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
import java.util.UUID;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;

/** Verifies field-specific analysis for changed Git paths. */
class ChangedPathAnalysisH2Test {

  private static final String REPOSITORY_NAME = "path-analysis";
  private static final String OBJECT_ID = "0123456789012345678901234567890123456789";

  @Test
  void matchesPathComponentsAcrossPunctuationAndCase() {
    try (HibernateSessionFactoryProvider provider =
        new HibernateSessionFactoryProvider(h2Properties(), SearchEntities.annotatedClasses())) {
      persistProjection(provider, projection());
      GitHistorySearchService history =
          new GitHistorySearchService(provider.getSessionFactory());

      assertEquals(List.of(OBJECT_ID), objectIds(search(history, "workflow")));
      assertEquals(List.of(OBJECT_ID), objectIds(search(history, "SRC main")));
      assertEquals(List.of(), objectIds(search(history, "missing")));
    }
  }

  private static List<GitCommitIndex> search(GitHistorySearchService history, String pathTerms) {
    return history.findChanges(
        CommitHistoryQuery.forRepository(REPOSITORY_NAME)
            .matchingText("checkpoint")
            .touchingPath(pathTerms)
            .limit(10)
            .build());
  }

  private static List<String> objectIds(List<GitCommitIndex> hits) {
    return hits.stream().map(GitCommitIndex::getObjectId).toList();
  }

  private static GitCommitIndex projection() {
    GitCommitIndex projection = new GitCommitIndex();
    projection.setRepositoryName(REPOSITORY_NAME);
    projection.setObjectId(OBJECT_ID);
    projection.setShortMessage("Path analysis checkpoint");
    projection.setFullMessage("Path analysis checkpoint");
    projection.setAuthorName("Path Tester");
    projection.setAuthorEmail("path@example.org");
    projection.setCommitTime(Instant.parse("2026-07-20T00:00:00Z"));
    projection.setChangedPaths("src/Main.java\nworkflow.dsl\nmodels/v2/Signal42.json");
    projection.setChangedText("node classifier");
    return projection;
  }

  private static void persistProjection(
      HibernateSessionFactoryProvider provider, GitCommitIndex projection) {
    try (Session session = provider.getSessionFactory().openSession()) {
      session.beginTransaction();
      session.persist(projection);
      session.getTransaction().commit();
    }
  }

  private static Properties h2Properties() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url", "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-heap");
    return properties;
  }
}
