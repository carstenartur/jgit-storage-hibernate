# Protocol storage metrics

The real JGit protocol and concurrent-publication benchmarks expose database and repository coordination costs as JMH secondary metrics. This complements elapsed time with evidence about why an operation is expensive and prevents optimization work from being selected only from timing noise.

## Scope

The serial protocol matrix covers:

- `initialPushViaReceivePack`
- `incrementalPushViaReceivePack`
- `initialCloneViaUploadPack`
- `incrementalFetchViaUploadPack`

The four-thread concurrency matrix additionally covers:

- `publishToSameRepository`
- `publishToDifferentRepositories`

Fixture creation is completed before counters are reset. Schema creation, repository construction, deterministic base histories and client preparation are therefore excluded. The measured transport/publication operation and its result validation are included.

Filesystem runs publish zero for database-specific counters.

## Aggregate JMH secondary metrics

| Counter | Meaning |
|---|---|
| `hibernateQueries` | Hibernate HQL/criteria query executions |
| `preparedStatements` | JDBC statements prepared by Hibernate |
| `hibernateTransactions` | Hibernate transaction completions observed by `Statistics` |
| `connections` | JDBC connection acquisitions observed by Hibernate |
| `storageTransactions` | top-level repository storage transactions started |
| `storageCommits` | repository storage transactions committed |
| `storageRollbacks` | repository storage transactions rolled back |
| `repositoryLocks` | pessimistic repository row locks acquired |
| `repositoryLockAcquisitionMicros` | elapsed lock-acquisition time, including the database round trip and any contention wait |
| `transactionDurationMicros` | cumulative duration of top-level repository transaction attempts, including session acquisition and commit or rollback |
| `repositoryLockHeldMicros` | cumulative duration from the first successful repository-lock acquisition in a transaction until that transaction completes |

The duration counters answer different questions:

- `repositoryLockAcquisitionMicros` shows how expensive it was to obtain the lock;
- `repositoryLockHeldMicros` shows how long other writers were excluded after acquisition;
- `transactionDurationMicros` shows the complete repository transaction envelope.

A low acquisition value with a high held value indicates long serialized work rather than contention. A high acquisition value under concurrency indicates waiting for another writer. `repositoryLockHeldMicros` is recorded once per top-level transaction even if nested work asks for the same lock again.

### Event score versus one invocation

Protocol and concurrent storage counters use JMH `@AuxCounters(AuxCounters.Type.EVENTS)`. The displayed secondary-metric score is the sum across measured operations, not automatically the cost of one invocation. Use `secondaryMetrics.<counter>.rawData` in `jmh-result.json` and normalize duration totals by the matching operation or recorded transaction/lock count.

## Per-operation breakdown

Every top-level repository transaction receives exactly one stable `StorageOperationKind`. Nested work sharing the active Hibernate session inherits the category of the owning transaction. The benchmark fails immediately if the sum of category counters differs from the backward-compatible aggregate snapshot.

| Operation kind | Diagnostic boundary |
|---|---|
| `REPOSITORY_INITIALIZATION` | creation or verification of repository coordination state |
| `PACK_METADATA_READ` | reconstruction of committed DFS pack descriptions |
| `PACK_FILE_READ` | opening one committed pack extension through the database fallback |
| `PACK_EXTENSION_WRITE` | lock-free persistence or lease renewal of an unpublished PACK/IDX/REFTABLE group |
| `PACK_PUBLICATION` | making the complete expected extension set visible as one committed logical pack |
| `PACK_ROLLBACK` | removal of unpublished local or token-owned pack state after failure |
| `PACK_MAINTENANCE` | cleanup or other explicit maintenance of pack persistence state |
| `REF_PUBLICATION` | locked ref/reftable publication and its nested reflog work |
| `REFLOG_READ` | standalone reflog retrieval |
| `REFLOG_WRITE` | standalone reflog persistence outside a ref-publication transaction |
| `OTHER` | explicit uncategorized application work or an internal call site still requiring classification |

