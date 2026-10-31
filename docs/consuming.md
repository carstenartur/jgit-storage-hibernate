# Consuming jgit-storage-hibernate

This guide covers the storage-facing artifacts:

```text
io.github.carstenartur:jgit-storage-hibernate-core
io.github.carstenartur:jgit-storage-hibernate-security
io.github.carstenartur:jgit-storage-hibernate-search
```

Core provides database-backed JGit repositories. Security is optional and adds a framework-neutral principal/group ACL schema plus deterministic repository/ref decisions. Search is optional and adds generic relational and Hibernate Search/Lucene projections. The higher-level `java-analysis` and `architecture` modules build on this foundation, but their module-owned persistence remains incubating; consult their module guides before registering those entities.

The documented release line is **0.11.8**. It uses Java 21, JGit 7.8.0.202609011348-r, Hibernate ORM 7.4.12.Final and Hibernate Search 8.4.0.Final. Keep those versions aligned through the published BOM and tested deployment stack instead of overriding only one side of the stack.

## Supported databases and operating model

This is the canonical support matrix for the library artifacts in the documented release line. The versions below describe the tested release stack; development-branch dependency updates can exercise newer versions.

| Database | Core | Search | Security | Tested release version and intended use |
|---|---|---|---|---|
| PostgreSQL | Supported | Supported | Supported | PostgreSQL 17.10 through Testcontainers; persistent development, staging and production |
| Microsoft SQL Server (MSSQL) | Supported | Supported from 0.1.16 | Supported | SQL Server 2022 through Testcontainers; persistent deployments, copied-Sandbox Core adoption and Search rebuild cut-over |
| HSQLDB | Supported | Supported from 0.11.6; new Search installation | Supported | HSQLDB 2.7.4; embedded persistent deployments, file-database and Lucene restart tests; see the Spring Boot limitation below |
| H2 | Supported | Supported | Supported | H2 2.4.x; tests, demos and lightweight/disposable development |
| Oracle | Not supported | Not supported | Not supported | No module-owned migration streams or Oracle database integration tests |

