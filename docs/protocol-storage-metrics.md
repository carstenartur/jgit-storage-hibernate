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

The three duration counters answer different questions:

- `repositoryLockAcquisitionMicros` shows how expensive it was to obtain the lock;
- `repositoryLockHeldMicros` shows how long other writers were excluded after acquisition;
- `transactionDurationMicros` shows the complete repository transaction envelope.

A low acquisition value with a high held value indicates long serialized work rather than lock contention. A high acquisition value under concurrency indicates waiting for another writer. `repositoryLockHeldMicros` is recorded once per top-level transaction even if nested work asks for the same repository lock again.

### Event score versus one invocation

Protocol and concurrent storage counters use JMH `@AuxCounters(AuxCounters.Type.EVENTS)`. For event counters, the displayed secondary-metric **score is the sum across all measured operations**, not automatically the cost of one invocation. Use `secondaryMetrics.<counter>.rawData` in `jmh-result.json` when interpreting one push, clone, fetch or publication.

The same rule applies to the microsecond duration counters. Concurrent throughput tests execute many operations per iteration, so normalize event totals by the matching operation count before comparing per-operation costs.

Before adaptive chunked publication, PostgreSQL produced these stable raw structural values for the serial protocol matrix:

| Workload, per invocation | Transactions | Locks | Prepared statements | Category distribution |
|---|---:|---:|---:|---|
| Initial push | 4 | 2 | 11 | 2 pack publications, 2 file reads |
| Incremental push | 4 | 2 | 11 | 2 pack publications, 2 file reads |
| Initial clone | 1 | 0 | 2 | 1 file read |
| Incremental fetch | 2 | 0 | 3 | 2 file reads |

Adaptive publication intentionally changes the write-side structure for logical packs containing chunked extensions: one former `PACK_PUBLICATION` transaction becomes two `PACK_EXTENSION_WRITE` transactions plus one shorter `PACK_PUBLICATION` transaction, with one reservation lock and one final publication lock. Fully inline and mixed legacy publications retain the direct path. The merge decision therefore compares added fixed transaction cost against reduced `PACK_PUBLICATION` lock-held time and improved shared-repository throughput.

Duration values are intentionally not documented as fixed constants. They depend on database placement, contention, payload size, WAL behavior and runner load and should be compared through raw per-operation samples.

## Concurrent publication evidence

The four-thread benchmark gives every Hibernate thread an independent `SessionFactory` connected to the same database. It compares four handles sharing one logical repository lock row with four handles using independent repositories.

The baseline that selected adaptive chunked publication was:

| Backend | Same logical repository | Four independent repositories | Independent advantage |
|---|---:|---:|---:|
| Filesystem | 290.7 ops/s | 299.0 ops/s | 2.9% |
| HSQLDB | 75.8 ops/s | 90.6 ops/s | 19.5% |
| PostgreSQL | 54.8 ops/s | 85.0 ops/s | 55.0% |
| PostgreSQL + HikariCP | 53.1 ops/s | 82.5 ops/s | 55.4% |

For PostgreSQL, shared-repository lock acquisition was roughly 12 ms per recorded lock versus roughly 0.6 ms with independent repositories. HikariCP did not change the ratio. The nearly flat filesystem result shows that the difference comes from database-backed repository coordination rather than the four-thread harness itself.

## Per-operation breakdown

Every top-level repository transaction receives exactly one stable `StorageOperationKind`. Nested work sharing the active Hibernate session inherits the category of the owning transaction. The benchmark fails immediately if the sum of category counters differs from the backward-compatible aggregate snapshot.

| Operation kind | Diagnostic boundary |
|---|---|
| `REPOSITORY_INITIALIZATION` | creation or verification of repository coordination state |
| `PACK_METADATA_READ` | reconstruction of committed DFS pack descriptions |
| `PACK_FILE_READ` | opening one committed pack extension through the database fallback |
| `PACK_EXTENSION_WRITE` | reservation, payload persistence or lease renewal of an unpublished PACK/IDX/REFTABLE extension |
| `PACK_PUBLICATION` | making the complete expected extension set visible as one committed logical pack |
| `PACK_ROLLBACK` | removal of unpublished local or token-owned pack state after failure |
| `PACK_MAINTENANCE` | cleanup of expired uncommitted pack state |
| `REF_PUBLICATION` | locked ref/reftable publication and its nested reflog work |
| `REFLOG_READ` | standalone reflog retrieval |
| `REFLOG_WRITE` | standalone reflog persistence outside a ref-publication transaction |
| `OTHER` | explicit uncategorized application work or an internal call site still requiring classification |

For adaptive chunked publication, the expected category sequence is:

```text
PACK_EXTENSION_WRITE  locked parent reservation
PACK_EXTENSION_WRITE  lock-free inline/chunk payload transfer
PACK_PUBLICATION      locked replacement + atomic visibility update
```

A failed final update is a rolled-back `PACK_PUBLICATION`; successful token cleanup is a lock-free `PACK_ROLLBACK`. Readers cannot observe the intermediate rows because every read path requires `committed=true`.

The raw JMH JSON publishes transaction counters for every category and lock counters for categories that can acquire the repository lock. High-value fields include:

```text
packExtensionWriteTransactions
packPublicationTransactions
refPublicationTransactions
packMetadataReadTransactions
packFileReadTransactions
packExtensionWriteLocks
packPublicationLocks
refPublicationLocks
otherStorageTransactions
otherRepositoryLocks
```

