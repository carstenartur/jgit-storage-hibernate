# Taxonomy adoption runbook

This runbook describes how a Spring-managed application such as Taxonomy can replace its copied
Hibernate/JGit storage implementation with `jgit-storage-hibernate-core` without losing existing Git
objects or changing application transaction ownership.

## Supported database paths

| Database | Fresh installation | Pre-library adoption | Restart coverage |
|---|---:|---:|---:|
| HSQLDB | yes | yes | in-memory and file-backed |
| PostgreSQL | yes | yes | Testcontainers |
| H2 | yes | existing 0.1.4 baseline path | in-memory |

Use the public constants rather than copying classpath strings:

```java
CoreSchemaMigrations.HSQLDB_LOCATION
CoreSchemaMigrations.POSTGRESQL_LOCATION
CoreSchemaMigrations.HSQLDB_LEGACY_ADOPTION_LOCATION
CoreSchemaMigrations.POSTGRESQL_LEGACY_ADOPTION_LOCATION
CoreSchemaMigrations.SCHEMA_HISTORY_TABLE
CoreSchemaMigrations.LEGACY_ADOPTION_SCHEMA_HISTORY_TABLE
```

HSQLDB file-backed Hibernate configuration can use:

```properties
hibernate.connection.url=jdbc:hsqldb:file:/var/lib/taxonomy/taxonomy
hibernate.connection.driver_class=org.hsqldb.jdbc.JDBCDriver
hibernate.dialect=org.hibernate.dialect.HSQLDialect
hibernate.hbm2ddl.auto=validate
```

Flyway must finish before the Spring persistence context starts schema validation. With Flyway 12 or
newer, include `flyway-database-hsqldb` at the same version as `flyway-core`; database-specific
support is no longer provided by `flyway-core` alone.

## Application-managed persistence context

The application owns the `DataSource`, `EntityManagerFactory`/`SessionFactory`, transaction manager
and shutdown lifecycle. The storage facade owns only the JGit repository handle returned by
`open(...)`.

Register the public entity list during persistence bootstrap instead of importing entity
implementation packages throughout application code:

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

A Spring/JPA application can keep its existing `EntityManagerFactory` and expose the storage factory
from the native Hibernate contract:

```java
@Bean
HibernateRepositoryFactory hibernateRepositoryFactory(EntityManagerFactory entityManagerFactory) {
  SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
  return new DefaultHibernateRepositoryFactory(sessionFactory);
}
```

When generic history search is enabled, install its transactional deletion participant as well:

```java
@Bean
HibernateRepositoryFactory hibernateRepositoryFactory(EntityManagerFactory entityManagerFactory) {
  SessionFactory sessionFactory = entityManagerFactory.unwrap(SessionFactory.class);
  return new DefaultHibernateRepositoryFactory(
      sessionFactory,
      List.of(new SearchRepositoryDeletionParticipant()));
}
```

Do not close the application-managed `SessionFactory` from repository code. Close every
`HibernateGitStorage` handle after use. `ApplicationManagedSessionFactoryIntegrationTest` verifies
that Core can share application entities and leaves shutdown ownership with the application.

## Fresh installation

For a dedicated empty schema, run the normal migration location and history table:

```java
Flyway.configure()
    .dataSource(dataSource)
    .locations(CoreSchemaMigrations.HSQLDB_LOCATION) // or POSTGRESQL_LOCATION
    .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
    .load()
    .migrate();
```

For a shared schema that contains unrelated application tables but no Core tables, use the
pre-migration baseline version `0` once, as documented in [consuming.md](consuming.md).

## Adopting the pre-library Taxonomy schema

The copied pre-library schema contains `git_packs` rows without `committed` and `committed_at`, and
without the logical pack-identity constraint. It also uses the JPA-default length 255 for
`git_packs.pack_extension` and `git_reflog.ref_name`; the released Core contract requires lengths 32
and 1024 respectively. Adoption is intentionally a separate migration stream; it is not hidden
inside a normal fresh-install migration.

### Preconditions

1. Stop every writer and take a restorable database backup.
2. Verify that `git_packs` and `git_reflog` are the copied Sandbox/Taxonomy tables, including the
   legacy `VARCHAR(255)` lengths for `pack_extension` and `ref_name`.
3. Verify that no Core Flyway history table claims the schema is already managed.
4. Record repository counts, ordered BLOB checksums and the existing reflog rows.
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
- `pack_extension` values longer than 32 characters;
- duplicate `(repository_name, pack_name, pack_extension)` identities.

It never chooses a duplicate row or truncates an oversized extension automatically. Operators must
resolve such rows explicitly from application knowledge or restore a known-good backup.

### Run the adoption migration stream

The schema is intentionally non-empty before the dedicated adoption history table exists. Baseline
that history stream at version `0` so Flyway can record the pre-existing state and then execute all
pending adoption migrations:

