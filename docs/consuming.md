# Consuming jgit-storage-hibernate

This guide covers the two storage-facing artifacts:

```text
io.github.carstenartur:jgit-storage-hibernate-core
io.github.carstenartur:jgit-storage-hibernate-search
```

Core provides database-backed JGit repositories. Search is optional and adds generic Hibernate
Search/Lucene projections. The higher-level `java-analysis` and `architecture` modules build on this
foundation, but their Hibernate entity layers remain incubating in the `0.1.x` line; consult their
module guides before registering those entities.

The documented release line is **0.1.14**. It uses Java 21, Hibernate ORM 7.4.5.Final, Hibernate
Search 8.4.0.Final and Flyway 13.0.0. Keep those versions aligned through the published artifacts and
tested deployment stack instead of overriding only one side of the stack.

## Supported databases and operating model

| Database | Core | Search | Tested version and intended use |
|---|---|---|---|
| PostgreSQL | Supported | Supported | PostgreSQL 17.10 through Testcontainers; persistent development, staging and production |
| Microsoft SQL Server | Supported | Not currently shipped | SQL Server 2022 through Testcontainers; persistent Core deployments and copied-Sandbox adoption |
| HSQLDB | Supported | Not currently shipped | HSQLDB 2.7.4; embedded persistent Core deployments and restart tests |
| H2 | Supported | Supported | H2 2.4.x; tests, demos and lightweight/disposable development |

Support means that the artifact ships dialect-specific Flyway migrations and automated integration
coverage for the listed module. SQL Server support applies to Core only; do not configure the Search
module against SQL Server until it publishes its own SQL Server migrations and integration evidence.
Java Analysis and Architecture do not yet ship module-owned migrations for any database.

Migration SQL is dialect-specific. Never run H2, HSQLDB, PostgreSQL or SQL Server resources against a
different database.

Production sequence:

1. back up and identify the current schema state;
2. apply the packaged Flyway migrations;
3. start Hibernate with `hibernate.hbm2ddl.auto=validate`;
4. run an application-level smoke test;
5. retain the Flyway history and migration logs with the deployment record.

`update` and `create-drop` are not production schema-management mechanisms:

- use `create-drop` for isolated tests and disposable databases;
- `update` may be useful for a disposable local database, but its changes are not a versioned
  deployment contract;
- use packaged migrations plus `validate` for persistent development, staging and production.

## Anonymous public Maven repository

Configure the static public release repository:

```xml
<repositories>
  <repository>
    <id>jgit-storage-hibernate-public</id>
    <url>https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/maven-repository/</url>
    <releases><enabled>true</enabled></releases>
    <snapshots><enabled>false</enabled></snapshots>
  </repository>
</repositories>
```

No GitHub username, token or Maven `settings.xml` server entry is required. Development snapshots may
still be published to GitHub Packages, but released versions use the anonymous repository above.

## Dependencies

Core only:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-core</artifactId>
  <version>0.1.14</version>
</dependency>
```

Optional generic history search:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-search</artifactId>
  <version>0.1.14</version>
</dependency>
```

## Schema ownership

| Module | Owned tables | Flyway history table |
|---|---|---|
| Core | `git_packs`, `git_pack_chunks`, `git_repository_lock`, `git_reflog` | `jgit_storage_hibernate_core_schema_history` |
| Search | `git_commit_index` | `jgit_storage_hibernate_search_schema_history` |

`git_packs` stores publication metadata and compatibility inline payloads. Adaptive storage keeps
small pack extensions inline and stores sufficiently large payloads as ordered 1 MiB rows in
`git_pack_chunks`. `git_repository_lock` coordinates ref updates, pack publication, repository
deletion and pack cleanup across independent `SessionFactory` instances.

Application workflow, session, audit, outbox and domain-projection tables are outside these migration
locations. The consuming application owns and migrates them even when all entities share one
`SessionFactory` and database schema.

## Packaged migration locations

