# SQL Server Search operations

This runbook applies to `jgit-storage-hibernate-search` on Microsoft SQL Server 2022.
It covers provisioning, copied-projection replacement, persistent Lucene ownership,
rebuild evidence, repository deletion and rollback.

## Authority and ownership

The ownership boundary is deliberately asymmetric:

| State | Owner | Recovery source |
|---|---|---|
| Git objects, packs, refs and reflogs | Core | Database backup and normal Git replication |
| `git_commit_index` rows | Search projection | Rebuild from Git history |
| Lucene files for `GitCommitIndex` | Search projection | Rebuild from `git_commit_index` or Git history |
| Application-specific history tables | Consuming application | Application-defined |

A successful Git publication is authoritative even when Search indexing fails. Search
failure must be reported and retried or rebuilt; it must not roll back a commit or ref
that Core already published.

## Required deployment dependencies

The consuming migration tool supplies Flyway and the SQL Server module:

```xml
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-core</artifactId>
  <version>13.0.0</version>
</dependency>
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-sqlserver</artifactId>
  <version>13.0.0</version>
</dependency>
<dependency>
  <groupId>com.microsoft.sqlserver</groupId>
  <artifactId>mssql-jdbc</artifactId>
  <version>13.4.0.jre11</version>
</dependency>
```

The application uses the released Search artifact and its Core dependency. Do not copy
migration SQL into the consuming repository.

## Schema and catalog selection

Choose the SQL Server database, catalog and default schema before applying either module.
Core and Search may share one schema, but they retain separate Flyway history tables:

```text
jgit_storage_hibernate_core_schema_history
jgit_storage_hibernate_search_schema_history
```

Apply Core first and Search second:

```java
Flyway.configure()
    .dataSource(dataSource)
    .locations(CoreSchemaMigrations.SQL_SERVER_LOCATION)
    .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
    .load()
    .migrate();

Flyway.configure()
    .dataSource(dataSource)
    .locations(SearchSchemaMigrations.SQL_SERVER_LOCATION)
    .table(SearchSchemaMigrations.SCHEMA_HISTORY_TABLE)
    .load()
    .migrate();
```

For a non-empty shared schema in which the module has never been installed, use the
module's pre-migration baseline version `0` only after verifying that its owned table and
history table are absent. Remove `baselineOnMigrate(true)` after the initial installation.

Start Hibernate with:

```properties
hibernate.hbm2ddl.auto=validate
hibernate.dialect=org.hibernate.dialect.SQLServerDialect
hibernate.connection.driver_class=com.microsoft.sqlserver.jdbc.SQLServerDriver
```

`update` and `create-drop` are not production migration mechanisms.

## Persistent Lucene directory

Production and restart testing must use a persistent directory owned by exactly one
active application instance unless a shared-index coordination design is provided:

```properties
hibernate.search.backend.type=lucene
hibernate.search.backend.directory.type=local-filesystem
hibernate.search.backend.directory.root=/srv/jgit-storage-hibernate/lucene
```

The directory is derived state, but its ownership still matters:

- stop all Search writers before backup, restore, analyzer changes or rebuild;
- do not let two uncoordinated JVMs write the same local filesystem directory;
- keep the directory outside ephemeral container layers;
- record the analyzer profile and application version with each backup;
- rebuild the directory after incompatible analyzer or mapping changes.

A database restore and an unrelated Lucene snapshot can disagree. The safe recovery path
is to restore authoritative Core data and rebuild Search.

## Replacing a copied Sandbox projection

Copied `git_commit_index` rows and copied Lucene files are not adopted silently. Use this
maintenance sequence:

