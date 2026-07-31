# jgit-storage-hibernate-core

Use the familiar JGit `Repository` API while storing packs, refs, Reftables and reflogs in the relational database and persistence lifecycle your application already operates.

## Why use it

- no filesystem-backed `.git` directory is required;
- repository data can share the application's `DataSource`, schema lifecycle, backup and access controls;
- small pack-related extensions are constructed in bounded random-readable memory, while larger extensions spill once to temporary files;
- completed PACK/IDX/Reftable extensions are published atomically per logical pack;
- small committed extensions may remain inline while large extensions use ordered 1 MiB chunks;
- additive chunked payloads are pre-persisted invisibly without occupying the repository publication lock;
- pack replacement and compaction remain on the direct locked path so JGit's ref-race checks stay valid;
- chunk inserts use portable Hibernate JDBC batching and exact final chunk arrays;
- committed chunked extensions reuse an immutable metadata catalog instead of repeating open-time lookups;
- local publication hands the exact committed pack list to JGit's post-commit scan without another database transaction;
- packs remain hidden until transactionally published, avoiding partially visible writes;
- normal JGit ref updates publish Reftable state and queryable reflogs atomically;
- repository-scoped database locks coordinate independent `SessionFactory` instances;
- writer tokens and renewable leases support safe cleanup of durable invisible groups;
- public consumers do not import `org.eclipse.jgit.internal.*`;
- versioned H2, HSQLDB, PostgreSQL and Microsoft SQL Server migrations support production `migrate + validate` operation;
- logical repositories have an explicit, idempotent and isolated deletion lifecycle.

Git remains authoritative. This module changes where JGit stores repository data, not the Git semantics exposed to callers.

A concrete example is the [auditable approval-workflow service](../docs/use-cases/versioned-approval-workflows.md): workflow definitions are normal Git commits, but pack/ref state is published transactionally in PostgreSQL and repeated audit questions are served by the optional Search projection.

## Dependency

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-core</artifactId>
  <version>0.1.17</version>
</dependency>
```

## Production sequence

1. Apply the packaged Core Flyway migration for the selected database.
2. Start Hibernate with `hibernate.hbm2ddl.auto=validate`.
3. Register `CoreEntities.annotatedClasses()` in the application-managed persistence context.
4. Configure JDBC batching when the application owns the `SessionFactory`; the bundled provider applies conservative non-overriding defaults.
5. Construct `DefaultHibernateRepositoryFactory` from the native Hibernate `SessionFactory`.
6. Open repositories through `RepositoryName` and use normal public JGit APIs.
7. Monitor heap, temporary-disk capacity and database transaction-log growth for concurrent pack construction.
8. Retain an operator policy for expired durable invisible rows and stale spill files after abnormal termination.

```java
Flyway.configure()
    .dataSource(dataSource)
    .locations(CoreSchemaMigrations.POSTGRESQL_LOCATION)
    .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
    .load()
    .migrate();

try (HibernateGitStorage storage =
    new DefaultHibernateRepositoryFactory(sessionFactory)
        .open(new RepositoryName("domain-history"))) {
  Repository repository = storage.repository();
}
```

Use `CoreSchemaMigrations.HSQLDB_LOCATION` for HSQLDB or `CoreSchemaMigrations.SQL_SERVER_LOCATION` for Microsoft SQL Server. See the [consumer guide](../docs/consuming.md) and [adoption runbook](../docs/taxonomy-adoption.md) before provisioning a persistent database.

## Staged and chunked payload storage

```text
JGit writer
  -> bounded random-readable memory
  -> spill once to a temporary file when >256 KiB or the staging budget is full
  -> close completed extension without creating a database row
  -> direct atomic publication when every extension remains inline
  -> otherwise persist the complete additive chunked group invisibly
  -> acquire one short repository lock and publish the complete group
