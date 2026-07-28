# jgit-storage-hibernate-core

Use the familiar JGit `Repository` API while storing packs, refs, reftables and reflogs in the relational database and persistence lifecycle your application already operates.

## Why use it

- no filesystem-backed `.git` directory is required;
- repository data can share the application's `DataSource`, schema lifecycle, backup and access controls;
- pack-related payloads are persisted in bounded 1 MiB chunks instead of complete heap byte arrays;
- existing inline BLOB rows remain readable after upgrade;
- packs remain hidden until transactionally published, avoiding partially visible writes;
- normal JGit ref updates publish Reftable state and queryable reflogs atomically;
- repository-scoped database locks coordinate independent `SessionFactory` instances;
- writer tokens and renewable leases support safe abandoned-write cleanup;
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
  <version>0.1.15</version>
</dependency>
```

## Production sequence

1. Apply the packaged Core Flyway migration for the selected database.
2. Start Hibernate with `hibernate.hbm2ddl.auto=validate`.
3. Register `CoreEntities.annotatedClasses()` in the application-managed persistence context.
4. Construct `DefaultHibernateRepositoryFactory` from the native Hibernate `SessionFactory`.
5. Open repositories through `RepositoryName` and use normal public JGit APIs.
6. Schedule an operator-owned policy for `PackStorageMaintenance` when crash recovery of abandoned writes is required.

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

## Chunked payload storage

`git_packs` stores publication metadata. New binary payloads are stored in ordered rows in `git_pack_chunks`:

```text
JGit writer
  -> temporary file for random read/write during construction
  -> 1 MiB bounded chunks
  -> uncommitted git_packs metadata + git_pack_chunks rows
  -> transactional publication
```

The reader opens either:

- a compatibility channel for an existing inline `git_packs.data` BLOB; or
- a chunked channel that loads only the currently required 1 MiB row.

The migration does not rewrite existing published BLOBs. A later JGit repack naturally replaces them with chunked rows.

Temporary-disk capacity must cover concurrent open pack extensions. The detailed capacity envelope, the optional 1/16/128 MiB test profile and the Sandbox predecessor review are documented in [Pack capacity and recovery](../docs/operations/capacity-and-recovery.md).

## Transaction guarantees

Core opens explicit Hibernate transactions for database mutations:

| Operation | Guarantee |
|---|---|
| Pack-extension flush | Metadata and ordered chunks are committed with `committed=false`; normal pack reads filter them out. |
| Writer lease | The writer token and lease are renewed under the repository lock; a writer that lost ownership fails instead of overwriting the row. |
| Pack publication and replacement | New extensions are made visible, their leases are cleared and replaced packs/chunks are deleted in one transaction. |
| Normal rollback | Uncommitted metadata and chunks are deleted on a best-effort basis without masking the original JGit error. |
| Expired-write cleanup | Eligible extension groups are selected by age and lease under the repository lock, then chunks and metadata are deleted in one transaction. |
| Normal `RefUpdate` | Reftable pack publication and the matching queryable `git_reflog` row share one repository-scoped Hibernate transaction. |
| Failed optimistic ref update | No Reftable change and no queryable reflog row are committed. |
| Manual reflog import | `HibernateReflogWriter` can append externally produced history in an independent transaction. |
| Repository deletion | Optional projection cleanup, reflogs, pack chunks and pack/reftable metadata are removed in one transaction. |

The practical outcome is that a repository reader sees committed repository state rather than a partially published set of database rows. This is the ACID storage benefit described in [eclipse-jgit/jgit discussion #251](https://github.com/eclipse-jgit/jgit/discussions/251).

### Boundary of the guarantee

Supplying an application-managed `SessionFactory` does not automatically join Core operations to an already active application transaction. Git object insertion, Search indexing and arbitrary application entity changes remain separate transactional steps. A normal ref update and its queryable reflog are atomic with each other, but not automatically atomic with an unrelated application entity update.

Do not advertise this module as providing one transaction over:

```text
application entity + Git object insertion + ref update + Search indexing
```

Applications needing cross-domain coordination should persist the published commit ID through an explicit workflow and use an outbox/idempotent projection step, or keep Git as the authoritative domain record. The full contract and failure model are documented in the [application use case](../docs/use-cases/versioned-approval-workflows.md#database-transaction-guarantees).

## Recovering abandoned writes

A process crash can leave old invisible rows because JGit's rollback callback cannot run. Clean them through the lease-aware maintenance API:

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

- `git_packs`, including Reftable-related metadata and legacy inline payloads;
- `git_pack_chunks`, containing bounded payload rows for new writes;
- `git_repository_lock`, coordinating multi-instance mutations and maintenance;
- `git_reflog`;
- the Core Flyway history table;
- the one-time legacy-adoption Flyway history table when that path is used.

Workflow, session, audit, outbox and other application-specific tables remain owned by the consuming application.

## Verification

H2 and HSQLDB migration tests run on every build. HSQLDB coverage includes in-memory and file-backed restart scenarios. With Docker available, Testcontainers starts PostgreSQL 17.10 and SQL Server 2022. The suites verify fresh installation, 0.1.4 upgrades where applicable, pre-library adoption with unchanged BLOB checksums and reflog rows, Hibernate validation, adaptive inline/chunked repository history, refs, normal-update reflogs and `SessionFactory` restart.

Contract tests cover early flush followed by further writes, random reads across chunk boundaries, writer ownership loss, group-safe leased cleanup, deletion isolation and rollback. The optional `pack-capacity` Maven profile verifies 1 MiB, 16 MiB and 128 MiB payloads and is run manually and weekly by the existing performance workflow.
