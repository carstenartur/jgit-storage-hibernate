# Oracle deployment

Oracle support starts with **0.12.0** for Core, Search and Security. The qualified
database is **Oracle Free 23.26.3**, tested with
`gvenzl/oracle-free:23.26.3-slim-faststart`, the `AL32UTF8` database character set
and `AL16UTF16` national character set. The initial migrations use native SQL
`BOOLEAN`, so this contract does not include Oracle 19c or 21c. Other Oracle
versions, editions and character-set configurations require their own validation.

Microsoft SQL Server remains an officially supported backend; its existing
migrations and tests continue to run alongside Oracle. The complete matrix is in
the [consumer guide](../consuming.md#supported-databases-and-operating-model).

## Dependencies and connection

Use the matching released Core, Search and Security artifacts, normally aligned
through the project BOM. The application or migration tool supplies its JDBC
driver and Flyway adapter. The tested Oracle dependencies are:

```xml
<dependency>
  <groupId>com.oracle.database.jdbc</groupId>
  <artifactId>ojdbc11</artifactId>
  <version>23.26.4.0.0</version>
</dependency>
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-core</artifactId>
  <version>13.11.1</version>
</dependency>
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-database-oracle</artifactId>
  <version>13.11.1</version>
</dependency>
```

The JDBC driver class is `oracle.jdbc.OracleDriver`; a service-name URL has the
form `jdbc:oracle:thin:@//database-host:1521/service-name`. Use an application-owned
schema in the intended pluggable database, with table/index/identity creation
privileges and an adequate tablespace quota. Schema provisioning and credentials
remain deployment responsibilities; the library does not create users or grants.

## New schema installation

Each module starts its Oracle migration history at **0.12.0**:

| Module | Location constant | History table |
|---|---|---|
| Core | `CoreSchemaMigrations.ORACLE_LOCATION` | `jgit_storage_hibernate_core_schema_history` |
| Search | `SearchSchemaMigrations.ORACLE_LOCATION` | `jgit_storage_hibernate_search_schema_history` |
| Security | `SecuritySchemaMigrations.ORACLE_LOCATION` | `jgit_storage_hibernate_security_schema_history` |

Apply Core first. For an empty dedicated application schema:

```java
Flyway.configure()
    .dataSource(dataSource)
    .locations(CoreSchemaMigrations.ORACLE_LOCATION)
    .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
    .load()
    .migrate();
```

When Search or Security is installed after Core, the schema is already nonempty.
After confirming that the module's owned tables and history table do not exist,
use its separate history table with the pre-migration baseline `0`:

```java
Flyway.configure()
    .dataSource(dataSource)
    .locations(SearchSchemaMigrations.ORACLE_LOCATION)
    .table(SearchSchemaMigrations.SCHEMA_HISTORY_TABLE)
    .baselineOnMigrate(true)
    .baselineVersion(SearchSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION)
    .baselineDescription(SearchSchemaMigrations.PRE_MIGRATION_BASELINE_DESCRIPTION)
    .load()
    .migrate();
```

Use the corresponding `SecuritySchemaMigrations` constants for Security. The
[shared-schema runbook](../consuming.md#provisioning-runbook-b-shared-schema-without-jgit-tables)
also applies when unrelated application tables precede the first Core install.
Disable automatic baselining after the one-time installation. Run Flyway
validation on subsequent deployments and start Hibernate with
`hibernate.hbm2ddl.auto=validate`.

There is **no Oracle adoption stream for 0.1.4 or for copied pre-library
Sandbox/Taxonomy tables**. Do not baseline unknown existing Oracle tables as
0.12.0: that would record an unverified schema as already migrated. For a system
already holding repositories in a different schema, provision a fresh supported
schema and rehearse a verified repository transfer with backups and application
writers stopped. Search projections can be rebuilt from authoritative Git data;
Security policy and credentials require a separate, deliberate migration.

## Hibernate and Spring Boot

`HibernateSessionFactoryProvider` selects the library's `JgitOracleDialect` for
Oracle, including the usual explicit `org.hibernate.dialect.OracleDialect`
setting. The dialect preserves Oracle's native behavior and fixes nationalized
text capacity at the qualified `AL16UTF16` limit. Long Search text is stored as
`NCLOB`, including the 2048-character short-message field. Existing database
dialects and custom application dialects remain application-controlled. An explicitly
supplied dialect instance also remains unchanged, including its database version
and server settings; use a `JgitOracleDialect` instance for that configuration style.

When building a Hibernate `SessionFactory` outside this provider, select:

```properties
hibernate.dialect=io.github.carstenartur.jgit.storage.hibernate.config.JgitOracleDialect
hibernate.hbm2ddl.auto=validate
```

Register the module entities through `CoreEntities`, and, when enabled,
`SearchEntities` and `SecurityEntities`. Use `validate` only after their respective
Flyway streams have completed.

The Spring Boot starter recognizes Oracle from JDBC metadata and supplies the
Flyway Oracle adapter from 0.12.0. Supply the JDBC driver and application
`DataSource`, then use the existing settings:

```properties
jgit.storage.hibernate.schema-action=migrate
jgit.storage.hibernate.search.enabled=true
jgit.storage.hibernate.search.directory=/persistent/search-indexes
```

The starter applies Core and optional Search migrations before Hibernate
validation. Security migrations remain managed by the application or deployment
tool. For externally managed migrations, apply and validate all required streams
before startup and use `jgit.storage.hibernate.schema-action=none`; Hibernate
validation still runs.

## Oracle-specific behavior and verification

- Oracle treats empty scalar strings as SQL `NULL`. Durable reflog replay accounts
  for this database behavior while databases that distinguish null and empty
  strings keep their existing semantics.
- Core pack data uses `BLOB`; Search's long nationalized fields use `NCLOB`.
  The integration tests include complete reads and query matches beyond ordinary
  scalar-column limits.
- Security's managed-policy unique indexes exclude unmanaged rows with
  function-based keys. Multiple unrelated unmanaged grants and ref rules can
  coexist for one repository; duplicate managed ownership keys remain rejected.
- Oracle's schema is not a promise that every consuming application exposes
  Oracle. A consumer must use a suitable library version and wire and test its
  own migrations, Hibernate configuration and application features.
- The published standalone server image and supplied Compose deployment retain
  their PostgreSQL contract. Oracle support here applies to library integrations.

The normal `mvn verify` runs the Oracle integration suites when Docker is
available. CI and release preparation/publication additionally require successful,
non-skipped Oracle and SQL Server reports and retain the XML reports as artifacts.
A local run without Docker does not establish Oracle or SQL Server qualification.
See [quality gates](api-compatibility-and-quality-gates.md#local-reproduction).
