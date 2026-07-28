# Pre-library Core adoption runbook

This runbook describes how an application such as Sandbox or Taxonomy can replace its copied
Hibernate/JGit storage implementation with `jgit-storage-hibernate-core` without losing existing Git
objects or changing application transaction ownership.

## Supported database paths

| Database | Fresh installation | Pre-library adoption | Automated evidence |
|---|---:|---:|---|
| HSQLDB | yes | yes | in-memory and file-backed restart tests |
| PostgreSQL | yes | yes | PostgreSQL 17.10 Testcontainers |
| Microsoft SQL Server | yes | yes | SQL Server 2022 Testcontainers |
| H2 | yes | existing 0.1.4 baseline path | in-memory tests |

Use the public constants rather than copying classpath strings:

```java
CoreSchemaMigrations.HSQLDB_LOCATION
CoreSchemaMigrations.POSTGRESQL_LOCATION
CoreSchemaMigrations.SQL_SERVER_LOCATION
CoreSchemaMigrations.HSQLDB_LEGACY_ADOPTION_LOCATION
CoreSchemaMigrations.POSTGRESQL_LEGACY_ADOPTION_LOCATION
CoreSchemaMigrations.SQL_SERVER_LEGACY_ADOPTION_LOCATION
CoreSchemaMigrations.SCHEMA_HISTORY_TABLE
CoreSchemaMigrations.LEGACY_ADOPTION_SCHEMA_HISTORY_TABLE
```

The Flyway database module must match the selected database and the `flyway-core` version:

- HSQLDB: `org.flywaydb:flyway-database-hsqldb`;
- PostgreSQL: `org.flywaydb:flyway-database-postgresql`;
- SQL Server: `org.flywaydb:flyway-sqlserver`.

Flyway must finish before Hibernate starts schema validation.

## Application-managed persistence context

The application owns the `DataSource`, `EntityManagerFactory`/`SessionFactory`, transaction manager
and shutdown lifecycle. The storage facade owns only the JGit repository handle returned by
`open(...)`.

Register the public entity list during persistence bootstrap instead of importing implementation
packages throughout application code:

```java
List<Class<?>> managedTypes = new ArrayList<>();
managedTypes.addAll(CoreEntities.annotatedClasses());
managedTypes.addAll(SearchEntities.annotatedClasses()); // only when Search is used
managedTypes.addAll(applicationEntityClasses);
```

For native Hibernate bootstrap:

```java
MetadataSources metadata = new MetadataSources(serviceRegistry);
CoreEntities.annotatedClasses().forEach(metadata::addAnnotatedClass);
applicationEntityClasses.forEach(metadata::addAnnotatedClass);
SessionFactory sessionFactory = metadata.buildMetadata().buildSessionFactory();
```

A Spring/JPA application can expose the public storage factory from its existing persistence context:

```java
@Bean
HibernateRepositoryFactory hibernateRepositoryFactory(EntityManagerFactory entityManagerFactory) {
  SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
  return new DefaultHibernateRepositoryFactory(sessionFactory);
}
```

When generic history search is enabled, install its transactional deletion participant as well:

```java
return new DefaultHibernateRepositoryFactory(
    sessionFactory,
    List.of(new SearchRepositoryDeletionParticipant()));
```

Do not close the application-managed `SessionFactory` from repository code. Close every
`HibernateGitStorage` handle after use.

## Fresh installation

For a dedicated empty schema, run the normal migration location and history table:

```java
Flyway.configure()
    .dataSource(dataSource)
    .locations(CoreSchemaMigrations.POSTGRESQL_LOCATION)
    .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
    .load()
    .migrate();
```

Use `HSQLDB_LOCATION` or `SQL_SERVER_LOCATION` for the corresponding database. For a shared schema
that contains unrelated application tables but no Core tables, use the documented pre-migration
baseline version `0` once. Start Hibernate with `hibernate.hbm2ddl.auto=validate` only after the
migration succeeds.

