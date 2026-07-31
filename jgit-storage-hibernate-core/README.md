# jgit-storage-hibernate-core

Use the familiar JGit `Repository` API while storing packs, refs, Reftables and reflogs in the relational database and persistence lifecycle your application already operates.

## Why use it

- no filesystem-backed `.git` directory is required;
- repository state can share the application's `DataSource`, backup, access-control and schema lifecycle;
- Git object and ref semantics remain JGit semantics;
- small committed extensions may remain inline while large extensions use ordered 1 MiB chunks;
- large payload transfer can complete before the short repository publication lock is acquired;
- the complete logical pack is still made visible atomically;
- independent `SessionFactory` instances coordinate through repository-scoped database locks;
- durable repository ownership is separate from write coordination, so concurrent deletion cannot leave orphan packs;
- refs, Reftables and queryable reflogs are published transactionally;
- read-optimized maintenance can compact aged repositories and create JGit bitmaps, commit graphs and changed-path Bloom filters;
- versioned H2, HSQLDB, PostgreSQL and Microsoft SQL Server migrations support production `migrate + validate` operation;
- public consumers do not need to import `org.eclipse.jgit.internal.*`.

Git remains authoritative. This module changes where JGit stores repository data, not the Git semantics exposed to callers.

A concrete example is the [auditable approval-workflow service](../docs/use-cases/versioned-approval-workflows.md): workflow definitions are normal Git commits, while pack/ref state is stored transactionally in PostgreSQL and repeated audit questions can be served by the optional Search projection.

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
3. Register `CoreEntities.annotatedClasses()` in an application-managed persistence context.
4. Configure JDBC batching when the application owns the `SessionFactory`; the bundled provider applies conservative, non-overriding defaults.
5. Construct `DefaultHibernateRepositoryFactory` from the native Hibernate `SessionFactory`.
6. Open repositories through `RepositoryName` and use normal public JGit APIs.
7. Monitor temporary-disk capacity, database transaction latency and repository-lock metrics.
8. Retain an operator policy for expired uncommitted rows and stale temporary files after abnormal process termination.

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
  // Use ordinary JGit commands and APIs.
}
```

Use `CoreSchemaMigrations.HSQLDB_LOCATION` for an embedded HSQLDB deployment or `CoreSchemaMigrations.SQL_SERVER_LOCATION` for Microsoft SQL Server. SQL Server applications must add Flyway's SQL Server database module and the Microsoft JDBC driver. See the [consumer guide](../docs/consuming.md) and [pre-library adoption runbook](../docs/taxonomy-adoption.md) before provisioning an existing persistent schema.

## Adaptive pack publication

JGit often creates several related files for one logical pack: PACK, IDX, object-size index, bitmap, commit graph or Reftable. Readers must never observe only part of that set.

```text
JGit extension writers
  -> bounded temporary files supporting random reads during construction
  -> close all completed extensions; still no visible database state
  -> commitPack()
       -> one payload transaction for every chunked extension
            committed=false
            shared writer token
            active lease
            ordered 1 MiB chunks
       -> acquire repository publication lock
       -> validate token, row identity, size, representation and lease
       -> delete replaced packs
       -> persist remaining inline extensions
       -> switch the complete generation to committed=true
       -> commit atomically
