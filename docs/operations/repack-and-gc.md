# Repack, garbage collection and read acceleration

Long-lived repositories accumulate small `INSERT` and `RECEIVE` packs. Retaining every pack is transactionally correct, but eventually increases pack-index memory, object-lookup fan-out and the work required to negotiate clone and fetch requests.

Core exposes JGit's DFS garbage collector through `PackStorageMaintenance`. The service keeps the relational database as the durable authority while using the same atomic pack-publication path as ordinary writes. Persisted logical pack metadata keeps JGit's source, ordering and lifecycle decisions identical before and after a repository restart.

The reproducible matrix, generated artifacts and break-even interpretation rules are defined in [Repository-aging policy evidence](repository-aging-policy-evidence.md).

## Read-optimized maintenance

```java
PackStorageMaintenance maintenance =
    new PackStorageMaintenance(sessionFactory);

PackRepackResult result =
    maintenance.repackForReads(new RepositoryName("customer-history"));

if (!result.successful()) {
  // Refs moved while JGit validated the replacement. Retry in a later
  // maintenance window; no partially published replacement is visible.
}
```

The read-optimized preset requests:

- one primary reachable-object pack;
- JGit pack bitmaps for clone/fetch negotiation and graph walks;
- a commit graph;
- changed-path Bloom filters in that commit graph;
- Reftable compaction;
- the normal one-day unreachable-garbage retention and 50-MiB garbage-coalescing limit.

Use `PackRepackOptions.compactOnly()` when the deployment wants fewer packs without paying the maintenance-time and storage cost of auxiliary read indexes. A custom immutable `PackRepackOptions` can select each feature independently; Bloom filters require commit-graph generation.

JGit's current DFS garbage collector does not emit a persisted reverse-index extension. Every supported JGit compatibility job now retains `jgit-dfs-midx-capability.json`, listing the exact selected version and exposed pack extensions. The maintenance API does not advertise a Multi-Pack-Index shortcut unless that version exposes a persistable DFS extension that this backend can store.

## Transaction and concurrency model

JGit performs graph traversal, delta selection and extension construction through the repository's normal DFS interfaces. The Hibernate backend stages the resulting PACK, IDX, bitmap, commit-graph and Reftable extensions outside database visibility.

The final replacement:

1. acquires the repository-scoped database lock;
2. persists and publishes the complete logical replacement;
3. removes replaced parent rows and their chunks through the database cascade;
4. commits the new generation atomically.

Readers therefore see either the old generation or the complete new generation. They never see a half-written pack. JGit revalidates the refs before replacement; `PackRepackResult.successful()` is `false` when a concurrent ref change makes the prepared replacement unsafe.

Maintenance for different logical repositories uses different lock rows and can run concurrently. Repack work for the same repository should be serialized by the operator even though publication itself remains protected.

## Result interpretation

`PackRepackResult` records:

- active PACK and Reftable counts before and after;
- logical extension bytes before and after;
- source and newly generated pack-description counts;
- end-to-end maintenance duration;
- whether JGit accepted the replacement after its race check.

`storedByteDelta()` is `after - before`. It may be positive even for a successful compaction because bitmaps and commit graphs intentionally trade some storage for lower read latency and lower CPU cost. `packReduction()` reports the active ordinary-pack reduction independently.

## Measured aging breakpoint

The deterministic smoke fixture creates one fresh pack per incremental push, closes and reopens the repository, verifies persisted `committedAt` ordering and then compares no maintenance, compact-only maintenance and the read-optimized preset.

The smoke profile intentionally covers 1 and 10 pushes. The full age profile is configured for 1, 10, 32, 100, 300 and 1,000 pushes, cold and warm JGit cache states, HSQLDB as a reference, and PostgreSQL through both the built-in pool and HikariCP. Its raw JMH JSON is converted into structural comparison and break-even evidence; a successful larger-scale production-database run must still be retained before changing the automatic-maintenance default.

A separate protected-main profile closes and rebuilds the complete Hibernate `SessionFactory` and connection pool before measurement. Its first complete retained run covers PostgreSQL and SQL Server, cold and warm cache states and three independent repeats at ten pushes. It answers the restart/lifecycle question but does not replace the larger age axis.

### Structural result

| History | Maintenance | Active PACKs | Stored extension bytes | Pack reduction | Maintenance time |
|---:|---|---:|---:|---:|---:|
| 1 push | none | 1 | 18,491 | – | – |
| 1 push | compact-only | 2 | 19,567 | -1 | about 41 ms |
| 1 push | read-optimized | 2 | 20,955 | -1 | about 65 ms |
| 10 pushes | none | 10 | 107,269 | – | – |
| 10 pushes | compact-only | 2 | 98,409 | 8 | about 71 ms |
| 10 pushes | read-optimized | 2 | 100,661 | 8 | about 103 ms |

At one push, both maintenance modes create more packs and more bytes. There is no operational justification for maintenance that early.