1. stop application Search endpoints, indexers and background writers;
2. record row counts by repository and the minimum/maximum covered commit times;
3. record current refs and verify that every repository can be traversed through Core;
4. take a restorable SQL Server backup and preserve the previous application artifact;
5. archive or remove the copied `git_commit_index` only after traversal succeeds;
6. apply the released Search migration with its dedicated history table;
7. start Hibernate with `validate` and a controlled Lucene directory;
8. rebuild every logical repository through `CommitProjectionRebuilder`;
9. compare indexed commit counts and representative queries with the recorded Git refs;
10. enable Search endpoints only after the verification gates pass.

Do not serialize Hibernate entities as a migration interchange format. Rebuilding from Git
is preferred to preserving copied projection rows.

## Public rebuild contract

`CommitProjectionRebuilder` clears one logical repository's old SQL and Lucene projection,
resolves all commit-valued refs, deduplicates reachable commits through `RevWalk` and indexes
oldest-first without changing Git:

```java
CommitProjectionRebuilder rebuilder =
    new CommitProjectionRebuilder(sessionFactory);

RebuildResult result =
    rebuilder.rebuild(
        repository,
        new RepositoryName("payment-platform"),
        progress -> maintenanceLog.write(progress));
```

Progress events expose:

- repository name;
- lifecycle state (`CLEARING`, `DISCOVERING`, `INDEXING`, `COMPLETED`, `FAILED`,
  `INTERRUPTED`);
- discovered ref-tip count;
- visited, indexed and skipped commit counts;
- removed projection count;
- current object ID;
- failure type and message for terminal failures.

An interrupted or failed run may leave a partial projection. A retry is safe because the
next invocation removes that partial state before traversing authoritative history again.
The caller must stop concurrent projection writers for the same repository during rebuild.

## Query verification gates

Before enabling Search endpoints, verify at least:

- full-message Unicode search;
- exact author and committer email filters;
- added, modified and deleted path coverage using first-parent semantics;
- merge-commit path semantics relative to the first parent;
- inclusive author-time and committer-time bounds;
- compound text/identity/path/time queries;
- deterministic offset/limit pages ordered by the selected timestamp;
- isolation between at least two logical repositories;
- successful full-text queries after closing and reopening the application-managed
  `SessionFactory` with the same Lucene directory.

Full-text queries are relevance-ranked. Structured queries without text are ordered by the
selected timestamp descending and then object ID, so offset/limit pages are stable.

## Repository deletion

Register `SearchRepositoryDeletionParticipant` with
`DefaultHibernateRepositoryFactory`:

```java
DefaultHibernateRepositoryFactory factory =
    new DefaultHibernateRepositoryFactory(
        sessionFactory,
        List.of(new SearchRepositoryDeletionParticipant()));
```

Core deletion and Search row removal then share the Core repository-deletion transaction.
Hibernate Search updates the corresponding Lucene documents from the entity removals. After
deletion, verify both the SQL row count and a full-text query for the deleted repository,
and verify that another logical repository is unchanged.

## Backup and rollback

Before cut-over, retain:

- a restorable SQL Server backup;
- the previous application artifact and configuration;
- the previous Lucene directory or an explicit decision to rebuild it;
- migration output, row-count evidence and rebuild progress records.

Rollback is:

1. stop all writers;
2. restore the SQL Server backup;
3. restore the matching previous application artifact and configuration;
4. restore the matching Lucene directory, or remove it and run the previous supported
   rebuild procedure;
5. verify refs, representative object reads and previous Search queries before reopening
   traffic.

Never roll back only the database or only the Lucene directory and assume they remain
consistent.

## Automated evidence

The SQL Server Testcontainers suite verifies clean Core-plus-Search migration, Hibernate
schema validation, Unicode root/normal/merge indexing, first-parent add/modify/delete paths,
author/committer/path/time/compound queries, stable pagination, two-repository rebuild,
interrupted retry, transactional deletion, projection-failure isolation and persistent
Lucene restart.

The H2 suite independently verifies rebuild progress events, idempotency and partial-state
cleanup on retry. These tests are part of ordinary Maven verification; GitHub Actions only
invokes Maven and does not encode a separate database test procedure.
