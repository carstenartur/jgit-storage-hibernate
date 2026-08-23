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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.entity.GitReflogEntity;
import io.github.carstenartur.jgit.storage.hibernate.queue.DurableStripedWriteQueue;
import io.github.carstenartur.jgit.storage.hibernate.refs.HibernateReflogBatchProcessor.RetryAdvice;
import io.github.carstenartur.jgit.storage.hibernate.refs.ReflogAppendResult.Status;
import io.github.carstenartur.jgit.storage.hibernate.refs.ReflogBatchRejectedException.Reason;
import io.github.carstenartur.jgit.storage.hibernate.repository.HibernateRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.lib.ObjectId;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.junit.jupiter.api.Test;

class HibernateReflogBatchProcessorH2Test {

  private static final Instant WHEN = Instant.parse("2026-08-22T00:00:00Z");

  @Test
  void queueCommitsOneOrderedGitAwareBatchBeforeAcknowledgement() throws Exception {
    try (Fixture fixture = new Fixture()) {
      List<ReflogAppendCommand> commands =
          List.of(
              command("delivery-1", ObjectId.zeroId(), id(1), "first"),
              command("delivery-2", id(1), id(2), "second"),
              command("delivery-3", id(2), id(3), "third"));
      DurableStripedWriteQueue.Limits limits = limits(3);

      try (DurableReflogWriter writer =
          new DurableReflogWriter(fixture.provider.getSessionFactory(), limits)) {
        List<DurableStripedWriteQueue.Submission<ReflogAppendResult>> submissions =
            commands.stream()
                .map(
                    command -> {
                      try {
                        return writer.append(fixture.repositoryName, command);
                      } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(failure);
                      }
                    })
                .toList();

        for (int index = 0; index < submissions.size(); index++) {
          ReflogAppendResult result =
              submissions.get(index).completion().get(5, TimeUnit.SECONDS);
          assertEquals(commands.get(index).deliveryId(), result.deliveryId());
          assertEquals(Status.APPENDED, result.status());
          assertEquals(3, submissions.get(index).batchSize());
        }
        assertEquals(3, writer.metrics().completed());
      }

      List<GitReflogEntity> rows = fixture.rows();
      assertEquals(3, rows.size());
      for (int index = 0; index < rows.size(); index++) {
        assertEquals(commands.get(index).deliveryId(), rows.get(index).getDeliveryId());
        assertEquals(commands.get(index).oldIdName(), rows.get(index).getOldId());
        assertEquals(commands.get(index).newIdName(), rows.get(index).getNewId());
      }
    }
  }

  @Test
  void exactReplayIsIdempotentAndDoesNotCreateDuplicateRows() throws Exception {
    try (Fixture fixture = new Fixture()) {
      HibernateReflogBatchProcessor processor =
          new HibernateReflogBatchProcessor(fixture.provider.getSessionFactory());
      List<ReflogAppendCommand> commands =
          List.of(
              command("delivery-1", ObjectId.zeroId(), id(1), "first"),
              command("delivery-2", id(1), id(2), "second"));

      assertEquals(
          List.of(Status.APPENDED, Status.APPENDED),
          processor.execute(fixture.repositoryName, commands).stream()
              .map(ReflogAppendResult::status)
              .toList());
      assertEquals(
          List.of(Status.ALREADY_APPLIED, Status.ALREADY_APPLIED),
          processor.execute(fixture.repositoryName, commands).stream()
              .map(ReflogAppendResult::status)
              .toList());
      assertEquals(2, fixture.rows().size());
    }
  }

  @Test
  void conflictingDeliveryIdRejectsWholeBatchBeforeAnyMutation() throws Exception {
    try (Fixture fixture = new Fixture()) {
      HibernateReflogBatchProcessor processor =
          new HibernateReflogBatchProcessor(fixture.provider.getSessionFactory());
      ReflogAppendCommand committed =
          command("delivery-1", ObjectId.zeroId(), id(1), "first");
      processor.execute(fixture.repositoryName, List.of(committed));

      ReflogAppendCommand newCommand =
          command("delivery-2", id(1), id(2), "second");
      ReflogAppendCommand conflicting =
          command("delivery-1", ObjectId.zeroId(), id(1), "changed-message");
      ReflogBatchRejectedException failure =
          assertThrows(
              ReflogBatchRejectedException.class,
              () ->
                  processor.execute(
                      fixture.repositoryName, List.of(newCommand, conflicting)));

      assertEquals(Reason.DELIVERY_ID_REUSED_WITH_DIFFERENT_PAYLOAD, failure.reason());
      assertEquals(RetryAdvice.DO_NOT_RETRY, HibernateReflogBatchProcessor.retryAdvice(failure));
      assertEquals(1, fixture.rows().size());
    }
  }

  @Test
  void nonContiguousRefHistoryRejectsWholeBatch() throws Exception {
    try (Fixture fixture = new Fixture()) {
      HibernateReflogBatchProcessor processor =
          new HibernateReflogBatchProcessor(fixture.provider.getSessionFactory());
      processor.execute(
          fixture.repositoryName,
          List.of(command("delivery-1", ObjectId.zeroId(), id(1), "first")));

      ReflogBatchRejectedException failure =
          assertThrows(
              ReflogBatchRejectedException.class,
              () ->
                  processor.execute(
                      fixture.repositoryName,
                      List.of(
                          command("delivery-2", ObjectId.zeroId(), id(2), "wrong-old"),
                          command("delivery-3", id(2), id(3), "third"))));

      assertEquals(Reason.NON_CONTIGUOUS_REF_HISTORY, failure.reason());
      assertEquals("delivery-2", failure.deliveryId());
      assertEquals(1, fixture.rows().size());
    }
  }

  @Test
  void databaseFailureRollsBackEveryCommandAndSameIdsCanBeRetried() throws Exception {
    try (Fixture fixture = new Fixture()) {
      fixture.executeSql(
          "ALTER TABLE git_reflog ADD CONSTRAINT reject_explode "
              + "CHECK (message <> 'explode')");
      List<ReflogAppendCommand> commands =
          List.of(
              command("delivery-1", ObjectId.zeroId(), id(1), "first"),
              command("delivery-2", id(1), id(2), "explode"));

      CompletionException firstFailure;
      CompletionException secondFailure;
      try (DurableReflogWriter writer =
          new DurableReflogWriter(fixture.provider.getSessionFactory(), limits(2))) {
        DurableStripedWriteQueue.Submission<ReflogAppendResult> first =
            writer.append(fixture.repositoryName, commands.get(0));
        DurableStripedWriteQueue.Submission<ReflogAppendResult> second =
            writer.append(fixture.repositoryName, commands.get(1));
        firstFailure = assertThrows(CompletionException.class, first.completion()::join);
        secondFailure = assertThrows(CompletionException.class, second.completion()::join);
        assertEquals(2, writer.metrics().failed());
      }

      assertEquals(
          RetryAdvice.RETRY_WITH_SAME_DELIVERY_IDS,
          HibernateReflogBatchProcessor.retryAdvice(firstFailure));
      assertEquals(
          RetryAdvice.RETRY_WITH_SAME_DELIVERY_IDS,
          HibernateReflogBatchProcessor.retryAdvice(secondFailure));
      assertTrue(fixture.rows().isEmpty(), "one failed insert must roll the first insert back");

      fixture.executeSql("ALTER TABLE git_reflog DROP CONSTRAINT reject_explode");
      HibernateReflogBatchProcessor retry =
          new HibernateReflogBatchProcessor(fixture.provider.getSessionFactory());
      assertEquals(
          List.of(Status.APPENDED, Status.APPENDED),
          retry.execute(fixture.repositoryName, commands).stream()
              .map(ReflogAppendResult::status)
              .toList());
      assertEquals(2, fixture.rows().size());
    }
  }

  private static DurableStripedWriteQueue.Limits limits(int batchSize) {
    return new DurableStripedWriteQueue.Limits(
        1,
        100,
        1024 * 1024,
        batchSize,
        1024 * 1024,
        Duration.ofMinutes(1),
        Duration.ofSeconds(1));
  }

  private static ReflogAppendCommand command(
      String deliveryId, ObjectId oldId, ObjectId newId, String message) {
    return new ReflogAppendCommand(
        deliveryId,
        "refs/heads/main",
        oldId,
        newId,
        "Batch User",
        "batch@example.invalid",
        WHEN,
        message);
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
    properties.put("hibernate.connection.pool_size", "4");
    return properties;
  }

  private static final class Fixture implements AutoCloseable {
    private final String repositoryName = "reflog-batch-" + UUID.randomUUID();
    private final HibernateSessionFactoryProvider provider =
        new HibernateSessionFactoryProvider(h2Properties(repositoryName));

    private Fixture() throws Exception {
      try (HibernateRepository repository =
          HibernateRepository.create(provider.getSessionFactory(), repositoryName)) {
        repository.create(true);
      }
    }

    private List<GitReflogEntity> rows() {
      try (Session session = provider.getSessionFactory().openSession()) {
        return session
            .createQuery(
                "FROM GitReflogEntity r WHERE r.repositoryName = :repo "
                    + "AND r.deliveryId IS NOT NULL ORDER BY r.id",
                GitReflogEntity.class)
            .setParameter("repo", repositoryName)
            .getResultList();
      }
    }

    private void executeSql(String sql) {
      try (Session session = provider.getSessionFactory().openSession()) {
        Transaction transaction = session.beginTransaction();
        try {
          session.createNativeMutationQuery(sql).executeUpdate();
          transaction.commit();
        } catch (RuntimeException failure) {
          if (transaction.isActive()) {
            transaction.rollback();
          }
          throw failure;
        }
      }
    }

    @Override
    public void close() {
      provider.close();
    }
  }
}
