# jgit-storage-hibernate-core

Use the familiar JGit `Repository` API while storing packs, refs, reftables and reflogs in the relational database and persistence lifecycle your application already operates.

## Why use it

- no filesystem-backed `.git` directory is required;
- repository data can share the application's `DataSource`, schema lifecycle, backup and access controls;
- pack-related payloads are constructed in bounded temporary files instead of complete heap byte arrays;
- completed PACK/IDX/Reftable extensions are persisted and published atomically per logical pack;
- small committed extensions may remain inline while large extensions use ordered 1 MiB chunks;
- chunk inserts use portable Hibernate JDBC batching without generated-key round trips per chunk;
- calibrated network measurements select a bounded 16-chunk default while allowing explicit 32/50 tuning;
- immutable chunk payloads switch automatically to shared-transaction stateless ORM at the measured 16-MiB crossover;
- a production durable striped queue can persist up to 50 receiver records in one Hibernate transaction and one compatible JDBC batch, or flush a partial batch after a configurable collection window;
- committed chunked extensions reuse an immutable pack-list metadata catalog instead of repeating the same open-time lookup;
- local publication hands the exact committed pack list to JGit's post-commit scan without another database transaction;
- existing inline BLOB, generated chunk-ID and legacy uncommitted rows remain readable after upgrade;
- packs remain hidden until transactionally published, avoiding partially visible writes;
- normal JGit ref updates publish Reftable state and queryable reflogs atomically;
- repository-scoped database locks coordinate independent `SessionFactory` instances;
- writer tokens and renewable leases retain safe cleanup for legacy durable uncommitted rows;
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
  <version>0.11.3</version>
</dependency>
```

## Production sequence

1. Apply the packaged Core Flyway migration for the selected database.
2. Start Hibernate with `hibernate.hbm2ddl.auto=validate`.
3. Register `CoreEntities.annotatedClasses()` in the application-managed persistence context.
4. Configure JDBC batching when the application owns the `SessionFactory`; the bundled provider applies evidence-based non-overriding defaults.
5. Construct `DefaultHibernateRepositoryFactory` from the native Hibernate `SessionFactory`.
6. Open repositories through `RepositoryName` and use normal public JGit APIs.
7. Monitor JVM temporary-disk capacity and heap retained by concurrent large-pack writers.
8. Retain an operator policy for legacy durable uncommitted rows and stale staging files after abnormal process termination.

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

Use `CoreSchemaMigrations.HSQLDB_LOCATION` for an embedded HSQLDB deployment or `CoreSchemaMigrations.SQL_SERVER_LOCATION` for Microsoft SQL Server. SQL Server applications must add Flyway's `flyway-sqlserver` database module and the Microsoft JDBC driver. Fresh databases, shared schemas, existing 0.1.4 installations and the copied pre-library Sandbox/Taxonomy schema require different procedures. See the [consumer guide](../docs/consuming.md) and [adoption runbook](../docs/taxonomy-adoption.md) before provisioning a persistent database.

## Staged and chunked payload storage

```text
JGit writer
  -> temporary file for random read/write during extension construction
  -> close completed PACK / IDX / Reftable extension without a database row
  -> commitPack() acquires the repository lock once for the logical pack
  -> persist inline data or ordered 1 MiB chunks
  -> make every expected extension visible in the same Hibernate transaction