For the supported benchmark workloads, `otherStorageTransactions` and `otherRepositoryLocks` must be zero. A non-zero value is treated as a diagnostic gap rather than silently folded into a misleading category.

The Java snapshot API also retains transaction and lock duration per operation category. This makes it possible to verify that payload transfer moved into lock-free `PACK_EXTENSION_WRITE` work while `PACK_PUBLICATION` lock-held time decreased.

## Pack-file read attribution

`PACK_FILE_READ` identifies the transaction boundary but not which extension caused it. `PackFileReadMetrics` therefore classifies every successful committed database fallback after its transaction commits:

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

Catalogued chunked extensions are intentionally absent because they open without the database metadata fallback. Missing lookups are separate and must be zero for a successful protocol invocation. The benchmark fails if the sum of successful attributed reads differs from `PACK_FILE_READ` transactions.

The first complete PostgreSQL attribution produced this per-invocation result:

| Workload | PACK inline | PACK chunked | IDX inline/chunked | Reftable inline | Other | Missing |
|---|---:|---:|---:|---:|---:|---:|
| Initial push | 0 | 0 | 0 | 2 | 0 | 0 |
| Incremental push | 0 | 0 | 0 | 2 | 0 | 0 |
| Initial clone | 0 | 0 | 0 | 1 | 0 | 0 |
| Incremental fetch | 1 | 0 | 0 | 1 | 0 | 0 |

The evidence rules out IDX caching as a useful standard-protocol optimization. It supports only a bounded local handoff for newly published inline Reftable payloads and small inline PACK payloads. Historical inline payloads remain outside the generation catalog.

The public performance dashboard continues to chart primary latency and throughput. Secondary metrics remain in the raw `jmh-result.json` workflow artifact for architecture analysis.

## Enabling repository counters

Repository transaction, lock, duration and pack-file attribution counters are opt-in:

```properties
jgit.storage.hibernate.metrics.enabled=true
```

The default is `false`. Applications that do not enable the property do not allocate category-counter arrays, do not inspect internal call stacks and receive zero aggregate, breakdown and pack-file snapshots.

Hibernate's own counters remain controlled independently:

```properties
hibernate.generate_statistics=true
```

## Programmatic snapshots

Aggregate compatibility API:

```java
StorageOperationMetrics before = repository.getStorageOperationMetrics();
// execute JGit operation
StorageOperationMetrics delta =
    repository.getStorageOperationMetrics().minus(before);

long transactionNanos = delta.transactionDurationNanos();
long lockAcquisitionNanos = delta.repositoryLockAcquisitionNanos();
long lockHeldNanos = delta.repositoryLockHeldNanos();
```

The original five-argument `StorageOperationMetrics` constructor remains available for source compatibility and initializes the two duration values to zero. Repository snapshots use the complete seven-counter representation.

Categorized diagnostic API:

```java
StorageOperationBreakdown before = repository.getStorageOperationBreakdown();
// execute JGit operation
StorageOperationBreakdown delta =
    repository.getStorageOperationBreakdown().minus(before);

StorageOperationMetrics packWrites =
    delta.metrics(StorageOperationKind.PACK_EXTENSION_WRITE);
StorageOperationMetrics packPublication =
    delta.metrics(StorageOperationKind.PACK_PUBLICATION);
```

Pack-file fallback attribution:

```java
PackFileReadMetrics before = repository.getPackFileReadMetrics();
// execute JGit operation
PackFileReadMetrics delta =
    repository.getPackFileReadMetrics().minus(before);

long successfulFallbacks = delta.successfulReads();
long allLookups = delta.totalLookups();
```

`StorageOperationBreakdown.total()` must equal the aggregate delta for the same interval, including both duration counters. Pack-file successful reads must equal `PACK_FILE_READ` transactions for successful benchmark operations. Snapshots are immutable and monotone; `minus(...)` rejects an earlier/newer ordering that would produce negative counters.

## Interpretation

The metrics are intended to choose and validate optimizations using evidence:

- high `PACK_PUBLICATION` lock-held time with low acquisition time suggests moving payload transfer before the publication lock;
- high acquisition time under concurrent same-repository writes indicates genuine lock contention;
- after adaptive publication, `PACK_EXTENSION_WRITE` payload-transfer transactions should have zero locks while final `PACK_PUBLICATION` retains atomic visibility;
- higher transaction count is acceptable only when shared-repository throughput or publication lock-held time improves materially and serial small-pack behavior does not regress;
- high transaction duration with little or no lock-held time points to read transactions, connection setup or database work outside serialized publication;
- many `REF_PUBLICATION` transactions or locks suggest ref/reftable coordination is the dominant fixed cost;
- many `PACK_METADATA_READ` transactions suggest pack-list reconstruction should be examined;
- `PACK_FILE_READ` must be interpreted through extension/storage attribution before introducing payload caching;
- many prepared statements within a small number of pack-write transactions suggest JDBC batching or incremental chunk persistence;
- low database activity with high elapsed time suggests JGit pack generation, compression or client-side work rather than Hibernate storage.

PR #130 tested and rejected one plausible-looking hypothesis: suppressing intermediate flush delegation did not change the raw per-invocation transaction, statement, connection or lock counts. The categorized breakdown and separate lock-held metric exist specifically so later implementation targets a measured operation boundary rather than another guess.

No Hibernate second-level payload cache should be introduced solely from elapsed-time comparisons. JGit already maintains its specialized DFS block cache. Any payload handoff must be locally scoped, bounded and justified by extension-level evidence.
