# Pack capacity and recovery

## Current payload model

Core currently stores each pack-related file in one database binary column and exposes it to JGit through an in-memory byte-array channel.

The write path buffers the complete file and creates a byte-array snapshot before persistence. The read path loads the complete payload. Channel positions are ultimately represented by Java array indexes. This design is appropriate for controlled small and medium repositories, but it is not a streaming large-object implementation.

## Capacity planning

Before production use, measure the largest generated `PACK`, `IDX`, bitmap and Reftable payloads with production-like history. Size the JVM so that the largest payload plus JGit/Hibernate copies, transaction state, caches and concurrent operations fit comfortably without approaching the heap limit.

A practical acceptance test must include:

- an import or repack representative of the largest expected repository;
- concurrent readers and writers at the expected peak;
- close/reopen and application restart;
- database backup and restore;
- GC, allocation and transaction-duration monitoring.

Do not infer large-repository readiness from the small JMH latency benchmark alone. The benchmark compares backend overhead and hot-cache operations; it is not a maximum-pack-size certification.

No fixed maximum byte count is claimed in `0.1.x`. Deployments that cannot keep the largest individual pack-related payload safely in heap should wait for or contribute a chunked/streaming payload store.

## Uncommitted rows after failure

Pack extensions are persisted with `committed=false` before publication. Normal JGit rollback removes them. A process crash can leave old uncommitted rows because the rollback callback cannot run.

Readers ignore these rows, so they do not become visible Git state. They can nevertheless consume storage.

Until a leased, writer-aware cleanup API is released, cleanup is an operator action and requires exclusive repository maintenance:

1. stop all writers for the logical repository on every node;
2. take a database backup;
3. inspect uncommitted rows and their `created_at` timestamps;
4. verify that no active operation owns the affected repository;
5. delete only rows with `committed=false` that predate the maintenance window;
6. reopen the repository and run an object/ref/reflog smoke test.

Example inspection query:

```sql
select repository_name, pack_name, pack_extension, file_size, created_at
from git_packs
where committed = false
order by created_at;
```

Example deletion for one exclusively locked repository and an operator-selected cutoff:

```sql
delete from git_packs
where repository_name = :repository
  and committed = false
  and created_at < :cutoff;
```

Do not schedule this deletion concurrently with writers. A future automated cleanup service must add writer identity or lease information before it can distinguish an abandoned row from a slow active write safely.

## Planned scalable direction

The intended evolution is to separate transactional publication metadata from payload storage, using chunked rows, JDBC large-object streaming or a pluggable payload store. Any replacement must preserve invisible-before-commit publication, random reads, rollback, replacement of old packs and repository deletion semantics.
