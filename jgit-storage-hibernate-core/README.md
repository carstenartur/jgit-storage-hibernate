# jgit-storage-hibernate-core

Use the familiar JGit `Repository` API while storing packs, refs, reftables and reflogs in the relational database and persistence lifecycle your application already operates.

## Why use it

- no filesystem-backed `.git` directory is required;
- repository data can share the application's `DataSource`, database operations, schema lifecycle, backup and access controls;
- packs remain hidden until transactionally published, avoiding partially visible writes;
- normal JGit ref updates publish Reftable state and queryable reflogs atomically;
- public consumers do not import `org.eclipse.jgit.internal.*`;
- versioned H2, HSQLDB and PostgreSQL migrations support production `migrate + validate` operation;
- logical repositories have an explicit, idempotent and isolated deletion lifecycle.

Git remains authoritative. This module changes where JGit stores repository data, not the Git semantics exposed to callers.

A concrete example is the [auditable approval-workflow service](../docs/use-cases/versioned-approval-workflows.md): workflow definitions are normal Git commits, but pack/ref state is published transactionally in PostgreSQL and repeated audit questions are served by the optional Search projection.

## Dependency

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-core</artifactId>
  <version>0.1.7</version>
</dependency>
```

## Production sequence

1. Apply the packaged Core Flyway migration for the selected database.
2. Start Hibernate with `hibernate.hbm2ddl.auto=validate`.
3. Register `CoreEntities.annotatedClasses()` in the application-managed persistence context.
4. Construct `DefaultHibernateRepositoryFactory` from the native Hibernate `SessionFactory`.
5. Open repositories through `RepositoryName` and use normal public JGit APIs.

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

Use `CoreSchemaMigrations.HSQLDB_LOCATION` for an embedded HSQLDB deployment. Fresh databases, shared schemas, existing 0.1.4 installations and the copied pre-library Taxonomy schema require different procedures. See the [consumer guide](../docs/consuming.md) and [Taxonomy adoption runbook](../docs/taxonomy-adoption.md) before provisioning a persistent database.

## Transaction guarantees

Core opens explicit Hibernate transactions for database mutations:

| Operation | Guarantee |
|---|---|
| Pack-extension flush | The row is committed with `committed=false`; normal pack reads filter it out. |
| Pack publication and replacement | New extensions are made visible and replaced packs are deleted in one transaction; a runtime failure rolls back the transaction. |
| Abandoned pack cleanup | Uncommitted rows are deleted transactionally on a best-effort basis without masking the original JGit error. |
| Normal `RefUpdate` | Reftable pack publication and the matching queryable `git_reflog` row share one repository-scoped Hibernate transaction. |
| Failed optimistic ref update | No Reftable change and no queryable reflog row are committed. |
| Manual reflog import | `HibernateReflogWriter` can still append externally produced history in an independent transaction. |
| Repository deletion | Optional projection cleanup, reflogs and pack/reftable rows are removed in one transaction. |

The practical outcome is that a repository reader sees committed repository state rather than a partially published set of database rows. This is the ACID storage benefit described in [eclipse-jgit/jgit discussion #251](https://github.com/eclipse-jgit/jgit/discussions/251).

### Boundary of the guarantee

Supplying an application-managed `SessionFactory` does not automatically join Core operations to an already active application transaction. Git object insertion, Search indexing and arbitrary application entity changes remain separate transactional steps. A normal ref update and its queryable reflog are atomic with each other, but not automatically atomic with an unrelated application entity update.

Do not advertise this module as providing one transaction over:

```text
application entity + Git object insertion + ref update + Search indexing
```

Applications needing cross-domain coordination should persist the published commit ID through an explicit workflow and use an outbox/idempotent projection step, or keep Git as the authoritative domain record. The full contract and failure model are documented in the [application use case](../docs/use-cases/versioned-approval-workflows.md#database-transaction-guarantees).

## Repository deletion

Close every `HibernateGitStorage` opened by a factory for the logical repository, then call:

```java
RepositoryDeletionResult result =
    repositoryFactory.deleteRepository(new RepositoryName("domain-history"));
```

Deletion is idempotent and filters all statements by the exact repository name. Open handles are rejected to prevent stale repository-scoped DFS caches. Optional modules participate through `RepositoryDeletionParticipant`; the Search module supplies `SearchRepositoryDeletionParticipant`.

## Database ownership

Core owns:

- `git_packs`, including Reftable-related files;
- `git_reflog`;
- the Core Flyway history table;
- the one-time legacy-adoption Flyway history table when that path is used.

Workflow, session, audit, outbox and other application-specific tables remain owned by the consuming application.

## Verification

H2 and HSQLDB migration tests run on every build. HSQLDB coverage includes in-memory and file-backed restart scenarios. With Docker available, Testcontainers starts PostgreSQL 17.10 and verifies fresh installation, 0.1.4 upgrades, pre-library adoption with unchanged BLOB checksums, Hibernate validation, repository history, refs, normal-update reflogs and `SessionFactory` restart. Deletion tests cover open-handle protection, isolation, idempotence and rollback. The Search module verifies transactional projection deletion.