For adaptive additive chunked publication, the expected category sequence is:

```text
PACK_EXTENSION_WRITE  lock-free complete parent/payload pre-persistence
PACK_PUBLICATION      locked atomic visibility update
```

The first transaction either commits every expected parent and payload while all rows remain `committed=false`, or rolls back the whole group. The second transaction acquires the repository lock and must update exactly the expected number of token-owned rows. A failed final update is a rolled-back `PACK_PUBLICATION`; successful token cleanup is a lock-free `PACK_ROLLBACK`.

Replacing packs do not use this sequence. JGit performs ref-race validation before replacement, so construction, source-pack deletion and committed publication remain inside the established single `PACK_PUBLICATION` transaction and repository lock.

High-value raw fields include:

```text
packExtensionWriteTransactions
packPublicationTransactions
packRollbackTransactions
refPublicationTransactions
packMetadataReadTransactions
packFileReadTransactions
packExtensionWriteLocks
packPublicationLocks
refPublicationLocks
otherStorageTransactions
otherRepositoryLocks
```

For supported benchmark workloads, `otherStorageTransactions` and `otherRepositoryLocks` must be zero. A non-zero value is a diagnostic gap rather than a value to silently aggregate.

## Baseline and rejected prototype

Before adaptive additive chunked publication, PostgreSQL produced these stable raw structural values for the serial protocol matrix:

| Workload, per invocation | Transactions | Locks | Prepared statements | Category distribution |
|---|---:|---:|---:|---|
| Initial push | 4 | 2 | 11 | 2 pack publications, 2 file reads |
| Incremental push | 4 | 2 | 11 | 2 pack publications, 2 file reads |
| Initial clone | 1 | 0 | 2 | 1 file read |
| Incremental fetch | 2 | 0 | 3 | 2 file reads |

The four-thread baseline was:

| Backend | Same logical repository | Four independent repositories | Independent advantage |
|---|---:|---:|---:|
| Filesystem | 290.7 ops/s | 299.0 ops/s | 2.9% |
| HSQLDB | 75.8 ops/s | 90.6 ops/s | 19.5% |
| PostgreSQL | 54.8 ops/s | 85.0 ops/s | 55.0% |
| PostgreSQL + HikariCP | 53.1 ops/s | 82.5 ops/s | 55.4% |

For PostgreSQL, shared-repository lock acquisition was roughly 12 ms per recorded lock versus roughly 0.6 ms with independent repositories. HikariCP did not change the ratio.

A first prototype added a locked parent-reservation transaction before lock-free payload transfer and final publication. It reduced per-transaction duration but increased the measured structure from two to four storage transactions and from three to four recorded lock acquisitions per four-thread operation. Throughput regressed:

| PostgreSQL workload | Baseline | Rejected prototype |
|---|---:|---:|
| Same logical repository | 54.8 ops/s | 50.7 ops/s |
| Four independent repositories | 85.0 ops/s | 65.4 ops/s |
| Hikari, same repository | 53.1 ops/s | 49.6 ops/s |
| Hikari, independent | 82.5 ops/s | 61.3 ops/s |

For the focused twelve-MiB publication, that prototype expanded from one to three JDBC connections and from five to eight Hibernate flushes while latency remained inside the same broad range. It was rejected.

The final design removes the reservation lock and parent re-read. A chunked additive pack now adds only one lock-free pre-persistence transaction before the existing committed-visibility lock. The final benchmark must demonstrate that this single extra transaction is outweighed by lower publication lock-held time and better shared-repository throughput without materially reducing independent-repository throughput.

## Pack-file read attribution

`PACK_FILE_READ` identifies the transaction boundary but not the extension. `PackFileReadMetrics` classifies every successful committed database fallback after its transaction commits:

```text
packInlineReads
packChunkedReads
indexInlineReads
indexChunkedReads
reftableInlineReads
reftableChunkedReads
otherInlineReads
otherChunkedReads
missingPackFileReads
```