SQL Server uses the entity-compatible types exercised by the integration suite:

- `datetimeoffset(7)` for Java `Instant` values;
- `varbinary(max)` for pack and chunk payloads;
- `bit` for committed state;
- `nvarchar` for fields mapped with `@Nationalized`;
- an index on `repository_name` with `ref_name` as an included column, because the complete
  `nvarchar(255) + nvarchar(1024)` pair exceeds SQL Server's nonclustered-index key limit.

## Adopting the pre-library schema

The copied pre-library schema contains `git_packs` rows without `committed` and `committed_at`, and
without the logical pack-identity constraint. It also uses the JPA-default length 255 for
`git_packs.pack_extension` and `git_reflog.ref_name`; the released Core contract requires lengths 32
and 1024 respectively. Adoption is intentionally a separate migration stream; it is not hidden
inside a normal fresh-install migration.

### Preconditions

1. Stop every writer and take a restorable database backup.
2. Verify that `git_packs` and `git_reflog` are the copied Sandbox/Taxonomy tables.
3. Verify that no Core Flyway history table incorrectly claims the schema is managed.
4. Record repository counts, ordered SHA-256 checksums of every inline `git_packs.data` value and all
   existing reflog rows, including timestamps.
5. Run the read-only preflight before any Flyway DDL.

```java
try (Connection connection = dataSource.getConnection()) {
  LegacyCoreSchemaAdoption.LegacySchemaReport report =
      LegacyCoreSchemaAdoption.requireSafeToAdopt(connection);
  if (!report.requiresAdoption()) {
    throw new IllegalStateException("Schema is already adopted; do not run adoption V1 again");
  }
}
```

The preflight rejects:

- missing legacy columns;
- a partial state containing only one of `committed` or `committed_at`;
- null/incomplete pack rows or negative file sizes;
- `pack_extension` values longer than 32 Unicode characters;
- duplicate `(repository_name, pack_name, pack_extension)` identities.

It never chooses a duplicate row or truncates an oversized extension automatically. Resolve rejected
rows explicitly from application knowledge or restore a known-good backup.

### Run the adoption migration stream

The schema is intentionally non-empty before the dedicated adoption history table exists. Baseline
that history stream at version `0` and execute all pending adoption migrations:

```java
Flyway.configure()
    .dataSource(dataSource)
    .locations(CoreSchemaMigrations.POSTGRESQL_LEGACY_ADOPTION_LOCATION)
    .table(CoreSchemaMigrations.LEGACY_ADOPTION_SCHEMA_HISTORY_TABLE)
    .baselineOnMigrate(true)
    .baselineVersion(CoreSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION)
    .baselineDescription("before pre-library core adoption")
    .load()
    .migrate();
```

Use `HSQLDB_LEGACY_ADOPTION_LOCATION` or `SQL_SERVER_LEGACY_ADOPTION_LOCATION` as appropriate. The
migration stream:

- adds `committed` and `committed_at`;
- marks every pre-existing pack extension committed;
- initializes `committed_at` from `created_at`;
- adds the unique logical pack identity and committed-pack lookup index;
- narrows `git_packs.pack_extension` from the exact legacy length 255 to 32 after preflight;
- widens `git_reflog.ref_name` from 255 to 1024;
- normalizes SQL Server temporal values from copied `datetime2(6)` mappings to
  Hibernate-7-compatible `datetimeoffset(7)`;
- leaves every stored BLOB byte and logical reflog row unchanged.

The wider copied SQL Server `nvarchar(2048)` reflog-message column is retained. The Core entity writes
at most 2,000 characters, and preserving the wider physical column avoids an unnecessary narrowing
conversion of valid legacy data.

The adoption history configuration is used only for this one-time operation. Do not leave
`baselineOnMigrate(true)` enabled as an unrestricted startup repair mechanism.

### Databases already adopted with 0.1.8

