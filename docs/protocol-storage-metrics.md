# Protocol storage metrics

The real JGit protocol benchmarks expose database and repository coordination costs as JMH secondary metrics. This complements elapsed time with evidence about why an operation is expensive and prevents optimization work from being selected only from timing noise.

## Scope

Metrics are collected only for the four `GitProtocolBenchmark` workloads:

- `initialPushViaReceivePack`
- `incrementalPushViaReceivePack`
- `initialCloneViaUploadPack`
- `incrementalFetchViaUploadPack`

Fixture creation is completed before counters are reset. Schema creation, repository construction, deterministic base histories and client preparation are therefore excluded. The measured transport operation and its result validation are included.

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
| `repositoryLockAcquisitionMicros` | elapsed lock acquisition time in microseconds |

`repositoryLockAcquisitionMicros` includes the database round trip and any contention wait. It is deliberately not labelled as pure lock-wait time because portable Hibernate timing cannot separate those components.

### Event score versus one invocation

`ProtocolStorageCounters` uses JMH `@AuxCounters(AuxCounters.Type.EVENTS)`. For event counters, the displayed secondary-metric **score is the sum across all measured invocations**, not the cost of one invocation. This suite performs five measured single-shot invocations, so a displayed score of `50` storage transactions represents five raw values of `10`.

Use `secondaryMetrics.<counter>.rawData` in `jmh-result.json` when interpreting one push, clone or fetch. The first complete categorized PostgreSQL run produced these stable raw values:

| Workload, per invocation | Transactions | Locks | Category distribution |
|---|---:|---:|---|
| Initial push | 9 | 5 | 3 extension writes, 2 pack publications, 2 file reads, 2 metadata reads |
| Incremental push | 10 | 5 | 3 extension writes, 2 pack publications, 3 file reads, 2 metadata reads |
| Initial clone | 2 | 0 | 2 file reads |
| Incremental fetch | 3 | 0 | 3 file reads |

For PostgreSQL incremental push, the corresponding raw values are also 11 connection acquisitions, 25 prepared statements and roughly 4.2 ms cumulative end-to-end lock acquisition time per invocation. Scores shown by JMH for those event counters are five times the raw per-invocation values.

## Per-operation breakdown

Every top-level repository transaction receives exactly one stable `StorageOperationKind`. Nested work sharing the active Hibernate session inherits the category of the owning transaction. The benchmark fails immediately if the sum of category counters differs from the backward-compatible aggregate snapshot.

| Operation kind | Diagnostic boundary |
|---|---|
| `REPOSITORY_INITIALIZATION` | creation or verification of repository coordination state |
| `PACK_METADATA_READ` | reconstruction of committed DFS pack descriptions |
| `PACK_FILE_READ` | opening one committed pack extension |
| `PACK_EXTENSION_WRITE` | persistence or lease renewal of a temporary PACK/IDX/REFTABLE extension |
| `PACK_PUBLICATION` | making complete extensions visible as one committed pack |
| `PACK_ROLLBACK` | removal of an unpublished pack after failure |
| `PACK_MAINTENANCE` | cleanup of expired uncommitted pack state |
| `REF_PUBLICATION` | locked ref/reftable publication and its nested reflog work |
| `REFLOG_READ` | standalone reflog retrieval |
| `REFLOG_WRITE` | standalone reflog persistence outside a ref-publication transaction |
| `OTHER` | explicit uncategorized application work or an internal call site still requiring classification |

The raw JMH JSON publishes fixed transaction counters for every category and lock counters for the categories that can acquire the repository lock. High-value fields include:

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

For the four protocol workloads, `otherStorageTransactions` and `otherRepositoryLocks` must be zero. A non-zero value is treated as a diagnostic gap rather than silently folded into a misleading category.

The public performance dashboard continues to chart primary latency only. Secondary metrics remain in the raw `jmh-result.json` workflow artifact for architecture analysis.

## Enabling repository counters

Repository transaction and lock counters are opt-in:

```properties
jgit.storage.hibernate.metrics.enabled=true
```

The default is `false`. Applications that do not enable the property do not allocate category-counter arrays, do not inspect internal call stacks and receive zero aggregate and breakdown snapshots.

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
```

Categorized diagnostic API:

```java
StorageOperationBreakdown before = repository.getStorageOperationBreakdown();
// execute JGit operation
StorageOperationBreakdown delta =
    repository.getStorageOperationBreakdown().minus(before);

StorageOperationMetrics packWrites =
    delta.metrics(StorageOperationKind.PACK_EXTENSION_WRITE);
```

`StorageOperationBreakdown.total()` must equal the aggregate delta for the same interval. Snapshots are immutable and monotone; `minus(...)` rejects an earlier/newer ordering that would produce negative counters.

## Interpretation

The metrics are intended to choose the next optimization using evidence:

- many `PACK_EXTENSION_WRITE` transactions or locks suggest temporary-extension persistence or lease-boundary work;
- many `PACK_PUBLICATION` transactions suggest pack publication is fragmented;
- many `REF_PUBLICATION` transactions or locks suggest ref/reftable coordination is the dominant fixed cost;
- many `PACK_METADATA_READ` or `PACK_FILE_READ` transactions suggest pack-list reconstruction or read-channel opening should be examined;
- many prepared statements within a small number of pack-write transactions suggest JDBC batching or incremental chunk persistence;
- substantial repository-lock acquisition time under concurrency suggests lock-granularity work;
- low database activity with high elapsed time suggests JGit pack generation, compression or client-side work rather than Hibernate storage.

PR #130 tested and rejected one plausible-looking hypothesis: suppressing intermediate flush delegation did not change the raw per-invocation transaction, statement, connection or lock counts. The patch was not merged. The categorized breakdown exists specifically so the next implementation targets a measured operation boundary rather than another guess.

The first categorized result points to the five locked write/publication transactions per push, not to HikariCP or read-path work. A follow-up optimization must still prove any improvement through the same raw counters and latency benchmark.

No Hibernate second-level payload cache should be introduced solely from elapsed-time comparisons. JGit already maintains its specialized DFS block cache, so duplicate payload caching requires explicit evidence.