```

The reader opens either:

- an inline channel for a committed `git_packs.data` BLOB; or
- a chunked channel with bounded multi-chunk read-ahead.

A successful pack-list scan publishes an immutable metadata catalog for committed extensions. Chunked files opened from that generation do not repeat the `git_packs` metadata query; inline payload bytes remain outside the catalog and use the bounded database fallback.

After a successful local pack or Reftable publication, Core keeps JGit's normal `clearCache()` and packs-changed event ordering. The publication transaction returns the exact generated row IDs, file sizes and storage modes, and the first post-invalidation `listPacks()` call consumes that complete snapshot without opening a Hibernate transaction. An incomplete catalog or an independent repository instance still uses the authoritative database scan. A publication containing a legacy durable-uncommitted extension likewise disables the handoff rather than adding another metadata query; the next scan refreshes once from the database. See [Committed pack metadata catalog](../docs/operations/committed-pack-catalog.md).

The migration does not rewrite existing published BLOBs. A later JGit repack can replace them naturally. Legacy uncommitted rows produced by the previous/base writer contract remain publishable or removable through the compatibility path.

Temporary-disk capacity must cover concurrent open extensions and completed extensions waiting for their logical pack's publication callback. The detailed capacity envelope, optional 1/16/128 MiB profile, crash model and Sandbox predecessor review are documented in [Pack capacity and recovery](../docs/operations/capacity-and-recovery.md).

## JDBC batching, keys and automatic writer selection

`HibernateSessionFactoryProvider` applies these values only when the caller did not set them:

```properties
hibernate.jdbc.batch_size=16
hibernate.order_inserts=true
jgit.storage.hibernate.pack.chunk_writer=auto
jgit.storage.hibernate.pack.stateless_min_payload_bytes=16777216
```

The 16-row default has the best saved-network-exchanges-per-additional-MiB ratio in the calibrated 8/16/32/50 Toxiproxy matrix. `GitPackChunkEntity` uses `(pack_id, chunk_index)` as its Hibernate identity, so every identifier is known before SQL execution and chunk inserts can be sent through real JDBC batches.

Latency-sensitive deployments may configure a larger bounded pack window:

```properties
jgit.storage.hibernate.pack.chunk_batch_size=32
```

The bundled provider applies the same value to `hibernate.jdbc.batch_size` when the generic Hibernate setting is absent. Framework-managed applications must configure both values consistently. Values 1 through 64 are accepted; one active writer retains at most approximately that many MiB of final chunk arrays. Existing Flyway-managed schemas may retain their generated `git_pack_chunks.id` column; it remains read-only compatibility data and published payloads are not rewritten. The provider deliberately does not change update ordering for unrelated application entities.

The 16/128/512-MiB PostgreSQL matrix found that stateless chunk insertion reduces allocation by about 16–18%, keeps the same JDBC execution shape, substantially reduces flush/GC work and preserves byte-identical reopen integrity. Core therefore keeps small chunked payloads on the stateful reference path and uses a shared-transaction child `StatelessSession` at or above 16 MiB. Applications can force `stateful`, force `stateless` or move the byte threshold.

PostgreSQL `reWriteBatchedInserts` and SQL Server `useBulkCopyForBatchInsert` remain optional deployment-level experiments, not forced library defaults. See [JDBC batching and pack-chunk writers](../docs/operations/jdbc-batching.md) and the complete [network latency and batch-size evidence](../docs/operations/network-latency-and-chunk-batching.md).

## Durable receiver batching

`DurableStripedWriteQueue<C, R>` is available for receiver records that can be persisted in repository-homogeneous atomic batches. Its production defaults collect at most 50 records or 64 MiB. Reaching a bound releases the batch immediately; otherwise every available record for that repository is released after the configurable two-millisecond collection window.

`HibernateDurableBatchProcessor` wraps the complete batch in exactly one `HibernateTransactionContext` transaction and optionally acquires the repository coordination row. It sets the active session's JDBC batch size to the number of collected records, validates one result per record before commit and completes submission futures only after commit. A Core H2 integration test observes one real JDBC batch for 50 compatible inserts.

The queue is bounded by command count and bytes, preserves FIFO order per repository and allows independent stripes to progress concurrently. This component does not silently merge arbitrary complete JGit push/ref operations. Their compare-and-set, replacement and visibility semantics require a storage-specific batch processor. See [Durable striped receiver batching](../docs/operations/durable-striped-write-queue.md).

## Transaction guarantees

Core opens explicit Hibernate transactions for database mutations:

| Operation | Guarantee |
|---|---|
| Open/close staged extension | Bytes remain JVM-local; no `git_packs` or chunk row is created. |
| Logical-pack publication | Every expected staged/legacy extension is validated, persisted and made committed under one repository lock and one Hibernate transaction. |
| Publication failure | All rows and chunks inserted or deleted by that attempt roll back; the staged files remain available for JGit's rollback callback. |
| Pure staged rollback | Temporary files are deleted without opening a database transaction. |
| Mixed/legacy rollback | Local files and matching durable uncommitted rows/chunks are removed under the repository lock. |
| Pack replacement | Replacement rows/chunks are deleted in the same transaction that publishes the new complete pack. |
| Expired-write cleanup | Legacy durable extension groups are selected by age and lease under the repository lock, then chunks and metadata are deleted in one transaction. |
| Normal `RefUpdate` | Reftable pack publication and the matching queryable `git_reflog` row share one repository-scoped Hibernate transaction. |
| Failed optimistic ref update | No Reftable change and no queryable reflog row are committed. |
| Manual reflog import | `HibernateReflogWriter` can append externally produced history in an independent transaction. |
| Durable receiver batch | Every command in one repository-homogeneous batch commits in one transaction before any future reports success. |
| Repository deletion | Optional projection cleanup, reflogs, pack chunks and pack/reftable metadata are removed in one transaction. |

The practical outcome is that a repository reader sees committed repository state rather than a partially published set of database rows. This is the ACID storage benefit described in [eclipse-jgit/jgit discussion #251](https://github.com/eclipse-jgit/jgit/discussions/251).

### Boundary of the guarantee

Supplying an application-managed `SessionFactory` does not automatically join Core operations to an already active application transaction. Git object insertion, Search indexing and arbitrary application entity changes remain separate transactional steps. A normal ref update and its queryable reflog are atomic with each other, but not automatically atomic with an unrelated application entity update.

Do not advertise this module as providing one transaction over:

```text
application entity + Git object insertion + ref update + Search indexing
```

Applications needing cross-domain coordination should persist the published commit ID through an explicit workflow and use an outbox/idempotent projection step, or keep Git as the authoritative domain record. The full contract and failure model are documented in the [application use case](../docs/use-cases/versioned-approval-workflows.md#database-transaction-guarantees).

## Recovering abandoned state

### Local staging files

Normal publication and rollback explicitly delete files created with the `jgit-storage-pack-` prefix. A hard JVM termination or operating-system deletion failure can leave stale files in the configured temporary directory. They have no committed database row and must never be imported as durable pack state. Remove them only after verifying that the owning process is no longer active.

The implementation does not register every extension with the JVM-wide `deleteOnExit` registry, avoiding an unbounded registry in long-lived servers.

### Legacy durable uncommitted rows

Older/base writers may leave invisible `committed=false` rows when JGit's rollback callback cannot run. Clean them through the lease-aware maintenance API:

```java
PackCleanupResult result =
    new PackStorageMaintenance(sessionFactory)
        .deleteExpiredUncommittedPacks(
            new RepositoryName("domain-history"),
            Instant.now().minus(Duration.ofHours(24)),
            Instant.now());
