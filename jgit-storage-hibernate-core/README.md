# jgit-storage-hibernate-core

Use the familiar JGit `Repository` API while storing packs, refs, reftables and reflogs in the relational database and persistence lifecycle your application already operates.

## Why use it

- no filesystem-backed `.git` directory is required;
- repository data can share the application's `DataSource`, database operations, schema lifecycle, backup and access controls;
- packs remain hidden until transactionally published, avoiding partially visible writes;
- refs use JGit's Reftable/DFS abstractions and advertise atomic ref transactions;
- public consumers do not import `org.eclipse.jgit.internal.*`;
- versioned H2 and PostgreSQL migrations support production `migrate + validate` operation.

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
3. Construct `DefaultHibernateRepositoryFactory` from the application-managed or standalone `SessionFactory`.
4. Open repositories through `RepositoryName` and use normal public JGit APIs.

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

Fresh databases, shared schemas and existing 0.1.4 installations require different baseline handling. See the [consumer and migration operations guide](../docs/consuming.md) before provisioning a persistent database.

## Transaction guarantees

Core opens explicit Hibernate transactions for database mutations:

| Operation | Guarantee |
|---|---|
| Pack-extension flush | The row is committed with `committed=false`; normal pack reads filter it out. |
| Pack publication and replacement | New extensions are made visible and replaced packs are deleted in one transaction; a runtime failure rolls back the transaction. |
| Abandoned pack cleanup | Uncommitted rows are deleted transactionally on a best-effort basis without masking the original JGit error. |
| Ref update | The DFS/Reftable backend reports `performsAtomicTransactions() == true`; Reftable files are published through the transactional pack mechanism. |
| Queryable reflog append | Each append is committed in its own transaction and rolled back on failure. |

The practical outcome is that a repository reader sees committed repository state rather than a partially published set of database rows. This is the ACID storage benefit described in [eclipse-jgit/jgit discussion #251](https://github.com/eclipse-jgit/jgit/discussions/251).

### Boundary of the guarantee

Supplying an application-managed `SessionFactory` does not automatically join Core operations to an already active application transaction. The current implementation opens its own sessions and transactions. A Git object flush, ref update and reflog append can therefore be separate transactional steps, and they are not automatically atomic with an arbitrary application entity update.

Do not advertise this module as providing one transaction over:

```text
application entity + Git object insertion + ref update + Search indexing
```

Applications needing cross-domain coordination should persist the published commit ID through an explicit workflow and use an outbox/idempotent projection step, or keep Git as the authoritative domain record. The full contract and failure model are documented in the [application use case](../docs/use-cases/versioned-approval-workflows.md#database-transaction-guarantees).

## Database ownership

Core owns:

- `git_packs`, including Reftable-related files;
- `git_reflog`;
- the Core Flyway history table.

Workflow, session, audit, outbox and other application-specific tables remain owned by the consuming application.

## Verification

H2 migration tests run on every build. With Docker available, Testcontainers starts PostgreSQL 17.10 and verifies fresh installation, adoption of an immutable 0.1.4 fixture, Hibernate validation, repository history, refs, reflogs and `SessionFactory` restart. The Search module also contains an executable application use-case test covering commit/ref publication and indexed history queries.
