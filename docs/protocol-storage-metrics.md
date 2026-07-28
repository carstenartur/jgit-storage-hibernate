# Protocol storage metrics

The real JGit protocol benchmarks expose database and repository coordination costs as JMH secondary metrics. This complements elapsed time with evidence about why an operation is expensive.

## Scope

Metrics are collected only for the four `GitProtocolBenchmark` workloads:

- `initialPushViaReceivePack`
- `incrementalPushViaReceivePack`
- `initialCloneViaUploadPack`
- `incrementalFetchViaUploadPack`

Fixture creation is completed before counters are reset. Schema creation, repository construction, deterministic base histories and client preparation are therefore excluded. The measured transport operation and its result validation are included.

Filesystem runs publish zero for database-specific counters.

## JMH secondary metrics

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

The public performance dashboard continues to chart primary latency only. The secondary metrics remain in the raw `jmh-result.json` workflow artifact for architecture analysis.

## Enabling repository counters

Repository transaction and lock counters are opt-in:

```properties
jgit.storage.hibernate.metrics.enabled=true
```

The default is `false`. Applications that do not enable the property do not update the `LongAdder` counters and receive a zero snapshot.

Hibernate's own counters remain controlled independently:

```properties
hibernate.generate_statistics=true
```

## Programmatic snapshot

Internal repository-level integration can read a monotone snapshot:

```java
StorageOperationMetrics before = repository.getStorageOperationMetrics();
// execute JGit operation
StorageOperationMetrics delta =
    repository.getStorageOperationMetrics().minus(before);
```

The snapshot records transaction starts, commits, rollbacks, repository-lock acquisitions and cumulative lock acquisition nanoseconds. Nested storage work sharing an active repository transaction is counted only once.

## Interpretation

The metrics are intended to choose the next optimization using evidence:

- many storage transactions or connections suggest transaction-boundary consolidation;
- many prepared statements during pack ingestion suggest incremental chunk persistence or real JDBC batching;
- substantial repository-lock acquisition time under concurrency suggests lock-granularity work;
- low database activity with high elapsed time suggests JGit pack generation, compression or client-side work rather than Hibernate storage.

No Hibernate second-level payload cache should be introduced solely from elapsed-time comparisons. JGit already maintains its specialized DFS block cache, so duplicate payload caching requires explicit evidence.
