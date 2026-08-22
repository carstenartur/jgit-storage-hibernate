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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
 * <p>This processor deliberately does not combine complete pushes or mutate refs. It is the narrow
 * first Git-semantic batch requested by the durable queue contract: an append-only projection whose
 * ordering, idempotency and rollback behavior can be proven independently.
 */
public final class HibernateReflogBatchProcessor
    implements DurableStripedWriteQueue.DurableBatchProcessor<
        ReflogAppendCommand, ReflogAppendResult> {

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
    for (ReflogAppendCommand command : commands) {
      if (!existing.containsKey(command.deliveryId())) {
        refsWithNewCommands.add(command.refName());
      }
    }
    Map<String, String> latestNewIdByRef =
        loadLatestNewIds(session, repositoryName, refsWithNewCommands);
    validateNewCommandChains(commands, existing, latestNewIdByRef);

    Map<String, GitReflogEntity> inserted = new HashMap<>();
    for (ReflogAppendCommand command : commands) {
      if (existing.containsKey(command.deliveryId())) {
        continue;
      }
      GitReflogEntity entity = newEntry(repositoryName, command);
      session.persist(entity);
      inserted.put(command.deliveryId(), entity);
    }
    // Force every SQL/constraint failure inside the owning transaction before results are returned.
    session.flush();

    List<ReflogAppendResult> results = new ArrayList<>(commands.size());
    for (ReflogAppendCommand command : commands) {
      Status status =
          existing.containsKey(command.deliveryId())
              ? Status.ALREADY_APPLIED
              : Status.APPENDED;
      if (status == Status.APPENDED && inserted.get(command.deliveryId()).getId() == null) {
        throw new IOException(
            "Hibernate did not assign a reflog identity for " + command.deliveryId());
      }
      results.add(new ReflogAppendResult(command.deliveryId(), status));
    }
    return List.copyOf(results);
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
    Map<String, String> result = new HashMap<>();
    for (String refName : refNames) {
      List<String> latest =
          session
              .createQuery(
                  "SELECT r.newId FROM GitReflogEntity r WHERE r.repositoryName = :repo "
                      + "AND r.refNameKey = :refKey AND r.refName = :ref "
                      + "ORDER BY r.id DESC",
                  String.class)
              .setParameter("repo", repositoryName)
              .setParameter("refKey", GitReflogEntity.refNameKey(refName))
              .setParameter("ref", refName)
              .setMaxResults(1)
              .getResultList();
      if (!latest.isEmpty()) {
        result.put(refName, latest.getFirst());
      }
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

  private static GitReflogEntity newEntry(
      String repositoryName, ReflogAppendCommand command) {
    GitReflogEntity entry = new GitReflogEntity();
    entry.setRepositoryName(repositoryName);
    entry.setDeliveryId(command.deliveryId());
    entry.setRefName(command.refName());
    entry.setOldId(command.oldIdName());
    entry.setNewId(command.newIdName());
    entry.setWhoName(command.whoName());
    entry.setWhoEmail(command.whoEmail());
    entry.setWhen(command.when());
    entry.setMessage(command.message());
    return entry;
  }

  private static String normalize(String value) {
    return value == null ? "" : value;
  }
}
