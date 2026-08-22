/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitReflogEntity;
import io.github.carstenartur.jgit.storage.hibernate.queue.DurableStripedWriteQueue;
import io.github.carstenartur.jgit.storage.hibernate.refs.DurableReflogWriter;
import io.github.carstenartur.jgit.storage.hibernate.refs.ReflogAppendCommand;
import io.github.carstenartur.jgit.storage.hibernate.refs.ReflogAppendResult;
import io.github.carstenartur.jgit.storage.hibernate.refs.ReflogAppendResult.Status;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.lib.ObjectId;
import org.hibernate.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mssqlserver.MSSQLServerContainer;

/** Retains database-native evidence for the first Git-aware durable batch processor. */
@Testcontainers(disabledWithoutDocker = true)
@EnabledIfSystemProperty(
    named = ReflogBatchNativeTelemetryTest.ENABLED_PROPERTY,
    matches = "true")
class ReflogBatchNativeTelemetryTest {

  static final String ENABLED_PROPERTY =
      "jgit.storage.benchmark.reflog-batch.native.enabled";
  private static final int[] BATCH_SIZES = {1, 10, 50};
  private static final Instant WHEN = Instant.parse("2026-08-22T00:00:00Z");

  @Container
  static final PostgreSQLContainer<?> POSTGRESQL =
      new PostgreSQLContainer<>("postgres:17.10-alpine")
          .withDatabaseName("jgit_reflog_batch")
          .withUsername("benchmark")
          .withPassword("benchmark");

  @Container
  static final MSSQLServerContainer SQL_SERVER =
      new MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-CU20-ubuntu-22.04")
          .acceptLicense();

  @Test
  void writesCrossDatabaseApplyAndReplayEvidence() throws Exception {
    Path ndjson =
        Path.of(
                System.getProperty(
                    DatabaseTelemetryCollectors.OUTPUT_PROPERTY,
                    "target/reflog-batch-native/reflog-batch-database-telemetry.ndjson"))
            .toAbsolutePath();
    Path aggregate =
        ndjson.resolveSibling("reflog-batch-database-telemetry.json");
    Files.createDirectories(ndjson.getParent());
    Files.deleteIfExists(ndjson);
    Files.deleteIfExists(aggregate);

    int observations = 0;
    for (DatabaseTarget target : targets()) {
      observations += measure(target, ndjson);
    }
    DatabaseTelemetryJson.writeAggregate(ndjson, aggregate);

    assertEquals(12, observations);
    assertTrue(Files.size(ndjson) > 100);
    assertTrue(Files.size(aggregate) > 100);
  }

  private static int measure(DatabaseTarget target, Path ndjson) throws Exception {
    try (HibernateSessionFactoryProvider provider =
            new HibernateSessionFactoryProvider(target.hibernateProperties());
        DatabaseTelemetryCollector collector =
            DatabaseTelemetryCollectors.create(
                target.backend(),
                true,
                target.jdbcUrl(),
                target.username(),
                target.password())) {
      int observations = 0;
      for (int batchSize : BATCH_SIZES) {
        String repositoryName =
            "native-reflog-batch-" + target.backend() + "-" + batchSize;
        try (HibernateRepository repository =
            HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
          repository.create(true);
        }
        List<ReflogAppendCommand> commands =
            commands(target.backend(), batchSize);

        observations +=
            runPhase(
                target,
                provider,
                collector,
                ndjson,
                repositoryName,
                batchSize,
                "first-apply",
                Status.APPENDED,
                commands);
        assertEquals(batchSize, rowCount(provider, repositoryName));

        observations +=
            runPhase(
                target,
                provider,
                collector,
                ndjson,
                repositoryName,
                batchSize,
                "idempotent-replay",
                Status.ALREADY_APPLIED,
                commands);
        assertEquals(batchSize, rowCount(provider, repositoryName));
      }
      return observations;
    }
  }

  private static int runPhase(
      DatabaseTarget target,
      HibernateSessionFactoryProvider provider,
      DatabaseTelemetryCollector collector,
      Path ndjson,
      String repositoryName,
      int batchSize,
      String phase,
      Status expectedStatus,
      List<ReflogAppendCommand> commands)
      throws Exception {
    DatabaseTelemetrySnapshot before = collector.capture();
    long startedAt = System.nanoTime();
    List<ReflogAppendResult> results =
        appendBatch(provider, repositoryName, batchSize, commands);
    long elapsedNanos = System.nanoTime() - startedAt;
    DatabaseTelemetrySnapshot after = collector.capture();
    DatabaseTelemetryDelta delta = before.deltaTo(after);

    assertEquals(batchSize, results.size());
    assertTrue(results.stream().allMatch(result -> result.status() == expectedStatus));
    if ("first-apply".equals(phase)) {
      String requiredCounter =
          "postgresql".equals(target.backend())
              ? "postgresql.wal.insert_lsn_bytes"
              : "sqlserver.io.log.bytes_written";
      Long physicalWriteBytes = delta.counters().get(requiredCounter);
      assertTrue(
          physicalWriteBytes != null && physicalWriteBytes > 0,
          () ->
              "Missing positive "
                  + requiredCounter
                  + " for "
                  + target.backend()
                  + " batch "
                  + batchSize
                  + ": "
                  + delta);
    }

    DatabaseTelemetryJson.appendNdjson(
        ndjson,
        new DatabaseTelemetryObservation(
            Map.of(
                "backend", target.backend(),
                "batchSize", Integer.toString(batchSize),
                "elapsedNanos", Long.toString(elapsedNanos),
                "expectedStatus", expectedStatus.name(),
                "operation", "queryable-reflog-append",
                "phase", phase,
                "repositoryScope", "single-logical-repository",
                "transactionContract", "repository-lock-one-transaction"),
            delta));
    return 1;
  }

