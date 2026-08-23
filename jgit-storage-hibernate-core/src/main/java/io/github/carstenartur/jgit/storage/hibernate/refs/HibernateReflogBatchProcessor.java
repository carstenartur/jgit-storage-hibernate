/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.refs;

import io.github.carstenartur.jgit.storage.hibernate.entity.GitReflogEntity;
import io.github.carstenartur.jgit.storage.hibernate.queue.DurableStripedWriteQueue;
import io.github.carstenartur.jgit.storage.hibernate.queue.HibernateDurableBatchProcessor;
import io.github.carstenartur.jgit.storage.hibernate.queue.HibernateDurableBatchProcessor.Locking;
import io.github.carstenartur.jgit.storage.hibernate.refs.ReflogAppendResult.Status;
import io.github.carstenartur.jgit.storage.hibernate.refs.ReflogBatchRejectedException.Reason;
import io.github.carstenartur.jgit.storage.hibernate.transaction.StorageOperationKind;
import java.io.IOException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

/**
 * Storage-specific atomic processor for append-only queryable reflog projection records.
 *
 * <p>Every queue batch is repository-homogeneous, acquires the same repository coordination row as
 * refs and pack publication, validates all delivery IDs and ref-history transitions before the first
 * insert, and commits all new entries in one transaction. Exact delivery-ID replays return {@link
 * Status#ALREADY_APPLIED}; conflicting reuses reject the complete batch.
 *
 * <p>New rows use one JDBC {@link PreparedStatement} batch. The identity primary key is deliberately
 * omitted because the durable result is the caller's delivery ID, not a database-generated row ID.
 * This avoids Hibernate's identity-generation batch split while retaining the same mapped table and
 * transaction.
 *
 * <p>This processor deliberately does not combine complete pushes or mutate refs. It is the narrow
 * first Git-semantic batch requested by the durable queue contract: an append-only projection whose
 * ordering, idempotency and rollback behavior can be proven independently.
 */