At ten pushes, both modes reduce ten ordinary packs to two. Compact-only also reduces stored extension bytes by about 8.9 KiB; the read-optimized preset reduces them by about 6.6 KiB after adding its auxiliary read structures.

### Original smoke read result at ten pushes

| Operation | No maintenance | Compact-only | Read-optimized |
|---|---:|---:|---:|
| Reopen and lookup oldest object | 15.64 ms | 4.47 ms | 4.60 ms |
| Clone-style traversal | 0.264 ms | 0.211 ms | 0.226 ms |
| Oldest-object lookup | 0.012 ms | 0.009 ms | approximately unchanged from baseline |

The strongest smoke signal is repository reopen plus an old-object lookup: compact-only and read-optimized maintenance are both about 70% faster than the ten-pack baseline. Clone-style traversal also improves, although the microsecond-scale point estimates require caution.

### Real provider restart at ten pushes

The protected-main run uses a fresh Hibernate provider and connection pool for the retained read and repeats every database/cache condition three times.

| Backend / cache | No maintenance | Compact-only | Read-optimized | Maintenance versus none |
|---|---:|---:|---:|---|
| PostgreSQL cold | 18.556 ms (CV 2.22%) | 6.046 ms (CV 4.37%) | 5.941 ms (CV 2.29%) | 67.4–68.0% faster |
| SQL Server cold | 28.972 ms (CV 20.03%) | 8.586 ms (CV 19.36%) | 8.545 ms (CV 20.47%) | 70.4–70.5% faster |
| PostgreSQL warm | 3.660 ms (CV 4.34%) | 4.555 ms (CV 3.13%) | 4.712 ms (CV 5.87%) | 24.5–28.8% slower |
| SQL Server warm | 7.069 ms (CV 9.58%) | 8.101 ms (CV 2.35%) | 8.493 ms (CV 5.57%) | 14.6–20.2% slower |

The cold direction is strong: PostgreSQL repeat dispersion is low, and even the wider SQL Server ranges do not overlap between baseline and either maintenance mode. The warm direction is the opposite. With ten packs and an already warmed read path, both maintenance modes make reopen-plus-oldest-object lookup slower.

PostgreSQL cold clone-style traversal improves by 29.8% with compact-only and 16.6% with read-optimized maintenance. Direct oldest-object lookup remains in the 0.008–0.012-ms range and has too much relative noise to support a policy decision. The complete aggregate and every repeat are retained in the [provider-restart evidence record](../evidence/repository-aging-restart-reproducibility-2026-09-04.md).

The result therefore does **not** justify a universal automatic threshold. It shows that the useful decision is lifecycle- and workload-dependent: maintenance can materially help cold reconstruction while regressing a warm path at the same pack count. The remaining 32/100/300/1,000-push production-database matrix and lifecycle-specific maintenance payback are required before enabling a default automatic policy.

## Generated policy evidence

For every complete measured condition, the converter compares the matching no-maintenance, compact-only and read-optimized results. It records:

- operation latency and uncertainty;
- pack reduction and stored-byte change;
- maintenance duration;
- latency saving relative to no maintenance;
- the number of equivalent reads needed to repay maintenance cost.

A candidate is emitted only when the mode both removes packs and improves the measured operation. The generated recommendation is observational evidence and never changes runtime configuration. Incomplete or duplicate matrices fail the workflow instead of silently producing a partial policy.

## Resulting operational guidance

- Do not repack a newly created or one-pack repository.
- Do not trigger maintenance from pack count alone. Around ten small incremental packs, first check whether cold reopen, clone/fetch or index-memory behavior has measurably degraded.
- Treat a predominantly warm repository separately: the retained ten-pack restart matrix shows that maintenance can regress an already warmed reopen path.
- Prefer `compactOnly()` as the first intervention when the goal is simply fewer packs; it has lower maintenance and auxiliary-storage cost, while the cold reopen result is effectively tied with the read-optimized preset.
- Use the read-optimized preset when clone/fetch negotiation or path-history workloads justify bitmap, commit-graph and Bloom-filter construction.
- Trigger from a combination of lifecycle/cache state, active pack count, small-pack ratio, index bytes, unreachable bytes, measured lookup/fetch degradation and time since last maintenance—not from a single hard-coded count.
- Keep automatic maintenance opt-in until repeated larger-scale PostgreSQL, PostgreSQL+HikariCP and SQL Server matrices establish stable breakpoints and read-payback.

A practical operator can record the condition before and after each maintenance run and retain `PackRepackResult` together with application latency. That deployment history is more reliable than assuming every repository has the same object mix and storage hardware.

## Failure and recovery

An exception means storage or graph construction failed and is reported as `HibernateStorageException`. A `successful=false` result is different: JGit detected a concurrent ref race and deliberately declined to replace the source packs.

Temporary extensions follow the existing staging cleanup and crash model. A hard process termination can leave unpublished `jgit-storage-pack-` files in the configured temporary directory; they are derived state and must not be imported as durable Git data.
