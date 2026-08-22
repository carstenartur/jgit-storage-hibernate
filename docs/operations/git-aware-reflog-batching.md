# Git-aware atomic reflog projection batches

`DurableReflogWriter` is the first storage-specific command processor built on the generic `DurableStripedWriteQueue`. It combines compatible **queryable reflog projection records** for one logical repository into one repository-locked Hibernate transaction.

The scope is intentionally narrow. It does not combine complete pushes, publish packs or mutate refs. Those operations have additional compare-and-set, generation and replacement semantics and must not be inferred from an append-only projection contract.

## Command and delivery identity

Every `ReflogAppendCommand` contains immutable reflog content plus a caller-supplied `deliveryId`:

```java
ReflogAppendCommand command = ReflogAppendCommand.from(
    "receiver-partition-3:offset-18421",
    "refs/heads/main",
    oldId,
    newId,
    actor,
    "receive: update main");
```

The delivery ID must originate in a durable source such as a broker offset, outbox row or replayable receiver record. It is persisted with the queryable reflog row. A retry after an unknown commit outcome must use the **same delivery ID and exactly the same immutable content**.

Timestamps are normalized to millisecond precision before persistence so exact replay comparison is stable across PostgreSQL, SQL Server, H2 and HSQLDB timestamp mappings.

## Atomic commit contract

For each repository-homogeneous queue batch, `HibernateReflogBatchProcessor`:

1. opens one Hibernate transaction;
2. acquires the same repository coordination row used by ref, pack and maintenance publication;
3. rejects duplicate delivery IDs inside the in-memory batch;
4. loads any already committed delivery IDs;
5. verifies that committed replays contain exactly the same ref, object IDs, actor, timestamp and message;
6. loads the latest committed queryable reflog entry for every affected ref;
7. validates all new `oldId -> newId` transitions in command order before the first insert;
8. persists every new entry and flushes all SQL/constraint failures inside the owning transaction;
9. commits once;
10. completes queue futures only after commit.

A result is returned in the same order as the submitted commands:

- `APPENDED` means this transaction created the row;
- `ALREADY_APPLIED` means an identical delivery ID and payload were already committed.

Legacy and standalone queryable reflog entries remain valid without a delivery ID. Once a ref already has a queryable reflog history, every new batched entry must continue from its latest committed `newId`. For a ref with no prior projection entry, the first batched record may establish a mid-history starting point.

## Failure, retry and rollback contract

`ReflogBatchRejectedException` describes deterministic semantic disagreement. Its reasons are stable:

- `DUPLICATE_DELIVERY_ID_IN_BATCH`;
- `DELIVERY_ID_REUSED_WITH_DIFFERENT_PAYLOAD`;
- `DUPLICATE_PERSISTED_DELIVERY_ID`;
- `NON_CONTIGUOUS_REF_HISTORY`.

These failures are **not retryable without reconciliation**. The complete transaction rolls back before any result can report success.

Infrastructure failures are different. A connection loss can occur before commit, during rollback or after the database committed but before the caller received acknowledgement. `HibernateReflogBatchProcessor.retryAdvice(failure)` therefore returns:

- `DO_NOT_RETRY` for semantic batch rejection;
- `RETRY_WITH_SAME_DELIVERY_IDS` for every infrastructure or unknown-outcome failure.

Replaying the same immutable commands is safe:

- if the first transaction rolled back, the retry appends them;
- if the first transaction committed, the retry returns `ALREADY_APPLIED`;
- if only part of the SQL work failed, Hibernate rolls the complete batch back and the retry appends all commands together.

Never allocate new delivery IDs merely because acknowledgement was lost. Doing so would intentionally create additional reflog projection entries.

## Queue usage

```java
DurableStripedWriteQueue.Limits limits =
    DurableWriteQueueSettings.from(properties);

try (DurableReflogWriter writer =
    new DurableReflogWriter(sessionFactory, limits)) {
  DurableStripedWriteQueue.Submission<ReflogAppendResult> submission =
      writer.append("customer-history", command);

  try {
    ReflogAppendResult result = submission.completion().join();
    // Completion is observable only after the transaction committed.
  } catch (CompletionException failure) {
    switch (HibernateReflogBatchProcessor.retryAdvice(failure)) {
      case RETRY_WITH_SAME_DELIVERY_IDS -> replayFromDurableSource(command);
      case DO_NOT_RETRY -> reconcileOrDeadLetter(command, failure);
    }
  }
}
```

The queue remains an in-memory batching and backpressure layer, not a durable journal. A process crash may discard accepted commands whose futures had not completed. A broker, outbox or replayable receiver source must retain them until committed acknowledgement.

## Ordering and concurrency

- FIFO order is preserved for one repository.
- A batch never mixes repositories.
- The processor validates contiguous history independently for every ref in the batch.
- Independent repositories can run on different writer stripes and use distinct repository lock rows.
- The repository lock orders projection batches with pack, ref and maintenance publication across independent SessionFactories.
- Queryable projection batching does not replace JGit's authoritative Reftable/ref update path.

## Database schema

Core migration `0.1.19` adds nullable `git_reflog.delivery_id` and an index on repository plus delivery ID for H2, HSQLDB, PostgreSQL and SQL Server. The column remains nullable so existing queryable reflog data and the standalone `HibernateReflogWriter` API remain compatible.

## Verification and native telemetry

The semantic contract tests prove:

- one ordered batch commits before acknowledgement;
- exact replay is idempotent;
- conflicting delivery-ID reuse rejects the complete batch before mutation;
- a non-contiguous ref transition rejects the complete batch;
- a database constraint failure on a later entry rolls an earlier insert back;
- the same delivery IDs can be retried successfully after the infrastructure fault is removed.

The dedicated native-telemetry workflow runs batch sizes 1, 10 and 50 on PostgreSQL and SQL Server. For each size it records separate `first-apply` and `idempotent-replay` windows and retains strict JSON containing WAL/log, database I/O, index and wait evidence. First application must produce a positive PostgreSQL WAL insert-position delta or SQL Server transaction-log file-write delta. Replays must leave the persisted row count unchanged.

This evidence answers the physical cost of the first safe Git-semantic batch. It does not justify batching arbitrary ref updates or complete pushes; those require a separate whole-operation validation and rollback design.