| Artifact | Database | Classpath location |
|---|---|---|
| Core | H2 | `classpath:db/migration/jgit-storage-hibernate/core/h2` |
| Core | HSQLDB | `classpath:db/migration/jgit-storage-hibernate/core/hsqldb` |
| Core | PostgreSQL | `classpath:db/migration/jgit-storage-hibernate/core/postgresql` |
| Core | Microsoft SQL Server | `classpath:db/migration/jgit-storage-hibernate/core/sqlserver` |
| Search | H2 | `classpath:db/migration/jgit-storage-hibernate/search/h2` |
| Search | PostgreSQL | `classpath:db/migration/jgit-storage-hibernate/search/postgresql` |

The public constants in `CoreSchemaMigrations` and `SearchSchemaMigrations` avoid copying these
strings or history-table names into consumer code.

Flyway is a deployment concern and is intentionally not a runtime dependency of the published storage
artifacts. Add `flyway-core` and the matching database module to the application, migration module or
deployment tool:

```xml
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-core</artifactId>
  <version>13.0.0</version>
</dependency>
```

Choose exactly one matching database module at the same version:

```text
PostgreSQL: org.flywaydb:flyway-database-postgresql
HSQLDB:     org.flywaydb:flyway-database-hsqldb
SQL Server: org.flywaydb:flyway-sqlserver
```

The consuming application also supplies the corresponding JDBC driver.

## Provisioning runbook A: empty dedicated schema

For a genuinely empty schema, do not baseline. Apply Core normally:

```java
Flyway.configure()
    .dataSource(dataSource)
    .locations(CoreSchemaMigrations.POSTGRESQL_LOCATION)
    .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
    .load()
    .migrate();
```

Use `CoreSchemaMigrations.HSQLDB_LOCATION` or `CoreSchemaMigrations.SQL_SERVER_LOCATION` for the
corresponding database. Flyway applies every Core migration in version order. Start Hibernate with
`validate` only after migration succeeds.

The SQL Server stream uses `datetimeoffset(7)` for `Instant`, `varbinary(max)` for payloads, `bit` for
boolean state and `nvarchar` for fields mapped with `@Nationalized`. The long Reflog ref name is an
included index column because the complete nationalized key exceeds SQL Server's nonclustered-index
key limit.

## Provisioning runbook B: shared schema without JGit tables

A schema may already contain unrelated application tables while Core or Search has never been
installed. Flyway treats that schema as non-empty, so use the module's pre-migration baseline version
`0` exactly once after verifying that its owned tables and history table are absent:

```java
Flyway.configure()
    .dataSource(dataSource)
    .locations(CoreSchemaMigrations.POSTGRESQL_LOCATION)
    .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
    .baselineOnMigrate(true)
    .baselineVersion(CoreSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION)
    .baselineDescription(CoreSchemaMigrations.PRE_MIGRATION_BASELINE_DESCRIPTION)
    .load()
    .migrate();
```

After the first successful installation, remove `baselineOnMigrate(true)` from normal deployment
configuration.

Before using this runbook, assert that Core's tables and history table are absent. When Search is
used, assert separately that `git_commit_index` and the Search history table are absent. If any owned
object already exists, stop and classify the schema before proceeding.

## Provisioning runbook C: adopt an existing 0.1.4 installation

Version 0.1.4 predates published Flyway history. Its Hibernate-generated Core and optional Search
tables correspond to migration version 0.1.4. Adoption is a one-time trust decision, not a generic
repair mechanism.

Preconditions:

- take a restorable database backup or snapshot;
- verify the tables were created by unmodified 0.1.4 mappings;
- verify there is no existing module history table;
- record row counts and representative repository/ref/reflog data;
- test the procedure against a restored production-like copy first.

Core adoption:

```java
Flyway.configure()
    .dataSource(dataSource)
    .locations(CoreSchemaMigrations.POSTGRESQL_LOCATION)
    .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
    .baselineOnMigrate(true)
    .baselineVersion(CoreSchemaMigrations.LEGACY_SCHEMA_VERSION)
    .baselineDescription(CoreSchemaMigrations.LEGACY_BASELINE_DESCRIPTION)
    .load()
    .migrate();
```

Select the matching Core location for HSQLDB or SQL Server. For Search, repeat the operation only on a
supported Search database using `SearchSchemaMigrations` and its separate history table. Disable
`baselineOnMigrate` after the one-time adoption.