```

Extensions up to 256 KiB retain the original one-transaction path and remain JVM-local until the final locked transaction. Larger extensions are persisted invisibly before the lock. This keeps tiny writes simple while removing large chunk transfer from the repository-scoped serialized interval.

Readers always filter on `committed=true`. A short-lived `committed=false` row with a valid lease is therefore normal during large-payload publication, but it is never readable as Git state.

The final transaction accepts a prepared extension only when all of these still match:

- repository, pack name and extension;
- generated row ID;
- declared file size;
- chunked representation;
- `committed=false`;
- the exact writer token;
- a non-expired writer lease.

Chunk completeness does not need a second scan under the lock: the payload row and all chunks were created by one earlier database transaction, so they either committed together or not at all.

## Repository lifecycle and concurrency

Two separate rows serve different purposes:

- `git_repository_lifecycle` is the durable repository identity and owns pack and lock rows through foreign-key cascades;
- `git_repository_lock` is used only for short ref, pack-publication and maintenance critical sections.

This separation is important. Large payload persistence may reference repository existence without holding or contending on the pessimistically locked publication row. Concurrent repository deletion remains safe:

- when pre-persistence commits first, lifecycle deletion cascades through the invisible rows and chunks;
- when deletion commits first, a later payload insert fails its lifecycle foreign key;
- when publication wins, the complete generation becomes visible atomically before deletion proceeds.

Independent logical repositories use independent lock rows and can publish concurrently.

## Reader path

A committed extension opens through either:

- an inline channel for `git_packs.data`; or
- a chunked channel with bounded, ordered multi-chunk read-ahead.

A successful pack-list scan publishes an immutable metadata catalog. Chunked files opened from that generation do not repeat the `git_packs` metadata lookup. After a successful local publication, Core hands the exact committed pack list back to JGit's first post-invalidation scan without another database transaction. An incomplete catalog, an independent repository instance or a legacy publication falls back to the authoritative database scan.

See [Committed pack metadata catalog](../docs/operations/committed-pack-catalog.md) and [Protocol storage metrics](../docs/protocol-storage-metrics.md).

## JDBC batching and keys

`HibernateSessionFactoryProvider` applies these values only when the caller did not configure them:

```properties
hibernate.jdbc.batch_size=8
hibernate.order_inserts=true
```

The eight-row batch matches the bounded chunk persistence window. `GitPackChunkEntity` uses `(pack_id, chunk_index)` as its Hibernate identity, so every identifier is known before SQL execution and chunk inserts can use real JDBC batches. Existing Flyway-managed schemas may retain their generated compatibility ID column; published payloads are not rewritten.

Applications constructing a framework-managed `SessionFactory` must configure batching themselves. PostgreSQL `reWriteBatchedInserts` and SQL Server `useBulkCopyForBatchInsert` remain deployment-level experiments rather than library defaults. See [JDBC batching and pack-chunk keys](../docs/operations/jdbc-batching.md).

## Transaction guarantees

| Operation | Guarantee |
|---|---|
| Open/write/close staged extension | Bytes remain in a bounded temporary file; no database row is visible. |
| Inline-only logical pack | Every extension and replacement is published under one repository lock and one Hibernate transaction. |
| Logical pack with chunked extensions | Payload rows/chunks commit invisibly first; one short locked transaction validates and publishes the complete generation. |
| Payload transaction failure | All prepared rows and chunks roll back; no repository state becomes visible. |
| Final publication failure | The publication transaction rolls back, then an exact writer-token cleanup removes prepared rows and chunks. |
| Process termination after pre-persistence | Prepared rows remain invisible and lease-protected until operator maintenance can safely reclaim them. |
| Pack replacement | Old rows/chunks are deleted in the same transaction that publishes the new complete generation. |
| Normal `RefUpdate` | Reftable publication and the matching queryable reflog row share one repository-scoped transaction. |
| Failed optimistic ref update | No Reftable change and no queryable reflog row are committed. |
| Repository deletion | Projection cleanup, reflogs, packs, chunks, lock and lifecycle are removed atomically. |

The practical outcome is that readers observe a committed repository generation rather than a partially transferred set of rows. This is the ACID storage benefit discussed in Eclipse JGit discussion #251.

### Boundary of the guarantee

Supplying an application-managed `SessionFactory` does not automatically join Core operations to an already active application transaction. Git object insertion, arbitrary application entities and optional Search indexing remain separate transactional steps.

Do not advertise the module as providing one implicit transaction over:

```text
application entity + Git object insertion + ref update + Search indexing
```

Applications needing cross-domain coordination should persist the published commit ID through an explicit workflow and use an outbox or idempotent projection step. The complete boundary is documented in the [approval-workflow use case](../docs/use-cases/versioned-approval-workflows.md#database-transaction-guarantees).

## Capacity and recovery

Temporary-disk capacity must cover concurrent open extensions and completed extensions awaiting `commitPack()`. Normal publication and rollback delete temporary files explicitly. A hard process termination can leave files with the `jgit-storage-pack-` prefix; they are unpublished derived state and must never be imported as packs.

A crash between payload persistence and final publication can also leave invisible database rows. Every such normal row has a writer token and lease. Clean expired groups through the public maintenance service rather than raw SQL:

```java
PackCleanupResult result =
    new PackStorageMaintenance(sessionFactory)
        .deleteExpiredUncommittedPacks(
            new RepositoryName("domain-history"),
            Instant.now().minus(Duration.ofHours(24)),
            Instant.now());