```

The in-memory path avoids creating, writing, rereading and deleting a temporary file for ordinary small PACK, IDX and Reftable extensions. One extension retains at most 256 KiB, and all repository instances share a 32 MiB process staging budget. Crossing either bound transparently preserves the previous file/chunk path.

The reader opens either an inline channel for committed `git_packs.data` or a chunked channel with bounded multi-chunk read-ahead. A successful pack-list scan publishes an immutable metadata catalog. Chunked files from that generation do not repeat the `git_packs` metadata query; selected locally published inline payloads use a separate hard-bounded handoff.

Temporary-disk and recovery details, including the optional 1/16/128 MiB profile, are documented in [Pack capacity and recovery](../docs/operations/capacity-and-recovery.md).

## JDBC batching and chunk arrays

`HibernateSessionFactoryProvider` applies these values only when the caller did not set them:

```properties
hibernate.jdbc.batch_size=8
hibernate.order_inserts=true
```

The eight-row batch matches the bounded chunk persistence window. `GitPackChunkEntity` uses `(pack_id, chunk_index)` as its Hibernate identity, so identifiers are known before SQL execution. The staged writer reads each chunk directly into the exact byte array retained by Hibernate instead of cloning a reusable one-MiB scratch buffer.

PostgreSQL `reWriteBatchedInserts` and SQL Server `useBulkCopyForBatchInsert` remain deployment-level experiments, not forced defaults. See [JDBC batching and pack-chunk keys](../docs/operations/jdbc-batching.md).

## Transaction guarantees

Core opens explicit Hibernate transactions for storage operations:

| Operation | Guarantee |
|---|---|
| Open/close staged extension | Bytes remain JVM-local in memory or a spill file; no database row is created. |
| Fully inline logical pack | Every expected extension is persisted and made visible in one repository-locked transaction. |
| Additive chunked logical pack | One complete lock-free transaction persists an invisible token-owned group; one short locked transaction publishes exactly that group. |
| Pack replacement/compaction | Source deletion and replacement publication remain in one repository-locked transaction. |
| Publication failure | Database work rolls back; token-owned uncommitted rows/chunks and local staging are removed through the normal failure/rollback path. |
| Pure local rollback | Memory reservations and spill files are released without database work. |
| Normal `RefUpdate` | Reftable publication and the matching queryable `git_reflog` row share one repository-scoped transaction. |
| Failed optimistic ref update | No Reftable change and no queryable reflog row are committed. |
| Repository deletion | Optional projection cleanup, reflogs, packs, chunks, lock and lifecycle state are removed transactionally. |

A reader therefore sees committed repository state rather than a partially published set of rows. This is the ACID storage benefit discussed in [eclipse-jgit/jgit discussion #251](https://github.com/eclipse-jgit/jgit/discussions/251).

Supplying an application-managed `SessionFactory` does not automatically join Core operations to an already active application transaction. Applications needing coordination with unrelated domain entities or Search indexing should use an explicit workflow/outbox and idempotent projection step.

## Recovering abandoned state

Memory-backed staging disappears on process failure. Spilled files use the `jgit-storage-pack-` prefix; they are derived state and must not be imported as durable packs. Remove stale files only after confirming that the owning process is gone.

A crash after adaptive pre-persistence can leave a complete invisible `committed=false` group. Clean expired groups through the lease-aware API rather than raw SQL:

```java
PackCleanupResult result =
    new PackStorageMaintenance(sessionFactory)
        .deleteExpiredUncommittedPacks(
            new RepositoryName("domain-history"),
            Instant.now().minus(Duration.ofHours(24)),
            Instant.now());
```

The service deletes a logical pack only when every persisted extension is old, uncommitted and lacks a current lease. See the [operations guide](../docs/operations/capacity-and-recovery.md).

## Repository deletion

Close every `HibernateGitStorage` opened by the factory, then call:

```java
RepositoryDeletionResult result =
    repositoryFactory.deleteRepository(new RepositoryName("domain-history"));
```

Deletion is idempotent and repository-scoped. Optional modules participate through `RepositoryDeletionParticipant`; the Search module supplies `SearchRepositoryDeletionParticipant`.

## Database ownership

Core owns:

- `git_packs`;
- `git_pack_chunks`;
- `git_repository_lifecycle`;
- `git_repository_lock`;
- `git_reflog`;
- the Core and optional legacy-adoption Flyway history tables.

Workflow, session, audit, outbox and other application-specific tables remain owned by the consuming application.

## Verification

Normal CI covers H2, HSQLDB, PostgreSQL 17 and SQL Server 2022, including fresh migration, legacy adoption, Hibernate validation, refs/reflogs, inline and chunked publication, replacement, rollback, deletion, lifecycle cascade and `SessionFactory` restart. The JGit compatibility matrix covers every supported JGit line.

Staging contract tests cover in-memory positional reads, budget- and threshold-driven spill, reads across the spill boundary, direct inline publication, multi-chunk publication, exact memory release and idempotent cleanup. The optional `pack-capacity` profile verifies 1 MiB, 16 MiB and 128 MiB payloads, while JMH measures object, commit, protocol, concurrency and focused twelve-MiB PostgreSQL workloads.
