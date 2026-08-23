/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.refs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.eclipse.jgit.lib.ObjectId;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;

class HibernateReflogBatchProcessorQueryCountH2Test {

  private static final Instant WHEN = Instant.parse("2026-08-23T00:00:00Z");

  @Test
  void latestRefLookupUsesConstantOrmQueryCountForMultiRefBatches() throws Exception {
    long singleRefQueries =
        executeAndCountQueries(
            List.of(command("delivery-1", "refs/heads/ref-1", id(1))));
    long multiRefQueries =
        executeAndCountQueries(
            List.of(
                command("delivery-1", "refs/heads/ref-1", id(1)),
                command("delivery-2", "refs/heads/ref-2", id(2)),
                command("delivery-3", "refs/heads/ref-3", id(3)),
                command("delivery-4", "refs/heads/ref-4", id(4)),
                command("delivery-5", "refs/heads/ref-5", id(5)),
                command("delivery-6", "refs/heads/ref-6", id(6)),
                command("delivery-7", "refs/heads/ref-7", id(7)),
                command("delivery-8", "refs/heads/ref-8", id(8))));

    assertTrue(singleRefQueries > 0, "Hibernate statistics must observe the validation queries");
    assertEquals(
        singleRefQueries,
        multiRefQueries,
        "adding refs to one JDBC batch must not add one latest-history query per ref");
  }

  private static long executeAndCountQueries(List<ReflogAppendCommand> commands)
      throws Exception {
    String databaseName = "reflog-query-count-" + UUID.randomUUID();
    String repositoryName = "repository-" + UUID.randomUUID();
    HibernateSessionFactoryProvider provider =
        new HibernateSessionFactoryProvider(h2Properties(databaseName));
    try {
      try (HibernateRepository repository =
          HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
        repository.create(true);
      }

      Statistics statistics = provider.getSessionFactory().getStatistics();
      statistics.clear();
      HibernateReflogBatchProcessor processor =
          new HibernateReflogBatchProcessor(provider.getSessionFactory());
      processor.execute(repositoryName, commands);
      return statistics.getQueryExecutionCount();
    } finally {
      provider.close();
    }
  }

  private static ReflogAppendCommand command(
      String deliveryId, String refName, ObjectId newId) {
    return new ReflogAppendCommand(
        deliveryId,
        refName,
        ObjectId.zeroId(),
        newId,
        "Batch User",
        "batch@example.invalid",
        WHEN,
        "append " + refName);
  }

  private static ObjectId id(int value) {
    return ObjectId.fromString("%040x".formatted(value));
  }

  private static Properties h2Properties(String databaseName) {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:" + databaseName + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    properties.put("hibernate.generate_statistics", "true");
    properties.put("hibernate.connection.pool_size", "4");
    return properties;
  }
}