```

The service acquires the repository lock and deletes a pack name only when every persisted extension is old, uncommitted and has no active lease. A published, recent or partly active group is skipped. Database cascades remove chunks with their parent rows.

The optional capacity profile verifies 1 MiB, 16 MiB and 128 MiB payloads:

```bash
mvn -B -pl jgit-storage-hibernate-core -Ppack-capacity verify
```

See [Pack capacity and recovery](../docs/operations/capacity-and-recovery.md) for the complete memory, disk, lease, inspection and crash model.

## Read-optimized maintenance

Long-lived repositories accumulate incremental packs. `PackStorageMaintenance` can invoke JGit's DFS garbage collector in a controlled maintenance window:

```java
PackRepackResult result =
    new PackStorageMaintenance(sessionFactory)
        .repackForReads(new RepositoryName("domain-history"));
```

The read-optimized preset can compact packs and Reftables and create pack bitmaps, a commit graph and changed-path Bloom filters. The replacement generation uses the same atomic publication contract as ordinary writes. See [Repack, garbage collection and read acceleration](../docs/operations/repack-and-gc.md).

## Repository deletion

Close every `HibernateGitStorage` opened by the factory for the logical repository, then call:

```java
RepositoryDeletionResult result =
    repositoryFactory.deleteRepository(new RepositoryName("domain-history"));
```

Deletion is idempotent and repository-scoped. Optional modules participate through `RepositoryDeletionParticipant`; Search supplies `SearchRepositoryDeletionParticipant`. The final lifecycle-parent removal is also the database-level safety net against a concurrently committing invisible pack generation.

## Database ownership

Core owns:

- `git_repository_lifecycle`, the durable logical-repository identity;
- `git_repository_lock`, the short publication/ref/maintenance coordination row;
- `git_packs`, including visibility, writer lease, metadata and optional inline payload;
- `git_pack_chunks`, containing bounded large-payload rows;
- `git_reflog`;
- the Core Flyway history table;
- the one-time legacy-adoption history table when that path is used.

Workflow, session, audit, outbox and other application-specific tables remain owned by the consuming application.

## Verification

Every normal build covers H2 and HSQLDB migration and restart paths. Docker-enabled CI adds PostgreSQL 17.10 and SQL Server 2022 through Testcontainers. The supported JGit matrix currently covers 7.5, 7.6, 7.7 and 7.7.1 on Java 21.

Contract tests cover:

- random reads through open staging streams;
- inline-only and mixed inline/chunked publication;
- visibility while another `SessionFactory` holds the repository lock;
- PostgreSQL pre-persistence before an independently held publication lock;
- token/lease validation and final-publication rollback cleanup;
- repository deletion races and lifecycle cascades;
- close/reopen and `SessionFactory` restart;
- pack replacement, garbage collection and read-optimized repack;
- real JDBC batch execution and bounded capacity.

The four-thread JMH matrix compares one shared repository with independent repositories for filesystem, HSQLDB, PostgreSQL and PostgreSQL with HikariCP. Raw results and grouped historical charts are published by the existing performance workflow.