public final class HibernateReflogBatchProcessor
    implements DurableStripedWriteQueue.DurableBatchProcessor<
        ReflogAppendCommand, ReflogAppendResult> {

  private static final String INSERT_SQL =
      """
      INSERT INTO git_reflog
          (version, repository_name, delivery_id, ref_name, ref_name_key,
           old_id, new_id, who_name, who_email, who_when, message)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  /** Caller action after a failed batch. */
  public enum RetryAdvice {
    /** The immutable command is semantically invalid and must be reconciled or dead-lettered. */
    DO_NOT_RETRY,
    /** Re-submit the same command content with exactly the same delivery IDs. */
    RETRY_WITH_SAME_DELIVERY_IDS
  }

  private final HibernateDurableBatchProcessor<ReflogAppendCommand, ReflogAppendResult> delegate;

  /** Create a processor using one repository-locked Hibernate transaction per queue batch. */
  public HibernateReflogBatchProcessor(SessionFactory sessionFactory) {
    delegate =
        new HibernateDurableBatchProcessor<>(
            sessionFactory,
            StorageOperationKind.REFLOG_BATCH_WRITE,
            Locking.REPOSITORY,
            this::executeBatch);
  }

  /** Execute and commit one repository-homogeneous batch. */
  @Override
  public List<ReflogAppendResult> execute(
      String repositoryName, List<ReflogAppendCommand> commands) throws IOException {
    return delegate.execute(repositoryName, commands);
  }

  /**
   * Classify the caller-visible failure contract.
   *
   * <p>Database/transport failures can occur before commit, after rollback or after a commit whose
   * acknowledgement was lost. Delivery IDs make replay safe in every one of those cases. A {@link
   * ReflogBatchRejectedException}, in contrast, describes deterministic immutable-content or chain
   * disagreement and must not be retried unchanged.
   */
  public static RetryAdvice retryAdvice(Throwable failure) {
    Throwable current = Objects.requireNonNull(failure, "failure");
    while ((current instanceof CompletionException || current instanceof ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current instanceof ReflogBatchRejectedException
        ? RetryAdvice.DO_NOT_RETRY
        : RetryAdvice.RETRY_WITH_SAME_DELIVERY_IDS;
  }

  private List<ReflogAppendResult> executeBatch(
      Session session, String repositoryName, List<ReflogAppendCommand> commands)
      throws IOException {
    LinkedHashSet<String> deliveryIds = validateUniqueDeliveryIds(commands);
    Map<String, GitReflogEntity> existing =
        loadExistingByDeliveryId(session, repositoryName, deliveryIds);
    validateExistingPayloads(commands, existing);

    Set<String> refsWithNewCommands = new LinkedHashSet<>();
    List<ReflogAppendCommand> newCommands = new ArrayList<>();
    for (ReflogAppendCommand command : commands) {
      if (!existing.containsKey(command.deliveryId())) {
        refsWithNewCommands.add(command.refName());
        newCommands.add(command);
      }
    }
    Map<String, String> latestNewIdByRef =
        loadLatestNewIds(session, repositoryName, refsWithNewCommands);
    validateNewCommandChains(commands, existing, latestNewIdByRef);
    insertJdbcBatch(session, repositoryName, newCommands);

    List<ReflogAppendResult> results = new ArrayList<>(commands.size());
    for (ReflogAppendCommand command : commands) {
      Status status =
          existing.containsKey(command.deliveryId())
              ? Status.ALREADY_APPLIED
              : Status.APPENDED;
      results.add(new ReflogAppendResult(command.deliveryId(), status));
    }
    return List.copyOf(results);
  }

  private static void insertJdbcBatch(
      Session session, String repositoryName, List<ReflogAppendCommand> commands) {
    if (commands.isEmpty()) {
      return;
    }
    session.doWork(
        connection -> {
          try (PreparedStatement statement = connection.prepareStatement(INSERT_SQL)) {
            for (ReflogAppendCommand command : commands) {
              statement.setLong(1, 0L);
              statement.setString(2, repositoryName);
              statement.setString(3, command.deliveryId());
              statement.setString(4, command.refName());
              statement.setString(5, GitReflogEntity.refNameKey(command.refName()));
              statement.setString(6, command.oldIdName());
              statement.setString(7, command.newIdName());
              statement.setString(8, command.whoName());
              statement.setString(9, command.whoEmail());
              statement.setObject(
                  10, OffsetDateTime.ofInstant(command.when(), ZoneOffset.UTC));
              statement.setString(11, command.message());
              statement.addBatch();
            }
            validateBatchCounts(statement.executeBatch(), commands.size());
          }
        });
  }

  private static void validateBatchCounts(int[] counts, int expected) throws SQLException {
    if (counts.length != expected) {
      throw new SQLException(
          "JDBC reflog batch returned " + counts.length + " counts for " + expected + " rows");
    }
    for (int index = 0; index < counts.length; index++) {
      if (counts[index] == Statement.EXECUTE_FAILED || counts[index] == 0) {
        throw new SQLException(
            "JDBC reflog batch rejected row " + index + " with count " + counts[index]);
      }
    }
  }

  private static LinkedHashSet<String> validateUniqueDeliveryIds(
      List<ReflogAppendCommand> commands) throws ReflogBatchRejectedException {
    LinkedHashSet<String> deliveryIds = new LinkedHashSet<>();
    for (ReflogAppendCommand command : commands) {
      Objects.requireNonNull(command, "commands must not contain null");
      if (!deliveryIds.add(command.deliveryId())) {
        throw new ReflogBatchRejectedException(
            Reason.DUPLICATE_DELIVERY_ID_IN_BATCH,
            command.deliveryId(),
            command.refName(),
            "Batch repeats delivery ID " + command.deliveryId());
      }
    }
    return deliveryIds;
  }

  private static Map<String, GitReflogEntity> loadExistingByDeliveryId(
      Session session, String repositoryName, Set<String> deliveryIds)
      throws ReflogBatchRejectedException {
    if (deliveryIds.isEmpty()) {
      return Map.of();
    }
    List<GitReflogEntity> rows =
        session
            .createQuery(
                "FROM GitReflogEntity r WHERE r.repositoryName = :repo "
                    + "AND r.deliveryId IN :deliveryIds",
                GitReflogEntity.class)
            .setParameter("repo", repositoryName)
            .setParameterList("deliveryIds", deliveryIds)
            .getResultList();
    Map<String, GitReflogEntity> result = new LinkedHashMap<>();
    for (GitReflogEntity row : rows) {
      GitReflogEntity previous = result.put(row.getDeliveryId(), row);
      if (previous != null) {
        throw new ReflogBatchRejectedException(
            Reason.DUPLICATE_PERSISTED_DELIVERY_ID,
            row.getDeliveryId(),
            row.getRefName(),
            "Multiple committed reflog rows use delivery ID " + row.getDeliveryId());
      }
    }
    return result;
  }

  private static void validateExistingPayloads(
      List<ReflogAppendCommand> commands, Map<String, GitReflogEntity> existing)
      throws ReflogBatchRejectedException {
    for (ReflogAppendCommand command : commands) {
      GitReflogEntity row = existing.get(command.deliveryId());
      if (row != null && !samePayload(row, command)) {
        throw new ReflogBatchRejectedException(
            Reason.DELIVERY_ID_REUSED_WITH_DIFFERENT_PAYLOAD,
            command.deliveryId(),
            command.refName(),
            "Committed delivery ID "
                + command.deliveryId()
                + " was reused with different reflog content");
      }
    }
  }

  private static Map<String, String> loadLatestNewIds(
      Session session, String repositoryName, Set<String> refNames) {
    if (refNames.isEmpty()) {
      return Map.of();
    }

    Set<String> refNameKeys = new LinkedHashSet<>();
    for (String refName : refNames) {
      refNameKeys.add(GitReflogEntity.refNameKey(refName));
    }

    List<Object[]> latestRows =
        session
            .createQuery(
                """
                SELECT r.refName, r.newId
                FROM GitReflogEntity r
                WHERE r.repositoryName = :repo
                  AND r.refNameKey IN :refKeys
                  AND r.refName IN :refs
                  AND r.id = (
                    SELECT MAX(candidate.id)
                    FROM GitReflogEntity candidate
                    WHERE candidate.repositoryName = r.repositoryName
                      AND candidate.refNameKey = r.refNameKey
                      AND candidate.refName = r.refName
                  )
                """,
                Object[].class)
            .setParameter("repo", repositoryName)
            .setParameterList("refKeys", refNameKeys)
            .setParameterList("refs", refNames)
            .getResultList();

    Map<String, String> result = new HashMap<>();
    for (Object[] row : latestRows) {
      result.put((String) row[0], (String) row[1]);
    }
    return result;
  }

  private static void validateNewCommandChains(
      List<ReflogAppendCommand> commands,
      Map<String, GitReflogEntity> existing,
      Map<String, String> latestNewIdByRef)
      throws ReflogBatchRejectedException {
    Map<String, String> expectedOldIdByRef = new HashMap<>(latestNewIdByRef);
    for (ReflogAppendCommand command : commands) {
      if (existing.containsKey(command.deliveryId())) {
        continue;
      }
      String expectedOldId = expectedOldIdByRef.get(command.refName());
      if (expectedOldId != null && !expectedOldId.equals(command.oldIdName())) {
        throw new ReflogBatchRejectedException(
            Reason.NON_CONTIGUOUS_REF_HISTORY,
            command.deliveryId(),
            command.refName(),
            "Reflog command "
                + command.deliveryId()
                + " expected old ID "
                + expectedOldId
                + " but received "
                + command.oldIdName());
      }
      expectedOldIdByRef.put(command.refName(), command.newIdName());
    }
  }

  private static boolean samePayload(GitReflogEntity row, ReflogAppendCommand command) {
    return Objects.equals(row.getRefName(), command.refName())
        && Objects.equals(row.getOldId(), command.oldIdName())
        && Objects.equals(row.getNewId(), command.newIdName())
        && Objects.equals(normalize(row.getWhoName()), command.whoName())
        && Objects.equals(normalize(row.getWhoEmail()), command.whoEmail())
        && Objects.equals(row.getWhen(), command.when())
        && Objects.equals(row.getMessage(), command.message());
  }

  private static String normalize(String value) {
    return value == null ? "" : value;
  }
}
