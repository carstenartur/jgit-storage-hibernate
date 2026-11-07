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

JGit's DFS garbage collector does not emit a persisted reverse-index extension for these presets. Multi-Pack-Index support is separate: all supported upstream JGit versions can write and read a DFS MIDX, but the Hibernate catalog cannot yet reconstruct its covered-pack relationships after a reopen. See the [version-specific MIDX evidence](repository-aging-policy-evidence.md#multi-pack-index-capability). The maintenance API does not expose a MIDX option.

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

The [native maintenance charts](../evidence/database-native-evidence-2026-11-07.md#repack-and-subsequent-reads) separate fixture, repack and read log activity, with SQL Server wait categories for both maintenance modes.

`PackRepackResult` records:

- active PACK and Reftable counts before and after;
- logical extension bytes before and after;
- source and newly generated pack-description counts;
- end-to-end maintenance duration;
- whether JGit accepted the replacement after its race check.

`storedByteDelta()` is `after - before`. It may be positive even for a successful compaction because bitmaps and commit graphs intentionally trade some storage for lower read latency and lower CPU cost. `packReduction()` reports the active ordinary-pack reduction independently.

## Measured aging breakpoint

The deterministic smoke fixture creates one fresh pack per incremental push, closes and reopens the repository, verifies persisted `committedAt` ordering and then compares no maintenance, compact-only maintenance and the read-optimized preset.

The smoke profile covers 1 and 10 pushes. The scheduled and manually dispatched full profile covers 1, 10, 32, 100, 300 and 1,000 pushes, cold and warm JGit cache states, HSQLDB as a reference, two PostgreSQL configurations, and SQL Server. The [retained full-age evidence](../evidence/repository-aging-full-2026-11-07.md#charts) contains all 1,152 coordinates, with [query amplification](../evidence/repository-aging-full-2026-11-07.md#latency-and-query-amplification), [storage impact](../evidence/repository-aging-full-2026-11-07.md#storage-and-auxiliary-indexes), and [payback](../evidence/repository-aging-full-2026-11-07.md#maintenance-cost-and-payback).

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

### Measured maintenance payback

For the retained `reopenAndLookupOldest` path, every maintenance result is paired with the no-maintenance result from the same backend, cache state and repeat. Maintenance time divided by the paired latency saving gives the following break-even:

| Backend / cache | Maintenance | Mean maintenance | Break-even reads | Paired repeat range |
|---|---|---:|---:|---:|
| PostgreSQL cold | compact-only | 71.7 ms | 5.73 | 5.31–6.23 |
| PostgreSQL cold | read-optimized | 98.7 ms | 7.82 | 7.49–8.10 |
| SQL Server cold | compact-only | 124.3 ms | 6.10 | 3.29–13.89 |
| SQL Server cold | read-optimized | 147.7 ms | 7.23 | 4.46–15.72 |
| PostgreSQL warm | both modes | 65.3–98.0 ms | none | every repeat regresses |
| SQL Server warm | both modes | 84.0–111.0 ms | none | every repeat regresses |

Thus the cold ten-pack maintenance cost is repaid by roughly six equivalent reopens for compact-only and roughly seven to eight for read-optimized maintenance. The warm path never repays the maintenance in this fixture. The [payback decision record](../evidence/repository-aging-restart-payback-2026-09-04.md) retains the method and every paired input.

The full SQL Server age results show the same warm crossover direction as PostgreSQL between 100 and 300 pushes. At 1,000 pushes, compact-only maintenance repays its cost in three equivalent cold SQL Server reopens or 229 warm reopens. Those values use the same-provider lifecycle and describe a different condition from the ten-push provider-restart experiment.

## Generated policy evidence

For every complete measured condition, the converter compares the matching no-maintenance, compact-only and read-optimized results. It records:

- operation latency and uncertainty;
- pack reduction and stored-byte change;
- maintenance duration;
- latency saving relative to no maintenance;
- the number of equivalent reads needed to repay maintenance cost.

A candidate is emitted only when the mode both removes packs and improves the measured operation. The generated recommendation is observational evidence and never changes runtime configuration. Incomplete or duplicate matrices fail the workflow instead of silently producing a partial policy.

## Resulting operational guidance

Use the [manual maintenance decision](../evidence/repository-aging-full-2026-11-07.md#manual-operating-decision) to compare current reads with the repository's own baseline, confirm fragmentation from the inventory, and price the expected read savings against maintenance cost. Match the cache state and operation to the deployment. For a one-pack repository the fixture creates more packs and stored bytes, and at 10–100 packs warm reopen is slower after maintenance. For those conditions, defer repack until another measured read or storage problem justifies it.

Begin with `compactOnly()` for a fragmentation or reopen problem. Use the read-optimized preset when measurements of clone/fetch negotiation or path history repay the cost of bitmap, commit-graph and Bloom-filter construction. Schedule explicit maintenance in a window with room for the measured read latency and storage activity. Keep the `PackRepackResult`, inventory, and workload latencies to validate the decision against the next maintenance cycle.

## Failure and recovery

An exception means storage or graph construction failed and is reported as `HibernateStorageException`. A `successful=false` result is different: JGit detected a concurrent ref race and deliberately declined to replace the source packs.

Temporary extensions follow the existing staging cleanup and crash model. A hard process termination can leave unpublished `jgit-storage-pack-` files in the configured temporary directory; they are derived state and must not be imported as durable Git data.