  private static List<ReflogAppendResult> appendBatch(
      HibernateSessionFactoryProvider provider,
      String repositoryName,
      int batchSize,
      List<ReflogAppendCommand> commands)
      throws Exception {
    DurableStripedWriteQueue.Limits limits =
        new DurableStripedWriteQueue.Limits(
            1,
            100,
            4L * 1024 * 1024,
            batchSize,
            4L * 1024 * 1024,
            Duration.ofMinutes(1),
            Duration.ofSeconds(10));
    try (DurableReflogWriter writer =
        new DurableReflogWriter(provider.getSessionFactory(), limits)) {
      List<DurableStripedWriteQueue.Submission<ReflogAppendResult>> submissions =
          new ArrayList<>(commands.size());
      for (ReflogAppendCommand command : commands) {
        submissions.add(writer.append(repositoryName, command));
      }
      List<ReflogAppendResult> results = new ArrayList<>(commands.size());
      for (DurableStripedWriteQueue.Submission<ReflogAppendResult> submission : submissions) {
        results.add(submission.completion().get(30, TimeUnit.SECONDS));
        assertEquals(batchSize, submission.batchSize());
      }
      assertEquals(commands.size(), writer.metrics().completed());
      assertEquals(1, writer.metrics().batches());
      return List.copyOf(results);
    }
  }

  private static List<ReflogAppendCommand> commands(String backend, int batchSize) {
    List<ReflogAppendCommand> commands = new ArrayList<>(batchSize);
    ObjectId oldId = ObjectId.zeroId();
    for (int index = 0; index < batchSize; index++) {
      ObjectId newId = objectId(batchSize * 1_000 + index + 1);
      commands.add(
          new ReflogAppendCommand(
              backend + ":" + batchSize + ":" + index,
              "refs/heads/main",
              oldId,
              newId,
              "Native Batch",
              "native-batch@example.invalid",
              WHEN.plusSeconds(index),
              "batch " + batchSize + " entry " + index));
      oldId = newId;
    }
    return List.copyOf(commands);
  }

  private static ObjectId objectId(int value) {
    return ObjectId.fromString("%040x".formatted(value));
  }

  private static long rowCount(
      HibernateSessionFactoryProvider provider, String repositoryName) {
    try (Session session = provider.getSessionFactory().openSession()) {
      return session
          .createQuery(
              "SELECT COUNT(r) FROM GitReflogEntity r WHERE r.repositoryName = :repo",
              Long.class)
          .setParameter("repo", repositoryName)
          .getSingleResult();
    }
  }

  private static List<DatabaseTarget> targets() {
    return List.of(
        new DatabaseTarget(
            "postgresql",
            POSTGRESQL.getJdbcUrl(),
            POSTGRESQL.getUsername(),
            POSTGRESQL.getPassword(),
            "org.postgresql.Driver",
            "org.hibernate.dialect.PostgreSQLDialect"),
        new DatabaseTarget(
            "sqlserver",
            SQL_SERVER.getJdbcUrl(),
            SQL_SERVER.getUsername(),
            SQL_SERVER.getPassword(),
            "com.microsoft.sqlserver.jdbc.SQLServerDriver",
            "org.hibernate.dialect.SQLServerDialect"));
  }

  private record DatabaseTarget(
      String backend,
      String jdbcUrl,
      String username,
      String password,
      String driver,
      String dialect) {

    private Properties hibernateProperties() {
      Properties properties = new Properties();
      properties.put("hibernate.connection.url", jdbcUrl);
      properties.put("hibernate.connection.username", username);
      properties.put("hibernate.connection.password", password);
      properties.put("hibernate.connection.driver_class", driver);
      properties.put("hibernate.dialect", dialect);
      properties.put("hibernate.hbm2ddl.auto", "create-drop");
      properties.put("hibernate.show_sql", "false");
      properties.put("hibernate.format_sql", "false");
      properties.put("hibernate.search.enabled", "false");
      properties.put("hibernate.connection.pool_size", "4");
      return properties;
    }
  }
}
