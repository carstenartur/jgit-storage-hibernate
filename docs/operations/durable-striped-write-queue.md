# Durable striped receiver batching

`DurableStripedWriteQueue<C, R>` is a production Core component for receiver-side persistence workloads that can safely combine multiple records for one logical repository into one database transaction.

It is intentionally generic. The queue controls ordering, bounded admission, collection time and durable acknowledgement; the supplied batch processor defines the actual Hibernate entities or mutations.

## Default behavior

`DurableWriteQueueSettings.from(properties)` resolves these defaults:

```properties
jgit.storage.hibernate.write_queue.stripes=4
jgit.storage.hibernate.write_queue.max_queued_commands_per_stripe=1000
jgit.storage.hibernate.write_queue.max_queued_bytes_per_stripe=268435456
jgit.storage.hibernate.write_queue.max_batch_commands=50
jgit.storage.hibernate.write_queue.max_batch_bytes=67108864
jgit.storage.hibernate.write_queue.max_batch_wait_ms=2
jgit.storage.hibernate.write_queue.enqueue_timeout_ms=10000
```

The receiver therefore behaves as follows:

1. Commands are routed deterministically by repository name to one writer stripe.
2. A batch never mixes two repositories.
3. Up to 50 commands are collected, subject to the independent 64-MiB byte bound.
4. Reaching either batch bound releases the batch immediately.
5. If the bounds are not reached, expiration of the oldest command's two-millisecond collection window releases every currently available command for that repository.
6. The processor is called once for the complete repository-homogeneous batch.
7. Submission futures complete only after the processor returns from the committed transaction.

Thus a burst of 50 small records is persisted in one transaction, while a sparse stream of, for example, three records is persisted as a three-record batch after the configured wait. No caller receives success merely because a record entered the in-memory queue.

## Hibernate transaction and JDBC-batch adapter

`HibernateDurableBatchProcessor` turns one queue batch into exactly one `HibernateTransactionContext` transaction:

```java
Properties queueProperties = new Properties();
queueProperties.setProperty(
    DurableWriteQueueSettings.MAX_BATCH_COMMANDS,
    "50");
queueProperties.setProperty(
    DurableWriteQueueSettings.MAX_BATCH_WAIT_MILLIS,
    "2");

DurableStripedWriteQueue.Limits limits =
    DurableWriteQueueSettings.from(queueProperties);

HibernateDurableBatchProcessor<IncomingRecord, Long> processor =
    new HibernateDurableBatchProcessor<>(
        sessionFactory,
        StorageOperationKind.OTHER,
        HibernateDurableBatchProcessor.Locking.REPOSITORY,
        (session, repositoryName, records) -> {
          List<Long> ids = new ArrayList<>(records.size());
          for (IncomingRecord record : records) {
            ReceivedEntity entity = map(repositoryName, record);
            session.persist(entity);
            ids.add(entity.getId());
          }
          return ids;
        });

try (DurableStripedWriteQueue<IncomingRecord, Long> queue =
    new DurableStripedWriteQueue<>(limits, processor)) {
  DurableStripedWriteQueue.Submission<Long> submission =
      queue.submit("domain-history", record.estimatedBytes(), record);
  Long committedId = submission.completion().join();
}
```

Before invoking the application work, the adapter sets the active stateful Hibernate session's JDBC batch size to the actual number of collected commands. Consequently:

- a full 50-record queue batch requests a session-level JDBC batch size of 50;
- a partial three-record batch requests a JDBC batch size of 3;
- the SessionFactory default does not unnecessarily split a larger receiver batch;
- one batch may still be divided when records generate different SQL statement shapes, use identity-generated keys, or application work calls `flush()` early.

A Core integration test persists 50 compatible `GitRepositoryLifecycleEntity` rows and observes exactly one `jdbcExecuteBatchStart()` event, one transaction, session batch size 50 and 50 post-commit completions. This verifies the requested receiver behavior at the Hibernate/JDBC boundary rather than only at the queue-scheduling boundary.

Use `Locking.REPOSITORY` only when the records participate in repository state that must be ordered with pack publication, refs or maintenance. Independent append-only application records can use `Locking.NONE` and still share one transaction per batch.

## Durability contract

The processor must return one result per command in the original order. `HibernateDurableBatchProcessor` validates the non-null result count inside the active transaction before commit. A mismatch throws, rolls the complete transaction back and fails every submission in the batch.

The adapter returns only after commit. The queue completes submission futures only after that return. A successfully completed future therefore means the batch transaction committed; an enqueue or SQL flush alone never counts as durable success.

A JVM crash can lose records that were accepted into memory but whose futures have not completed. This does not violate the acknowledgement contract: those callers have not received durable success. For end-to-end delivery across process crashes, place an external durable broker, outbox or replayable source before the queue. The in-memory queue is a batching and backpressure layer, not a message journal.

## Ordering and concurrency

- FIFO order is preserved within one repository.
- One atomic batch contains exactly one repository.
- Different repositories may execute concurrently when they hash to different stripes.
- Repositories sharing a stripe take turns; the short batch wait bounds head-of-line delay.
- No single global writer serializes all repositories.

The repository grouping is important: combining unrelated repositories into one transaction would make one failing tenant roll back another and would recreate global head-of-line blocking.

## Backpressure and shutdown

Admission is bounded independently by command count and referenced bytes per stripe. A producer waits at most `enqueue_timeout_ms`; afterward submission is rejected rather than growing heap without limit.

`close()` stops admission, drains all accepted commands and joins the writer stripes. `shutdownNow()` rejects queued commands but deliberately does not interrupt a command that may already own a database transaction.

Telemetry exposes submitted, completed, failed, cancelled and rejected commands; successful and failed batch counts; queued commands and bytes; queue wait; maximum batch size and maximum batch bytes.

## Scope limitation for Git commands

The queue can batch ordinary receiver records immediately. It must not transparently merge arbitrary complete JGit push/ref operations into one transaction without a storage-specific processor. Git ref compare-and-set, pack visibility, replacement and rollback semantics require explicit validation of the whole combined operation.

The first such storage-specific processor is deliberately narrower than a push: [`DurableReflogWriter`](git-aware-reflog-batching.md) batches idempotent append-only **queryable reflog projection records**. It validates immutable delivery IDs and contiguous per-ref history before one repository-locked JDBC batch, then completes callers only after commit. That proven contract does not authorize batching authoritative Reftable/ref changes, pack generations or unrelated complete pushes.

The earlier benchmark-only scheduling queue grouped commands for worker scheduling but deliberately executed their transactions separately. The production queue described here is different: its processor receives the full record list once, uses one transaction and can emit one JDBC batch per compatible SQL shape.

## Verification

Core integration tests verify:

- 50 lifecycle records are flushed in one Hibernate transaction;
- 50 compatible rows use one observed JDBC batch with session batch size 50;
- no submission is acknowledged before that transaction commits;
- a partial three-record batch is released after the configured wait and uses a batch size of three;
- repositories are never mixed in one batch;
- one transaction failure fails every command in the atomic batch;
- an invalid result count rolls back data before any future can report success;
- the Hibernate adapter commits before queue acknowledgement;
- property overrides independently control record count, byte bounds and collection time.

The Git-aware reflog contract additionally verifies exact replay, semantic rejection before mutation, full rollback after a later JDBC-batch failure, and PostgreSQL/SQL Server WAL or transaction-log evidence for batch sizes 1, 10 and 50.