Do not baseline an unknown, partially created or manually modified schema. Baselining records that the
existing structure is trusted; it does not verify or repair it. Existing inline BLOBs are not
rewritten to chunks during migration and remain readable.

## Provisioning runbook D: adopt the copied pre-library Sandbox/Taxonomy schema

The copied schema predates the Core contract and requires a dedicated read-only preflight plus the
matching legacy-adoption stream. Use one of:

```java
CoreSchemaMigrations.HSQLDB_LEGACY_ADOPTION_LOCATION
CoreSchemaMigrations.POSTGRESQL_LEGACY_ADOPTION_LOCATION
CoreSchemaMigrations.SQL_SERVER_LEGACY_ADOPTION_LOCATION
```

The procedure, checksum evidence, SQL Server temporal normalization and rollback boundary are
documented in [Pre-library Core adoption](taxonomy-adoption.md). Do not point Hibernate with
`hbm2ddl.auto=update` at the legacy database as a substitute for that process.

## Hibernate startup

Set:

```properties
hibernate.hbm2ddl.auto=validate
```

`HibernateSessionFactoryProvider` registers the Core entities and can register additional application
or supported projection entities:

```java
Properties properties = new Properties();
properties.put("hibernate.connection.url", jdbcUrl);
properties.put("hibernate.connection.username", username);
properties.put("hibernate.connection.password", password);
properties.put("hibernate.connection.driver_class", "org.postgresql.Driver");
properties.put("hibernate.dialect", "org.hibernate.dialect.PostgreSQLDialect");
properties.put("hibernate.hbm2ddl.auto", "validate");

try (HibernateSessionFactoryProvider provider =
    new HibernateSessionFactoryProvider(properties, List.of(MyApplicationEntity.class))) {
  HibernateRepositoryFactory factory =
      new DefaultHibernateRepositoryFactory(provider.getSessionFactory());
  try (HibernateGitStorage storage = factory.open(new RepositoryName("demo"))) {
    Repository repository = storage.repository();
  }
}
```

For SQL Server, use the Microsoft JDBC driver and `org.hibernate.dialect.SQLServerDialect`.
Framework-managed applications may construct the persistence context themselves and pass the native
Hibernate `SessionFactory` to `DefaultHibernateRepositoryFactory`.

## Chunked payload and temporary-disk operation

Pack extensions are first written to a temporary file while JGit performs random reads and writes.
Small extensions may then be stored inline; sufficiently large extensions are persisted as bounded
chunks. The JVM does not need a complete large pack-related file in one byte array, but the host must
provide enough temporary-disk space for all concurrent writers.

Each uncommitted extension has a UUID writer token and renewable lease. A writer verifies ownership on
flush and close. Pack flush, publication, ref publication, repository deletion and cleanup share the
same repository-scoped pessimistic lock.

The optional capacity profile exercises 1 MiB, 16 MiB and 128 MiB payloads:

```bash
mvn -B -pl jgit-storage-hibernate-core -Ppack-capacity verify
```

See [Pack capacity and recovery](operations/capacity-and-recovery.md) for the memory/disk envelope and
monitoring requirements.

## Recovering abandoned writes

A crash can leave invisible uncommitted rows. Clean them through the public service rather than direct
SQL:

```java
PackCleanupResult result =
    new PackStorageMaintenance(sessionFactory)
        .deleteExpiredUncommittedPacks(
            new RepositoryName("demo"),
            Instant.now().minus(Duration.ofHours(24)),
            Instant.now());
```

The service deletes a pack name only if every persisted extension is old, uncommitted and lacks a
current lease. Published, recent or partly active groups are skipped.

## Search setup

Apply Core migrations first, then Search migrations. Register Search entities only on a database for
which Search publishes migrations:

```java
List<Class<?>> annotatedClasses = new ArrayList<>();
annotatedClasses.addAll(SearchEntities.annotatedClasses());
annotatedClasses.add(MyApplicationEntity.class);
```

Search is derived state. Back up Core/Git data as authoritative state and maintain a repeatable reindex
procedure. SQL Server Core support does not imply SQL Server Search support.

