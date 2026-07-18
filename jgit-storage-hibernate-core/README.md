# jgit-storage-hibernate-core

Use the familiar JGit `Repository` API while storing packs, refs, reftables and reflogs in the relational database and persistence lifecycle your application already operates.

## Why use it

- no filesystem-backed `.git` directory is required;
- repository data can share the application's `DataSource`, transaction manager and Hibernate lifecycle;
- packs remain hidden until committed, avoiding partially visible writes;
- refs use JGit's Reftable/DFS abstractions and reflogs remain queryable;
- public consumers do not import `org.eclipse.jgit.internal.*`;
- versioned H2 and PostgreSQL migrations support production `migrate + validate` operation.

Git remains authoritative. This module changes where JGit stores repository data, not the Git semantics exposed to callers.

## Dependency

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-core</artifactId>
  <version>0.1.5</version>
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

## Database ownership

Core owns:

- `git_packs`, including Reftable-related files;
- `git_reflog`;
- the Core Flyway history table.

Workflow, session, audit, outbox and other application-specific tables remain owned by the consuming application.

## Verification

H2 migration tests run on every build. With Docker available, Testcontainers starts PostgreSQL 17.10 and verifies fresh installation, adoption of an immutable 0.1.4 fixture, Hibernate validation, repository history, refs, reflogs and `SessionFactory` restart.
