# Repack, garbage collection and read acceleration

Long-lived repositories accumulate small `INSERT` and `RECEIVE` packs. Retaining every pack is transactionally correct, but eventually increases pack-index memory, object-lookup fan-out and the work required to reconstruct repositories or negotiate clone and fetch requests.

Core exposes JGit's DFS garbage collector through `PackStorageMaintenance`. The relational database remains the durable authority, and replacement packs use the same atomic publication path as ordinary writes. Persisted logical pack metadata preserves JGit's source, ordering and lifecycle decisions across repository and provider restarts.

The complete measurement contract and raw-evidence interpretation are documented in [Repository-aging policy evidence](repository-aging-policy-evidence.md).

## Choosing a maintenance mode

For simple compaction:

```java
PackStorageMaintenance maintenance =
    new PackStorageMaintenance(sessionFactory);

PackRepackResult result =
    maintenance.repack(
        new RepositoryName("customer-history"),
        PackRepackOptions.compactOnly());

if (!result.successful()) {
  // A ref changed while JGit validated the replacement. Retry in a later
  // maintenance window; no partial replacement was published.
}
```

For clone/fetch and path-history workloads:

```java
PackRepackResult result =
    maintenance.repackForReads(new RepositoryName("customer-history"));
```

The read-optimized preset requests:

- one primary reachable-object pack;
- JGit pack bitmaps;
- a commit graph;
- changed-path Bloom filters;
- Reftable compaction;
- normal unreachable-garbage retention and coalescing limits.

Use `compactOnly()` when the objective is fewer packs without paying for auxiliary read indexes. A custom immutable `PackRepackOptions` can select individual features; Bloom filters require commit-graph generation.

## Transaction and concurrency model

JGit performs graph traversal, delta selection and extension construction through normal DFS interfaces. The Hibernate backend stages the resulting PACK, IDX, bitmap, commit-graph and Reftable extensions outside database visibility.

Final replacement:

1. acquires the repository-scoped database lock;
2. persists and publishes the complete logical replacement;
3. removes replaced parent rows and chunks through the database cascade;
4. commits the new generation atomically.

Readers see either the old generation or the complete replacement. They never see a half-written pack. JGit revalidates refs before replacement; `PackRepackResult.successful()` is `false` if a concurrent ref change makes the prepared replacement unsafe.

Maintenance for different logical repositories uses different lock rows and can run concurrently. Operators should still serialize repacks for the same repository.

## Result interpretation

`PackRepackResult` records:

- active PACK and Reftable counts before and after;
- logical extension bytes before and after;
- source and generated pack-description counts;
- end-to-end maintenance duration;
- whether JGit accepted the replacement.

`storedByteDelta()` is `after - before`. It can be positive when bitmaps or commit graphs intentionally exchange storage for read performance. `packReduction()` reports the active ordinary-pack reduction independently.

## Complete age-axis result

Workflow run [`33878467696`](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/33878467696) measured one, ten, 32, 100, 300 and 1,000 deterministic incremental packs on HSQLDB, PostgreSQL and PostgreSQL-Hikari with cold and warm JGit caches. The corrected, durable decision record is [Full repository-aging age-axis evidence](../evidence/repository-aging-full-2026-09-04.md).

### Structural crossover

| History | No maintenance | After compact-only | Pack reduction | Compact stored-byte delta |
|---:|---:|---:|---:|---:|
| 1 push | 1 pack | 2 packs | -1 | +1,076 B |
| 10 pushes | 10 packs | 2 packs | 8 | -8,860 B |
| 32 pushes | 32 packs | 2 packs | 30 | -33,148 B |
| 100 pushes | 100 packs | 2 packs | 98 | -108,220 B |
| 300 pushes | 300 packs | 2 packs | 298 | -329,021 B |
| 1,000 pushes | 1,000 packs | 2 packs | 998 | -1,101,821 B |

One-pack maintenance is rejected. From ten packs onward, compaction reduces both the active pack count and stored extension bytes in this fixture.

### Cold reopen plus oldest-object lookup

| Packs | PostgreSQL none | PostgreSQL compact | Break-even | HikariCP none | HikariCP compact | Break-even |
|---:|---:|---:|---:|---:|---:|---:|
| 10 | 10.427 ms | 3.577 ms | 12 reads | 6.839 ms | 2.443 ms | 14 reads |
| 32 | 26.292 ms | 3.701 ms | 6 reads | 18.464 ms | 2.652 ms | 6 reads |
| 100 | 76.094 ms | 4.538 ms | 4 reads | 53.363 ms | 3.376 ms | 5 reads |
| 300 | 248.965 ms | 4.076 ms | 3 reads | 173.933 ms | 2.822 ms | 3 reads |
| 1,000 | 605.727 ms | 3.801 ms | 3 reads | 407.971 ms | 2.581 ms | 4 reads |