```java
Flyway.configure()
    .dataSource(dataSource)
    .locations(CoreSchemaMigrations.HSQLDB_LEGACY_ADOPTION_LOCATION)
    .table(CoreSchemaMigrations.LEGACY_ADOPTION_SCHEMA_HISTORY_TABLE)
    .baselineOnMigrate(true)
    .baselineVersion(CoreSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION)
    .baselineDescription("before pre-library core adoption")
    .load()
    .migrate();
```

Use `POSTGRESQL_LEGACY_ADOPTION_LOCATION` for PostgreSQL. The migration stream:

- adds `committed` and `committed_at`;
- marks every pre-existing pack extension committed;
- initializes `committed_at` from `created_at`;
- adds the unique logical pack identity;
- adds the committed-pack lookup index;
- narrows `git_packs.pack_extension` from the exact legacy length 255 to 32 after preflight;
- widens `git_reflog.ref_name` from the exact legacy length 255 to 1024;
- leaves every stored BLOB byte and existing reflog row unchanged.

The adoption history configuration is used only for this one-time operation. Do not leave
`baselineOnMigrate(true)` enabled as an unrestricted application-startup repair mechanism.

### Follow-up for databases already adopted with 0.1.8

Version 0.1.8 published adoption migration V1 without the two column-length changes. V1 remains
immutable so existing Flyway checksums stay valid. The correction is adoption migration V2.

For a database whose adoption history already contains successful version `1`, stop writers and take
a new backup, run `LegacyCoreSchemaAdoption.requireSafeToAdopt(connection)` again, and then run the
same HSQLDB or PostgreSQL adoption location. In this state `report.requiresAdoption()` is
expected to be `false` because V1 already added the committed-state columns; the remaining preflight
checks must still pass before V2 is allowed to execute. Do not delete or re-baseline either Flyway
history table.
After migration, the adoption history must contain successful version `2`,
`git_packs.pack_extension` must report length 32 and `git_reflog.ref_name` length 1024. Compare the
recorded BLOB checksums and reflog rows before starting Hibernate validation or enabling writers.

### Establish normal Core history

After successful adoption, baseline the normal Core stream at the current physical schema version:

```java
Flyway.configure()
    .dataSource(dataSource)
    .locations(CoreSchemaMigrations.HSQLDB_LOCATION)
    .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
    .baselineOnMigrate(true)
    .baselineVersion(CoreSchemaMigrations.CURRENT_SCHEMA_VERSION)
    .baselineDescription("adopted pre-library core schema")
    .load()
    .migrate();
```

Remove `baselineOnMigrate(true)` from normal deployment configuration after this one-time step.
Start Hibernate with `hibernate.hbm2ddl.auto=validate`, reopen every logical repository and verify
refs, commit traversal and reflogs before enabling writes.

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

The reftable update and the `git_reflog` row now join one repository-scoped Hibernate transaction.
Create, fast-forward, forced update, link and delete operations therefore become queryable through
`Repository.getReflogReader(...)` without a second manual writer call. Failed optimistic updates do
not append a reflog row. `HibernateReflogWriter` remains available only for importing externally
created history.

## Deleting a logical repository

Close all handles for the repository name that share the same application-managed `SessionFactory`,
then call:

```java
RepositoryDeletionResult result =
    repositoryFactory.deleteRepository(new RepositoryName("taxonomy-workspace-42"));
```

The operation:

- coordinates all `DefaultHibernateRepositoryFactory` instances sharing that `SessionFactory`;
- rejects deletion while any coordinated handle for the repository name remains open;
- prevents a new coordinated handle from opening while deletion is active;
- removes optional participant projections, reflogs and pack/reftable rows in one transaction;
- rolls the complete deletion back when a participant fails;
- is idempotent;
- filters every statement by the exact logical repository name;
- closes the repository cache scope before returning.

A repeated deletion returns zero counts. A repository with another logical name is not affected.
With Hibernate Search, pass `SearchRepositoryDeletionParticipant` to the factory so entity removal
also updates the search backend.

## Verification checklist

Before switching Taxonomy to the library artifact:

- run the adoption procedure against a restored production-like database;
- compare ordered SHA-256 checksums of all `git_packs.data` values before and after migration;
- compare all existing `git_reflog` rows before and after migration;
- verify `pack_extension` length 32 and `ref_name` length 1024 through JDBC metadata;
- start Hibernate with `validate`;
- reopen at least two logical repositories and traverse their main histories;
- confirm normal `RefUpdate` operations create queryable reflog entries;
- test repository deletion, repeated deletion and participant rollback;
- run the full Maven build with Docker so PostgreSQL Testcontainers coverage executes;
- deploy a non-SNAPSHOT `jgit-storage-hibernate` release and pin Taxonomy to that version.
