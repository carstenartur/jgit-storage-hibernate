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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.search.entity.GitCommitIndex;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;

class CommitProjectionJdbcBatchH2Test {

  private static final int PROJECTIONS = 50;

  @Test
  void assignedProjectionKeysAllowOneRealJdbcInsertBatch() {
    try (HibernateSessionFactoryProvider provider = provider()) {
      Statistics statistics = provider.getSessionFactory().getStatistics();
      statistics.clear();
      SearchJdbcBatchEventListener.reset();

      try (Session session = provider.getSessionFactory().openSession()) {
        Transaction transaction = session.beginTransaction();
        session.setJdbcBatchSize(PROJECTIONS);
        for (int index = 0; index < PROJECTIONS; index++) {
          session.persist(projection(index));
        }
        transaction.commit();
      }

      SearchJdbcBatchEventListener.Snapshot jdbc = SearchJdbcBatchEventListener.snapshot();
      assertEquals(1L, jdbc.batches());
      assertEquals(0L, jdbc.statements());
      assertTrue(
          statistics.getPrepareStatementCount() <= 2,
          () -> "Expected one reusable insert statement, got "
              + statistics.getPrepareStatementCount());

      try (Session session = provider.getSessionFactory().openSession()) {
        List<GitCommitIndex> stored =
            session.createQuery("FROM GitCommitIndex", GitCommitIndex.class).getResultList();
        assertEquals(PROJECTIONS, stored.size());
        Set<String> keys = new HashSet<>();
        for (GitCommitIndex projection : stored) {
          assertNotNull(projection.getProjectionKey());
          assertTrue(keys.add(projection.getProjectionKey()));
        }
      }
    }
  }

  private static GitCommitIndex projection(int index) {
    Instant timestamp = Instant.parse("2026-08-08T00:00:00Z").plusSeconds(index);
    GitCommitIndex projection = new GitCommitIndex();
    projection.setRepositoryName("batch-repository");
    projection.setObjectId(String.format("%040d", index));
    projection.setShortMessage("Batch projection " + index);
    projection.setFullMessage("Batch projection " + index);
    projection.setAuthorName("Batch author");
    projection.setAuthorEmail("batch@example.invalid");
    projection.setAuthorTime(timestamp);
    projection.setCommitterName("Batch committer");
    projection.setCommitterEmail("batch@example.invalid");
    projection.setCommitterTime(timestamp);
    projection.setChangedPaths("path/" + index + ".txt");
    projection.setChangedText("content " + index);
    return projection;
  }

  private static HibernateSessionFactoryProvider provider() {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:search-jdbc-batch-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.generate_statistics", "true");
    properties.put("hibernate.jdbc.batch_size", Integer.toString(PROJECTIONS));
    properties.put("hibernate.order_inserts", "true");
    properties.put(
        "hibernate.session.events.auto", SearchJdbcBatchEventListener.class.getName());
    properties.put("hibernate.search.backend.type", "lucene");
    properties.put("hibernate.search.backend.directory.type", "local-heap");
    properties.put("hibernate.search.automatic_indexing.synchronization.strategy", "sync");
    return new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses());
  }
}