## Upgrade policy across multiple versions

Flyway applies every pending migration in version order. A consumer may cross several artifact
versions in one deployment only when all intermediate migrations are present and release notes do not
require an application-level intermediate step.

Recommended procedure:

1. read every intervening release note;
2. restore a production backup into staging;
3. run Flyway validation and migration, then Hibernate schema validation;
4. verify repository refs, commit traversal, reflogs and relevant projections;
5. deploy the application version built against the target artifact;
6. keep the backup until the deployment acceptance window closes.

Never delete an intermediate migration file to make an upgrade appear shorter.

## Failure, checksum and rollback handling

If migration fails:

1. stop application startup;
2. retain Flyway output and database logs;
3. determine whether the database transaction rolled back every statement;
4. restore from backup when state is uncertain;
5. fix the migration in a new version rather than editing an already published successful migration.

A checksum mismatch means that the classpath resource differs from the migration recorded in the
database. Verify the deployed artifact and database history before taking action. Do not run
`flyway repair` merely to suppress the error.

Published migrations are forward-only. The default recovery for an unsafe rollback is restore from a
known backup followed by redeployment of the previous application and artifact versions.

## Schemas, catalogs and tenants

Set the JDBC current schema/catalog, Hibernate default schema and Flyway schema consistently. Do not
let Hibernate validate one schema while Flyway migrated another. Each independently managed tenant
schema needs its own Core and optional Search history tables.

Database roles should separate migration privileges from runtime privileges where practical. The
runtime role normally needs DML access; the deployment role owns DDL migration.

## Backup and observability checklist

Before migration:

- confirm a restorable backup and record its identifier;
- capture the current application/library version and Flyway history;
- record Core row counts and ordered checksums for authoritative inline BLOBs;
- ensure no concurrent application instance can write during an incompatible migration;
- confirm temporary-disk capacity for concurrent pack writers.

After migration:

- archive Flyway output and deployed artifact checksums;
- verify Hibernate starts with `validate`;
- open a repository and traverse its main ref/history;
- read recent reflog entries;
- verify large new payloads can use `git_pack_chunks` while legacy inline rows remain readable;
- verify Search or schedule reindexing when Search is enabled;
- compare recorded row counts and domain-specific smoke-test results.

Operational monitoring should include temporary-disk free space, counts and bytes of uncommitted rows,
expired writer leases, chunk-row growth and transaction latency during repack and cleanup.

## Running integration tests locally

With Docker available, the normal build starts PostgreSQL 17.10 and SQL Server 2022 through
Testcontainers:

```bash
mvn verify
```

The Core and Search suites exercise:

- fresh installation on H2, PostgreSQL and SQL Server where supported;
- Core installation and restart behavior on HSQLDB;
- adoption of immutable 0.1.4 fixtures;
- copied pre-library adoption on HSQLDB, PostgreSQL and SQL Server;
- Flyway history versions and Hibernate `validate`;
- adaptive inline and chunked repository storage;
- refs, reflogs and legacy inline compatibility;
- leased abandoned-write cleanup and repository deletion;
- Search projection persistence on supported Search databases;
- `SessionFactory` restart.

When Docker is unavailable, Testcontainers disables the PostgreSQL and SQL Server classes; H2 and
HSQLDB tests still run. CI runners provide Docker and execute the real-database paths on every pull
request.

## Recommended integration in audio-analyzer

For the first integration, depend on Core and keep `VersionedWorkflowStore` as the domain boundary:

```text
audio-analyzer
  -> migrate the Core schema before Hibernate starts
  -> create one application-managed persistence context with validate
  -> register JGit storage plus workflow entities
  -> open the repository through DefaultHibernateRepositoryFactory
  -> serialize workflow.dsl / workflow.id
  -> commit through public JGit APIs
  -> close and rebuild the persistence context
  -> reopen the repository and read the workflow back
```

Add Search only for generic Git-history indexing on a supported Search database. Audio Analyzer should
contribute only workflow-specific projection fields that are not already supplied by the generic
commit/path/message/full-text projection.
