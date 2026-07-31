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

JGit's current DFS garbage collector does not emit a persisted reverse-index extension. Reverse lookup remains available through JGit's normal in-memory/on-demand fallback, so Core does not expose a configuration flag that would suggest otherwise.

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

## When to run

Useful triggers include:

- after a large import or synchronization batch;
- when active pack count has grown substantially;
- before a read-heavy release, migration or export window;
- when clone/fetch, revision-walk or path-history measurements regress over repository age.

A starting operational policy is to evaluate maintenance when active pack count exceeds 32 and to prefer a Multi-Pack-Index evaluation before a full repack for very large repositories. Those numbers are not universal defaults: use the protocol, aging and lock-held metrics from the actual deployment.

## Failure and recovery

An exception means storage or graph construction failed and is reported as `HibernateStorageException`. A `successful=false` result is different: JGit detected a concurrent ref race and deliberately declined to replace the source packs.

Temporary extensions follow the existing staging cleanup and crash model. A hard process termination can leave unpublished `jgit-storage-pack-` files in the configured temporary directory; they are derived state and must not be imported as durable Git data.
