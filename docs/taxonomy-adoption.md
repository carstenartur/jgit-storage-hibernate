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

Flyway must finish before the Spring persistence context starts schema validation.

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
`HibernateGitStorage` handle after use.

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
without the logical pack-identity constraint. Adoption is intentionally a separate migration stream;
it is not hidden inside a normal fresh-install migration.

### Preconditions

1. Stop every writer and take a restorable database backup.
2. Verify that `git_packs` and `git_reflog` are the copied Sandbox/Taxonomy tables.
3. Verify that no Core Flyway history table claims the schema is already managed.
4. Record repository counts and BLOB checksums.
5. Run the read-only preflight before any Flyway DDL.

```java
try (Connection connection = dataSource.getConnection()) {
  LegacyCoreSchemaAdoption.LegacySchemaReport report =
      LegacyCoreSchemaAdoption.requireSafeToAdopt(connection);
  if (!report.requiresAdoption()) {
    throw new IllegalStateException("Schema is already adopted; do not run the legacy migration");
  }
}
```

The preflight rejects:

- missing legacy columns;
- a partial state containing only one of `committed` or `committed_at`;
- null/incomplete pack rows or negative file sizes;
- duplicate `(repository_name, pack_name, pack_extension)` identities.

It never chooses a duplicate row automatically. Operators must resolve duplicates explicitly from
application knowledge or restore a known-good backup.

### Run the one-time adoption migration

The schema is intentionally non-empty before the dedicated adoption history table exists. Baseline
that history stream at version `0` so Flyway can record the pre-existing state and then execute the
version `1` adoption migration:

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

Use `POSTGRESQL_LEGACY_ADOPTION_LOCATION` for PostgreSQL. The migration:

- adds `committed` and `committed_at`;
- marks every pre-existing pack extension committed;
- initializes `committed_at` from `created_at`;
- adds the unique logical pack identity;
- adds the committed-pack lookup index;
- leaves every stored BLOB byte unchanged.

The adoption history configuration is used only for this one-time operation. Do not leave
`baselineOnMigrate(true)` enabled as an unrestricted application-startup repair mechanism.

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

Close all handles opened by the factory, then call:

```java
RepositoryDeletionResult result =
    repositoryFactory.deleteRepository(new RepositoryName("taxonomy-workspace-42"));
```

The operation:

- rejects deletion while the same factory still owns an open handle for that name;
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
- start Hibernate with `validate`;
- reopen at least two logical repositories and traverse their main histories;
- confirm normal `RefUpdate` operations create queryable reflog entries;
- test repository deletion, repeated deletion and participant rollback;
- run the full Maven build with Docker so PostgreSQL Testcontainers coverage executes;
- deploy a non-SNAPSHOT `jgit-storage-hibernate` release and pin Taxonomy to that version.