Catalogued chunked extensions are absent because they open without the database metadata fallback. Missing lookups are separate and must be zero for a successful protocol invocation. The benchmark fails if successful attributed reads differ from `PACK_FILE_READ` transactions.

The first complete PostgreSQL attribution produced this per-invocation result:

| Workload | PACK inline | PACK chunked | IDX inline/chunked | Reftable inline | Other | Missing |
|---|---:|---:|---:|---:|---:|---:|
| Initial push | 0 | 0 | 0 | 2 | 0 | 0 |
| Incremental push | 0 | 0 | 0 | 2 | 0 | 0 |
| Initial clone | 0 | 0 | 0 | 1 | 0 | 0 |
| Incremental fetch | 1 | 0 | 0 | 1 | 0 | 0 |

This ruled out IDX caching as a useful standard-protocol optimization and justified only the bounded local handoff for newly published inline Reftable payloads and small inline PACK payloads.

## Enabling repository counters

Repository transaction, lock, duration and pack-file attribution counters are opt-in:

```properties
jgit.storage.hibernate.metrics.enabled=true
```

The default is `false`. Hibernate's own counters are controlled independently:

```properties
hibernate.generate_statistics=true
```

## Programmatic snapshots

```java
StorageOperationMetrics before = repository.getStorageOperationMetrics();
StorageOperationBreakdown categoriesBefore = repository.getStorageOperationBreakdown();

// execute JGit operation

StorageOperationMetrics delta =
    repository.getStorageOperationMetrics().minus(before);
StorageOperationBreakdown categories =
    repository.getStorageOperationBreakdown().minus(categoriesBefore);

StorageOperationMetrics prePersistence =
    categories.metrics(StorageOperationKind.PACK_EXTENSION_WRITE);
StorageOperationMetrics publication =
    categories.metrics(StorageOperationKind.PACK_PUBLICATION);
```

`StorageOperationBreakdown.total()` must equal the aggregate delta, including both duration counters. For adaptive additive publication, `prePersistence.repositoryLocksAcquired()` must be zero and the final publication must retain the single committed visibility lock.

Pack-file attribution remains available independently:

```java
PackFileReadMetrics beforeReads = repository.getPackFileReadMetrics();
// execute JGit operation
PackFileReadMetrics reads =
    repository.getPackFileReadMetrics().minus(beforeReads);
```

Snapshots are immutable and monotone; `minus(...)` rejects an ordering that would produce negative counters.

## Interpretation

Use the metrics to choose and validate optimizations:

- high `PACK_PUBLICATION` lock-held time with low acquisition time suggests moving additive payload transfer before the publication lock;
- high acquisition time under concurrent same-repository writes indicates genuine lock contention;
- after adaptive publication, `PACK_EXTENSION_WRITE` must contain the long payload transaction with zero repository locks;
- higher transaction count is acceptable only when shared-repository throughput or lock-held time improves materially and independent/small-pack behavior does not regress;
- replacement and compaction must remain on one locked `PACK_PUBLICATION` boundary to preserve JGit's race contract;
- high transaction duration with little lock-held time points to connection, read or database work outside serialized publication;
- many `REF_PUBLICATION` transactions or locks suggest ref/reftable coordination is the dominant fixed cost;
- many `PACK_METADATA_READ` transactions suggest pack-list reconstruction should be examined;
- `PACK_FILE_READ` must be interpreted through extension/storage attribution before adding payload caching;
- many statements within a small number of pack-write transactions suggest JDBC batching or lower-level chunk persistence;
- low database activity with high elapsed time suggests JGit pack generation, compression or client-side work rather than Hibernate storage.

No Hibernate second-level payload cache should be introduced solely from elapsed-time comparisons. JGit already maintains its specialized DFS block cache; any payload handoff must be locally scoped, bounded and justified by extension-level evidence.