Cold unmaintained reconstruction grows strongly with the number of incremental packs. Compact-only and read-optimized maintenance are close on this path; compact-only normally has the lower maintenance and storage cost.

### Warm reopen is a different workload

On PostgreSQL and PostgreSQL-Hikari, maintenance regresses the warm reopen path at ten, 32 and 100 packs. It becomes faster again at 300 and 1,000 packs, but the measured compact-only payback remains much higher:

| Packs | PostgreSQL compact payback | HikariCP compact payback |
|---:|---:|---:|
| 300 | 702 warm reopens | 394 warm reopens |
| 1,000 | 306 warm reopens | 188 warm reopens |

This is why pack count alone is not a safe automatic trigger.

## Provider restart at ten packs

Two complete protected-main runs rebuilt the repository handle, Hibernate `SessionFactory` and connection pool before retained reads. Each run measured PostgreSQL and SQL Server, cold and warm cache state, and three independent repeats.

| Backend / cache | First run: none → compact | Corrected rerun: none → compact | Reproduced direction |
|---|---:|---:|---|
| PostgreSQL cold | 18.556 → 6.046 ms | 18.384 → 6.042 ms | about two-thirds faster |
| PostgreSQL warm | 3.660 → 4.555 ms | 3.819 → 4.805 ms | slower |
| SQL Server cold | 28.972 → 8.586 ms | 36.467 → 12.129 ms | large benefit; absolute time varies |
| SQL Server warm | 7.069 → 8.101 ms | 7.061 → 8.081 ms | slower |

PostgreSQL cold is highly reproducible: the baseline and compact-only means differ by less than 1% between runs. SQL Server warm is also almost identical. SQL Server cold absolute time varies between runners, but the maintenance benefit remains large and non-overlapping.

In the corrected rerun, compact-only pays back after a mean 5.78 equivalent PostgreSQL cold reopens and 3.60 SQL Server cold reopens. Read-optimized needs 7.80 and 4.83 respectively. Every warm repeat regresses, so the measured warm reopen path has no finite payback.

Evidence is retained in:

- [first provider-restart record](../evidence/repository-aging-restart-reproducibility-2026-09-04.md);
- [corrected rerun record](../evidence/repository-aging-restart-reproducibility-2026-09-04-rerun.md);
- [corrected rerun payback](../evidence/repository-aging-restart-payback-2026-09-04-rerun.md);
- [cross-run comparison](../evidence/repository-aging-restart-cross-run-2026-09-04.md).

The stable lesson is lifecycle-aware: a cold provider-reconstruction problem can repay compaction quickly, while the same pack count on an already warm path can become slower.

## Multi-Pack-Index status

The supported JGit version exposes `PackExt.MULTI_PACK_INDEX`. That extension alone is not sufficient for the Hibernate backend to offer MIDX maintenance.

JGit's MIDX pack description also identifies its covered packs and optional base MIDX. The current relational entity and `listPacks()` reconstruction persist scalar pack metadata but not those relationships. Before MIDX can be used safely, the project needs:

- relationship columns/tables and versioned migrations;
- publication and replacement handling for MIDX dependencies;
- reconstruction after repository and provider restart;
- deletion/garbage-collection semantics;
- PostgreSQL and SQL Server integration tests.

Until then, compact-only and read-optimized repack are the supported maintenance choices.

## Operational guidance

- Never maintain a new one-pack repository.
- Do not trigger maintenance from pack count alone.
- Measure the relevant path and distinguish cold reconstruction from an already warm repository.
- For a cold reopen or catalog-fan-out problem, prefer `compactOnly()` first.
- Use the read-optimized preset when bitmap, commit-graph or changed-path workloads justify it.
- Retain `PackRepackResult`, operation latency, cache/lifecycle state and time since previous maintenance.
- Keep automatic maintenance opt-in until the SQL Server age axis and live-interference evidence are complete.

## Failure and recovery

An exception indicates storage or graph-construction failure and is reported as `HibernateStorageException`. A `successful=false` result is different: JGit detected a concurrent ref race and deliberately declined to replace the source packs.

Temporary extensions follow the existing staging cleanup and crash model. A hard process termination can leave unpublished `jgit-storage-pack-` files in the configured temporary directory; they are derived state and must not be imported as durable Git data.