Version 0.1.8 published adoption migration V1 without the two column-length changes. V1 remains
immutable so existing Flyway checksums stay valid; V2 is the follow-up normalization migration.

For a database whose adoption history already contains successful version `1`, stop writers, take a
new backup and run the read-only preflight again. In this state `report.requiresAdoption()` is expected
to be `false` because V1 already added the committed-state columns. The remaining checks must still
pass before V2 executes. Do not delete or re-baseline either Flyway history table.

After migration, the adoption history must contain successful version `2`, `pack_extension` must
report length 32 and `ref_name` length 1024. Compare the recorded BLOB checksums and reflog rows before
starting Hibernate validation or enabling writers.

### Establish normal Core history

After successful adoption, baseline the normal Core stream at the current physical schema version and
apply all later migrations, including repository locks, chunk storage and writer leases:

```java
Flyway.configure()
    .dataSource(dataSource)
    .locations(CoreSchemaMigrations.POSTGRESQL_LOCATION)
    .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
    .baselineOnMigrate(true)
    .baselineVersion(CoreSchemaMigrations.CURRENT_SCHEMA_VERSION)
    .baselineDescription("adopted pre-library core schema")
    .load()
    .migrate();
```

Again, select the HSQLDB or SQL Server location for that database. Remove `baselineOnMigrate(true)`
after this one-time step. Start Hibernate with `validate`, reopen every logical repository and verify
refs, commit traversal and reflogs before enabling writes.

The SQL Server integration suite proves both directions required for a cut-over:

- existing inline pack BLOB checksums and reflog rows survive adoption and the original commit remains
  traversable;
- a new post-adoption repository can publish a non-compressible 1 MiB object through chunk storage,
  update a ref and write a queryable reflog.

## Normal ref updates and queryable reflogs

Callers use standard JGit APIs:

```java
RefUpdate update = repository.updateRef("refs/heads/main");
update.setExpectedOldObjectId(oldId);
update.setNewObjectId(newId);
update.setRefLogIdent(actor);
update.setRefLogMessage("commit: update workflow", true);
RefUpdate.Result result = update.update();
```

The Reftable update and the `git_reflog` row join one repository-scoped Hibernate transaction. Create,
fast-forward, forced update, link and delete operations therefore become queryable through
`Repository.getReflogReader(...)`. Failed optimistic updates do not append a reflog row.
`HibernateReflogWriter` remains available only for importing externally created history.

## Deleting a logical repository

Close all handles for the repository name that share the same application-managed `SessionFactory`,
then call:

```java
RepositoryDeletionResult result =
    repositoryFactory.deleteRepository(new RepositoryName("taxonomy-workspace-42"));
```

The operation coordinates all factories sharing the same `SessionFactory`, rejects deletion while a
coordinated handle remains open, blocks new handles during deletion, removes optional participant
projections and Core rows in one transaction, rolls back on participant failure and is idempotent.
With Hibernate Search, pass `SearchRepositoryDeletionParticipant` to the factory.

## Verification checklist

Before switching an application to the released library artifact:

- run the adoption procedure against a restored production-like database;
- compare ordered SHA-256 checksums of all legacy `git_packs.data` values before and after migration;
- compare all existing `git_reflog` rows and their timestamps before and after migration;
- verify `pack_extension` length 32 and `ref_name` length 1024 through JDBC metadata;
- start Hibernate with `validate`;
- reopen at least two logical repositories and traverse their main histories;
- confirm normal `RefUpdate` operations create queryable reflog entries;
- verify a sufficiently large, non-compressible payload creates chunk rows while small payloads may
  remain inline by design;
- test repository deletion, repeated deletion and participant rollback;
- run the full Maven build with Docker so PostgreSQL and SQL Server Testcontainers coverage executes;
- deploy a non-SNAPSHOT `jgit-storage-hibernate` release and pin the application to that version.

Rollback is database restore plus redeployment of the previous application artifact. No reverse
migration is claimed.