```

The service deletes a pack name only when every persisted extension is old, uncommitted and lacks a current lease. A group containing a published, recent or actively leased extension is skipped. Do not replace this with a raw SQL delete. See the [operations guide](../docs/operations/capacity-and-recovery.md).

## Repository deletion

Close every `HibernateGitStorage` opened by a factory for the logical repository, then call:

```java
RepositoryDeletionResult result =
    repositoryFactory.deleteRepository(new RepositoryName("domain-history"));
```

Deletion is idempotent and filters all statements by the exact repository name. Open handles are rejected to prevent stale repository-scoped DFS caches. Optional modules participate through `RepositoryDeletionParticipant`; the Search module supplies `SearchRepositoryDeletionParticipant`. Chunk rows are removed with their pack metadata, and rollback preserves both.

## Database ownership

Core owns:

- `git_packs`, including committed publication metadata, legacy inline payloads and compatibility fields for durable uncommitted rows;
- `git_pack_chunks`, containing bounded payload rows for large committed or legacy staged extensions;
- `git_repository_lock`, coordinating multi-instance publication, ref mutation and maintenance;
- `git_reflog`;
- the Core Flyway history table;
- the one-time legacy-adoption Flyway history table when that path is used.

Workflow, session, audit, outbox and other application-specific tables remain owned by the consuming application.

## Verification

H2 and HSQLDB migration tests run on every build. HSQLDB coverage includes in-memory and file-backed restart scenarios. With Docker available, Testcontainers starts PostgreSQL 17.10 and SQL Server 2022. The suites verify fresh installation, 0.1.4 upgrades where applicable, pre-library adoption with unchanged BLOB checksums and reflog rows, Hibernate validation, adaptive inline/chunked repository history, refs, normal-update reflogs and `SessionFactory` restart.

Contract tests cover pre-publication invisibility, random read-back through open staging streams, atomic multi-extension publication, transaction failure, mixed legacy/staging rollback, chunked publication, replacement, deletion, legacy lease-aware cleanup, real JDBC batch execution, committed-pack catalog lifecycle, zero-query local pack-list handoff through normal `ObjectInserter.addPack()` and authoritative refresh after legacy publication. Queue integration tests verify one 50-row JDBC batch, 50-record transactions, partial batches after the configured wait, repository isolation, pre-commit result validation, rollback and post-commit acknowledgement. Automatic writer tests cover stateful below 16 MiB, stateless at the threshold, explicit overrides, shared rollback and byte-identical reopen. The JGit compatibility matrix verifies the close-before-`commitPack()` lifecycle across all supported versions. Dedicated workflows retain the local writer comparison, calibrated network RTT matrix, batch-size selection matrix, 16/128/512-MiB writer threshold and the remaining read-ahead, aging, queue-scheduling and concurrent-publication investigations.
