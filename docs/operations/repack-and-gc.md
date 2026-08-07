# Repack, garbage collection and read acceleration

Long-lived repositories accumulate small `INSERT` and `RECEIVE` packs. Retaining every pack is transactionally correct, but eventually increases pack-index memory, object-lookup fan-out and the work required to negotiate clone and fetch requests.

Core exposes JGit's DFS garbage collector through `PackStorageMaintenance`. The service keeps the relational database as the durable authority while using the same atomic pack-publication path as ordinary writes. Persisted logical pack metadata keeps JGit's source, ordering and lifecycle decisions identical before and after a repository restart.

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

JGit's current DFS garbage collector does not emit a persisted reverse-index extension. The JGit versions in the supported matrix also expose no DFS pack extension that Core can use as a persisted Multi-Pack-Index. Reverse and multi-pack lookup therefore remain available only through JGit's normal in-memory/on-demand mechanisms in this version range; the maintenance API does not advertise an unsupported MIDX shortcut.

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

The smoke profile intentionally covers the first two bounded points, 1 and 10 pushes. The full scheduled/manual profile extends the same fixture to 100 and 1,000 pushes and PostgreSQL/HikariCP.

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

### Read result at ten pushes

| Operation | No maintenance | Compact-only | Read-optimized |
|---|---:|---:|---:|
| Reopen and lookup oldest object | 15.64 ms | 4.47 ms | 4.60 ms |
| Clone-style traversal | 0.264 ms | 0.211 ms | 0.226 ms |
| Oldest-object lookup | 0.012 ms | 0.009 ms | approximately unchanged from baseline |

The strongest smoke signal is repository reopen plus an old-object lookup: compact-only and read-optimized maintenance are both about 70% faster than the ten-pack baseline. Clone-style traversal also improves, although the microsecond-scale point estimates require caution.

The smoke result does **not** justify a universal automatic threshold. It establishes only that the useful crossover lies somewhere above one pack and at or below roughly ten small incremental packs for this fixture. The 100/1,000-push and PostgreSQL profiles are still required before enabling automatic maintenance.

## Resulting operational guidance

- Do not repack a newly created or one-pack repository.
- Begin evaluating maintenance around ten small incremental packs when reopen, oldest-object lookup, clone/fetch or index-memory metrics have measurably degraded.
- Prefer `compactOnly()` as the first intervention when the goal is simply fewer packs; it was faster and smaller than the read-optimized preset in the ten-push smoke fixture.
- Use the read-optimized preset when clone/fetch negotiation or path-history workloads justify bitmap, commit-graph and Bloom-filter construction.
- Trigger from a combination of active pack count, small-pack ratio, index bytes, unreachable bytes, measured lookup/fetch degradation and time since last maintenance—not from a single hard-coded count.
- Keep automatic maintenance opt-in until the full aging matrix has established production breakpoints.

A practical operator can record the condition before and after each maintenance run and retain `PackRepackResult` together with application latency. That deployment history is more reliable than assuming every repository has the same object mix and storage hardware.

## Failure and recovery

An exception means storage or graph construction failed and is reported as `HibernateStorageException`. A `successful=false` result is different: JGit detected a concurrent ref race and deliberately declined to replace the source packs.

Temporary extensions follow the existing staging cleanup and crash model. A hard process termination can leave unpublished `jgit-storage-pack-` files in the configured temporary directory; they are derived state and must not be imported as durable Git data.