Support means that the artifact ships dialect-specific Flyway migrations and automated integration coverage for the listed module. H2 and HSQLDB use embedded database tests; PostgreSQL and SQL Server use real databases through Testcontainers. The public `CoreSchemaMigrations`, `SearchSchemaMigrations` and `SecuritySchemaMigrations` constants identify the [packaged migration locations](#packaged-migration-locations). A Hibernate dialect or an Oracle-related mapping portability check alone does not establish backend support.

### Integration boundaries

- **Spring Boot starter:** automatic Core schema management recognizes H2, HSQLDB, PostgreSQL and SQL Server. Automatic Search schema management supports H2, PostgreSQL and SQL Server, but currently rejects HSQLDB for both `migrate` and `validate`. This is a starter integration limitation even though the Search module ships HSQLDB migrations and tests. For an externally managed HSQLDB Search schema, apply and validate the matching Core and Search migrations before startup, then set `jgit.storage.hibernate.schema-action=none`; Hibernate schema validation still runs. Follow the [HSQLDB Search installation procedure](../jgit-storage-hibernate-search/README.md#hsqldb-installation), including the separate history table and baseline version `0`. Security migrations remain separately managed by the application or deployment tool.
- **Published standalone server image:** the supplied image and Compose deployment use PostgreSQL. Other library backends are not supplied as ready-configured server-image options; see the [server guide](../jgit-storage-hibernate-server/README.md).
- **Smart HTTP:** owns no database schema and uses the configured Core and optional Security capabilities.
- **Java Analysis and Architecture:** their analysis/evaluation APIs are supported, but module-owned persistence remains incubating for every database.

### Schema operating model

Migration SQL is dialect-specific. Never run H2, HSQLDB, PostgreSQL or SQL Server resources against a different database.

Production sequence:

1. back up and identify the current schema state;
2. apply the packaged Flyway migrations;
3. start Hibernate with `hibernate.hbm2ddl.auto=validate`;
4. run an application-level smoke test;
5. retain the Flyway history and migration logs with the deployment record.

`update` and `create-drop` are not production schema-management mechanisms:

- use `create-drop` for isolated tests and disposable databases;
- `update` may be useful for a disposable local database, but its changes are not a versioned deployment contract;
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

No GitHub username, token or Maven `settings.xml` server entry is required. Development snapshots may still be published to GitHub Packages, but released versions use the anonymous repository above.

## Dependencies

Core only:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-core</artifactId>
  <version>0.11.8</version>
</dependency>
```

Optional generic history search:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-search</artifactId>
  <version>0.11.8</version>
</dependency>
```

Check the [database support matrix](#supported-databases-and-operating-model) when selecting Search or a framework integration.

Optional database-backed security policy:

Security was introduced in `0.11.0` and remains available in the documented release line. It remains optional: consumers add it only when they need stable principals, groups, repository/ref policy, credentials or audit.

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-security</artifactId>
  <version>0.11.8</version>
</dependency>
```

The module supplies explicit access contexts, Git-generic permissions, deterministic evaluation, migrations, principal-bound direct-JGit enforcement, credentials/tokens and durable audit while Core-only consumers remain unchanged.

## Schema ownership

| Module | Owned tables | Flyway history table |
|---|---|---|
| Core | `git_packs`, `git_pack_chunks`, `git_repository_lock`, `git_reflog` | `jgit_storage_hibernate_core_schema_history` |
| Security | principals, groups, memberships, repository grants, ref rules and monotonic security versions | `jgit_storage_hibernate_security_schema_history` |
| Search | `git_commit_index` | `jgit_storage_hibernate_search_schema_history` |

`git_packs` stores committed publication metadata, compatibility inline payloads and lease-owned durable unpublished state. Small pack extensions may remain inline; sufficiently large extensions use ordered 1 MiB rows in `git_pack_chunks`. `git_repository_lock` coordinates ref updates, logical-pack visibility, direct pack replacement, repository deletion and lease-aware cleanup across independent `SessionFactory` instances.

Application workflow, session, audit, outbox and domain-projection tables are outside these migration locations. The consuming application owns and migrates them even when all entities share one `SessionFactory` and database schema.

## Packaged migration locations

| Artifact | Database | Classpath location |
|---|---|---|
| Core | H2 | `classpath:db/migration/jgit-storage-hibernate/core/h2` |
| Core | HSQLDB | `classpath:db/migration/jgit-storage-hibernate/core/hsqldb` |
| Core | PostgreSQL | `classpath:db/migration/jgit-storage-hibernate/core/postgresql` |
| Core | Microsoft SQL Server | `classpath:db/migration/jgit-storage-hibernate/core/sqlserver` |
| Search | H2 | `classpath:db/migration/jgit-storage-hibernate/search/h2` |
| Search | HSQLDB | `classpath:db/migration/jgit-storage-hibernate/search/hsqldb` |
| Search | PostgreSQL | `classpath:db/migration/jgit-storage-hibernate/search/postgresql` |
| Search | Microsoft SQL Server | `classpath:db/migration/jgit-storage-hibernate/search/sqlserver` |
| Security | H2 | `classpath:db/migration/jgit-storage-hibernate/security/h2` |
| Security | HSQLDB | `classpath:db/migration/jgit-storage-hibernate/security/hsqldb` |
| Security | PostgreSQL | `classpath:db/migration/jgit-storage-hibernate/security/postgresql` |
| Security | Microsoft SQL Server | `classpath:db/migration/jgit-storage-hibernate/security/sqlserver` |

The public constants in `CoreSchemaMigrations`, `SecuritySchemaMigrations` and `SearchSchemaMigrations` avoid copying these strings or history-table names into consumer code.

Flyway is a deployment concern and is intentionally not a runtime dependency of the published storage artifacts. Add `flyway-core` and the matching database module to the application, migration module or deployment tool:

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

Use `CoreSchemaMigrations.HSQLDB_LOCATION` or `CoreSchemaMigrations.SQL_SERVER_LOCATION` for the corresponding database. Flyway applies every Core migration in version order. Start Hibernate with `validate` only after migration succeeds.

When Search is enabled, apply its matching location after Core:

```java
Flyway.configure()
    .dataSource(dataSource)
    .locations(SearchSchemaMigrations.SQL_SERVER_LOCATION)
    .table(SearchSchemaMigrations.SCHEMA_HISTORY_TABLE)
    .load()
    .migrate();
```

The SQL Server streams use `datetimeoffset(7)` for `Instant`, `varbinary(max)` for Core payloads, `bit` for boolean state and `nvarchar` for fields mapped with `@Nationalized`. Index definitions keep SQL Server key widths within platform limits.

## Provisioning runbook B: shared schema without JGit tables

A schema may already contain unrelated application tables while Core or Search has never been installed. Flyway treats that schema as non-empty, so use the module's pre-migration baseline version `0` exactly once after verifying that its owned tables and history table are absent:

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

After the first successful installation, remove `baselineOnMigrate(true)` from normal deployment configuration.

Before using this runbook, assert that Core's tables and history table are absent. When Search is used, assert separately that `git_commit_index` and the Search history table are absent. If any owned object already exists, stop and classify the schema before proceeding.

## Provisioning runbook C: adopt an existing 0.1.4 installation

Version 0.1.4 predates published Flyway history. Its Hibernate-generated Core and optional Search tables correspond to migration version 0.1.4. Adoption is a one-time trust decision, not a generic repair mechanism.

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

Select the matching Core location for HSQLDB or SQL Server. For Search on H2, PostgreSQL or SQL Server, repeat the operation using `SearchSchemaMigrations` and its separate history table. Disable `baselineOnMigrate` after the one-time adoption.

HSQLDB Search has no 0.1.4 upgrade path. Its migration stream starts with a new installation at 0.11.6. Follow the [HSQLDB Search installation and rebuild procedure](../jgit-storage-hibernate-search/README.md#hsqldb-installation) instead; do not baseline an existing HSQLDB Search projection as 0.1.4. This limitation does not affect HSQLDB Core adoption.

Do not baseline an unknown, partially created or manually modified schema. Baselining records that the existing structure is trusted; it does not verify or repair it. Existing inline BLOBs are not rewritten to chunks during migration and remain readable.

## Provisioning runbook D: adopt the copied pre-library Sandbox/Taxonomy schema

The copied Core schema predates the library contract and requires a dedicated read-only preflight plus the matching legacy-adoption stream. Use one of:

```java
CoreSchemaMigrations.HSQLDB_LEGACY_ADOPTION_LOCATION
CoreSchemaMigrations.POSTGRESQL_LEGACY_ADOPTION_LOCATION
CoreSchemaMigrations.SQL_SERVER_LEGACY_ADOPTION_LOCATION
```

The procedure, checksum evidence, SQL Server temporal normalization and rollback boundary are documented in [Pre-library Core adoption](taxonomy-adoption.md). Do not point Hibernate with `hbm2ddl.auto=update` at the legacy database as a substitute for that process.

Copied Search rows and Lucene files are handled differently: they are derived state. Verify Core traversal, archive or remove the copied projection, apply the released Search migration and rebuild through `CommitProjectionRebuilder`. See [SQL Server Search operations](operations/sql-server-search.md).

## Hibernate startup

Set:

```properties
hibernate.hbm2ddl.auto=validate
```

`HibernateSessionFactoryProvider` registers the Core entities and can register additional application or supported projection entities:

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

For SQL Server, use the Microsoft JDBC driver and `org.hibernate.dialect.SQLServerDialect`. Framework-managed applications may construct the persistence context themselves and pass the native Hibernate `SessionFactory` to `DefaultHibernateRepositoryFactory`.

## Search setup

Apply Core migrations first, then Search migrations. Register Search entities:

```java
List<Class<?>> annotatedClasses = new ArrayList<>();
annotatedClasses.addAll(SearchEntities.annotatedClasses());
annotatedClasses.add(MyApplicationEntity.class);
```

Search is derived state. Back up Core/Git data as authoritative state and maintain a repeatable rebuild procedure.

For persistent Lucene storage:

```properties
hibernate.search.backend.type=lucene
hibernate.search.backend.directory.type=local-filesystem
hibernate.search.backend.directory.root=/srv/jgit-storage-hibernate/lucene
```

The application owns this directory. Do not let multiple uncoordinated JVMs write it. After analyzer or mapping changes, remove or archive the incompatible derived directory and rebuild.

A compound query can filter author and committer identities separately and apply deterministic structured pagination:

```java
CommitHistoryQuery query =
    CommitHistoryQuery.forRepository("demo")
        .authoredBy("alice@example.org")
        .committedBy("build@example.org")
        .touchingPath("workflow.dsl")
        .committedBetween(from, to)
        .offset(0)
        .limit(100)
        .build();
```

Register `SearchRepositoryDeletionParticipant` when repository deletion must remove Core and Search state in the same deletion transaction.

## Pack payload and temporary-disk operation

The normal Core writer builds each pack-related extension in a temporary file so JGit can perform random reads while constructing or resolving it. Closing an extension creates no database row. Completed PACK, IDX, object-size-index, bitmap and Reftable files remain JVM-local until JGit calls `commitPack()` for the logical pack.

Publication is adaptive:

- a logical pack whose extensions all remain at or below 256 KiB is persisted and made visible in one repository-locked Hibernate transaction;
- a fully local additive logical pack containing a chunked extension persists its complete parent and payload set in one lock-free transaction while every row remains `committed=false`, then atomically changes the exact token-owned set to committed under one short repository lock;
- pack replacement, compaction and mixed legacy publication retain the established single locked transaction because no ref update may cross JGit's replacement race check.

Readers query only committed rows and therefore never see a subset of newly published extensions. The final visibility update must affect exactly the expected extension count or the publication rolls back.

The lock-free pre-persistence transaction is itself all-or-nothing. A failure before it commits leaves no durable partial parent/chunk group. A hard process termination after it commits but before final publication can leave one complete invisible group with a shared writer token and renewed lease.

Temporary-disk capacity must cover both open writers and completed extensions waiting for publication. For adaptive chunked publication, capacity must also allow a temporary overlap between the local files and the complete invisible database payload group. Normal publication and rollback delete local files explicitly.

A hard JVM termination or operating-system deletion failure can leave stale files with the `jgit-storage-pack-` prefix. Neither local files nor uncommitted database rows may be imported or manually promoted as durable Git state.

Writer tokens and renewable leases are part of the normal adaptive chunked path as well as legacy/base-writer compatibility. All extensions of one prepared logical pack share a token. The existing maintenance service removes only old groups whose complete persisted extension set has no current lease.

The optional capacity profile exercises 1 MiB, 16 MiB and 128 MiB payloads:

```bash
mvn -B -pl jgit-storage-hibernate-core -Ppack-capacity verify
```

See [Pack capacity and recovery](operations/capacity-and-recovery.md) for transaction boundaries, the memory/disk envelope, crash model and monitoring requirements.

## Recovering abandoned state

### Local staging files

A hard JVM termination can leave stale `jgit-storage-pack-` files in the configured JVM temporary directory. They are unpublished derived state and have no complete association with JGit's lost in-memory `DfsPackDescription`. Remove them only after confirming that the owning process is no longer active. Do not attempt to publish or import them.

### Durable uncommitted rows

A crash can leave invisible `committed=false` rows from a completed adaptive pre-persistence transaction, an older writer or direct use of the base storage path. Readers ignore them and operators must not promote them directly. A failure during adaptive pre-persistence itself rolls back the whole transaction and leaves no durable partial group.

Clean expired groups through the public service rather than direct SQL:

```java
PackCleanupResult result =
    new PackStorageMaintenance(sessionFactory)
        .deleteExpiredUncommittedPacks(
            new RepositoryName("demo"),
            Instant.now().minus(Duration.ofHours(24)),
            Instant.now());
```

The service deletes a pack name only if every persisted extension is old, uncommitted and lacks a current lease. Published, recent or partly active groups are skipped. Parent deletion uses the database cascade for chunk rows.

## Upgrade policy across multiple versions

Flyway applies every pending migration in version order. A consumer may cross several artifact versions in one deployment only when all intermediate migrations are present and release notes do not require an application-level intermediate step.

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

A checksum mismatch means that the classpath resource differs from the migration recorded in the database. Verify the deployed artifact and database history before taking action. Do not run `flyway repair` merely to suppress the error.

Published migrations are forward-only. The default recovery for an unsafe rollback is restore from a known backup followed by redeployment of the previous application and artifact versions.

Search indexing failure is separate from Core publication failure. A failed Search update is reported and retried or rebuilt; it does not invalidate a successfully published Git commit or ref.

## Schemas, catalogs and tenants

Set the JDBC current schema/catalog, Hibernate default schema and Flyway schema consistently. Do not let Hibernate validate one schema while Flyway migrated another. Each independently managed tenant schema needs its own Core and optional Search history tables.

Database roles should separate migration privileges from runtime privileges where practical. The runtime role normally needs DML access; the deployment role owns DDL migration.

## Backup and observability checklist

Before migration:

- confirm a restorable backup and record its identifier;
- capture the current application/library version and Flyway history;
- record Core row counts and ordered checksums for authoritative inline BLOBs;
- record Search row and repository coverage when replacing an existing projection;
- ensure no concurrent application instance can write during an incompatible migration;
- confirm temporary-disk and database capacity for open, completed and adaptively pre-persisted unpublished extensions;
- record the Lucene directory and analyzer profile when Search is enabled.

After migration:

- archive Flyway output and deployed artifact checksums;
- verify Hibernate starts with `validate`;
- open a repository and traverse its main ref/history;
- read recent reflog entries;
- verify large new payloads can use `git_pack_chunks` while legacy inline rows remain readable;
- rebuild and verify Search when Search is enabled;
- close and reopen the `SessionFactory` and verify persistent Lucene queries;
- compare recorded row counts and domain-specific smoke-test results.

Operational monitoring should include temporary-disk free space and stale staging-file counts, uncommitted row counts/bytes and expired leases, chunk-row growth, lock acquisition/held time, rebuild progress and transaction latency during publication, repack and cleanup.

## Running integration tests locally

With Docker available, the normal build starts PostgreSQL 17.10 and SQL Server 2022 through Testcontainers:

```bash
mvn verify
```

The Core and Search suites exercise:

- fresh Core and Search installation on H2, HSQLDB, PostgreSQL and SQL Server;
- Core file-database restart and Search file-database/Lucene restart, rebuild, query and deletion behavior on HSQLDB;
- adoption of immutable 0.1.4 fixtures;
- copied pre-library Core adoption on HSQLDB, PostgreSQL and SQL Server;
- Flyway history versions and Hibernate `validate`;
- direct inline publication, adaptive additive chunked publication and bounded read-ahead;
- complete invisible prepared payload groups, exact final visibility counts and failure cleanup;
- direct locked replacement/compaction plus refs, reflogs and legacy compatibility;
- local, prepared, mixed and legacy rollback plus lease-aware abandoned-write cleanup;
- repository deletion;
- Search Unicode indexing, first-parent add/modify/delete and merge semantics;
- author, committer, path, time, compound and paginated Search queries;
- two-repository rebuild, interruption/retry and deletion isolation;
- projection-failure isolation from successful Git publication;
- persistent Lucene `SessionFactory` restart.

When Docker is unavailable, Testcontainers disables the PostgreSQL and SQL Server classes; H2 and HSQLDB tests still run. CI runners provide Docker and execute the real-database paths on every pull request.

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

Add Search only for generic Git-history indexing on a supported Search database. Audio Analyzer should contribute only workflow-specific projection fields that are not already supplied by the generic commit/path/message/full-text projection.
