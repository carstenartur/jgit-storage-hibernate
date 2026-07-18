# Consuming jgit-storage-hibernate

This project publishes a parent POM plus two consumer-facing Maven artifacts:

```text
io.github.carstenartur:jgit-storage-hibernate-core
io.github.carstenartur:jgit-storage-hibernate-search
```

Use `jgit-storage-hibernate-core` when an application only needs database-backed JGit repositories. Add `jgit-storage-hibernate-search` only when the application also needs generic Hibernate Search/Lucene history indexing.

The versioned database schema contract starts with the `0.1.5` line. It uses Hibernate ORM `7.4.5.Final` and Hibernate Search `8.4.0.Final`. Consumers should keep those versions aligned through the published artifacts instead of overriding only one side of the pair.

## GitHub Packages repository

Until the project is published to Maven Central, configure GitHub Packages as a Maven repository.

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/carstenartur/jgit-storage-hibernate</url>
  </repository>
</repositories>
```

For private package access, add credentials to `~/.m2/settings.xml`. Public packages may still require GitHub authentication depending on account/package settings.

```xml
<settings>
  <servers>
    <server>
      <id>github</id>
      <username>YOUR_GITHUB_USERNAME</username>
      <password>YOUR_GITHUB_TOKEN</password>
    </server>
  </servers>
</settings>
```

The token needs at least `read:packages` for consuming and `write:packages` for publishing.

## Core dependency

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-core</artifactId>
  <version>0.1.5</version>
</dependency>
```

## Optional search dependency

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-search</artifactId>
  <version>0.1.5</version>
</dependency>
```

## Schema management policy

The core and search artifacts package Flyway-compatible SQL migrations. Production applications must apply these migrations before constructing the Hibernate `SessionFactory`, and must start Hibernate with:

```properties
hibernate.hbm2ddl.auto=validate
```

`update` and `create-drop` are intentionally not production schema-management mechanisms:

- use `create-drop` for isolated tests or other disposable databases;
- `update` may be useful for a disposable local development database, but its changes are not a versioned deployment contract;
- use the packaged migrations plus `validate` for persistent development, staging and production databases.

The migration resources are split by module and database dialect:

| Artifact | Database | Classpath location | Flyway history table |
|---|---|---|---|
| Core | H2 2.4.x | `classpath:db/migration/jgit-storage-hibernate/core/h2` | `jgit_storage_hibernate_core_schema_history` |
| Core | PostgreSQL 17 | `classpath:db/migration/jgit-storage-hibernate/core/postgresql` | `jgit_storage_hibernate_core_schema_history` |
| Search | H2 2.4.x | `classpath:db/migration/jgit-storage-hibernate/search/h2` | `jgit_storage_hibernate_search_schema_history` |
| Search | PostgreSQL 17 | `classpath:db/migration/jgit-storage-hibernate/search/postgresql` | `jgit_storage_hibernate_search_schema_history` |

H2 is supported for tests, demos and lightweight development. PostgreSQL is the production-oriented integration-test target. The SQL variants are deliberately explicit; do not run the H2 resources against PostgreSQL or the PostgreSQL resources against H2.

Core and search use separate Flyway history tables because both modules publish migrations with artifact-aligned versions such as `0.1.4` and `0.1.5`. Apply the core migrations first, then the optional search migrations.

The public constants in `CoreSchemaMigrations` and `SearchSchemaMigrations` avoid copying location and history-table strings into application code.

## Applying migrations with Flyway

Flyway is a deployment concern and is therefore not a runtime dependency of the storage artifacts. Add Flyway to the application, migration module or deployment tool that provisions the database. PostgreSQL users also need Flyway's PostgreSQL database module.

```xml
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-core</artifactId>
  <version>12.8.1</version>
</dependency>
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-database-postgresql</artifactId>
  <version>12.8.1</version>
</dependency>
```

A core-only application can migrate with:

```java
String location = postgresql
    ? CoreSchemaMigrations.POSTGRESQL_LOCATION
    : CoreSchemaMigrations.H2_LOCATION;

Flyway.configure()
    .dataSource(dataSource)
    .locations(location)
    .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
    .baselineOnMigrate(true)
    .baselineVersion(CoreSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION)
    .baselineDescription(CoreSchemaMigrations.PRE_MIGRATION_BASELINE_DESCRIPTION)
    .load()
    .migrate();
```

An application using generic search projections runs a second Flyway instance after the core migration:

```java
String searchLocation = postgresql
    ? SearchSchemaMigrations.POSTGRESQL_LOCATION
    : SearchSchemaMigrations.H2_LOCATION;

Flyway.configure()
    .dataSource(dataSource)
    .locations(searchLocation)
    .table(SearchSchemaMigrations.SCHEMA_HISTORY_TABLE)
    .baselineOnMigrate(true)
    .baselineVersion(SearchSchemaMigrations.PRE_MIGRATION_BASELINE_VERSION)
    .baselineDescription(SearchSchemaMigrations.PRE_MIGRATION_BASELINE_DESCRIPTION)
    .load()
    .migrate();
```

The migration classpath must contain the corresponding core or search artifact. A command-line or build-plugin Flyway invocation can use the same locations and history-table names.

The pre-migration baseline version `0` allows a module to be installed into a schema that already contains only unrelated application tables, or allows Search to be installed after Core. Flyway does not create that baseline in a completely empty schema. Before using it, verify that the tables owned by the module (`git_packs` / `git_reflog` for Core, `git_commit_index` for Search) are absent. Existing 0.1.4 module tables require the legacy baseline described below instead.

## Upgrading an existing 0.1.4 database

Version `0.1.4` predates published migration history. Its Hibernate-generated core and optional search tables correspond to migration version `0.1.4`. For an existing database, first verify that the tables were created by unmodified `0.1.4` mappings and that application-specific tables are managed separately. Then run a one-time baseline-and-migrate operation for each installed module:

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

For search, use the equivalent constants from `SearchSchemaMigrations` and its separate history table. Disable `baselineOnMigrate` after this one-time adoption. Do not blindly baseline an unknown or partially created schema: baselining records that the existing structure is already the trusted `0.1.4` baseline.

The `0.1.5` migration is non-destructive and establishes the first published, artifact-owned schema version. Integration tests exercise both empty-database migration and adoption of a populated `0.1.4` schema on H2 and PostgreSQL, followed by Hibernate `validate` and a persistence-context restart.

## Minimal repository setup

`HibernateSessionFactoryProvider` is a standalone convenience bootstrap. It always registers the core pack/reflog entities and can register additional application entities in the same Hibernate persistence context. The database must already be migrated when `validate` is used.

```java
Properties properties = new Properties();
properties.put("hibernate.connection.url", "jdbc:h2:mem:demo;DB_CLOSE_DELAY=-1");
properties.put("hibernate.connection.driver_class", "org.h2.Driver");
properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
properties.put("hibernate.hbm2ddl.auto", "validate");

try (HibernateSessionFactoryProvider provider =
    new HibernateSessionFactoryProvider(properties, List.of(MyApplicationEntity.class))) {
  SessionFactory sessionFactory = provider.getSessionFactory();
  HibernateRepositoryFactory factory =
      new DefaultHibernateRepositoryFactory(sessionFactory);

  try (HibernateGitStorage storage = factory.open(new RepositoryName("demo"))) {
    Repository repository = storage.repository();
    // Use standard public JGit APIs here.
  }

  // MyApplicationEntity is persisted through the same SessionFactory and transaction manager.
}
```

Framework-managed applications may construct the `SessionFactory` / Jakarta `EntityManagerFactory` themselves and pass the native Hibernate `SessionFactory` directly to `DefaultHibernateRepositoryFactory`. Register the public core entity package together with application mappings, use one `DataSource`, and let the application own transaction and lifecycle management. The repository facade never requires downstream code to import `org.eclipse.jgit.internal.*`.

Application-specific workflow, session, audit or outbox tables are outside this project's migration locations. A consuming application must own and migrate those tables independently, even when all entities share one `SessionFactory` and database schema.

## Search setup

Search projections require the additional search entities when creating the `SessionFactory`.

```java
List<Class<?>> annotatedClasses = new ArrayList<>();
annotatedClasses.addAll(SearchEntities.annotatedClasses());
annotatedClasses.add(MyApplicationEntity.class);

try (HibernateSessionFactoryProvider provider =
    new HibernateSessionFactoryProvider(properties, annotatedClasses)) {
  // Open repositories and use CommitIndexer / GitHistorySearchService.
  // Application-specific projections can share this persistence context.
}
```

The search index is derived state. Git objects and application audit entities remain authoritative, and indexes must be rebuildable after deletion or corruption.

## Recommended integration in audio-analyzer

For the first `audio-analyzer` integration, depend on `jgit-storage-hibernate-core` and keep the existing `VersionedWorkflowStore` facade as the domain boundary.

```text
audio-analyzer
  -> migrate the core schema before Hibernate starts
  -> create one application-managed Hibernate persistence context with validate
  -> register JGit storage plus Audio Analyzer workflow entities
  -> open the repository through DefaultHibernateRepositoryFactory
  -> serialize one minimal workflow to workflow.dsl / workflow.id
  -> commit it through standard public JGit APIs
  -> close and rebuild the persistence context
  -> reopen the repository and read the workflow back
```

Add `jgit-storage-hibernate-search` only for generic Git-history indexing. Audio Analyzer should contribute only workflow-specific projection fields that are not already supplied by the generic commit/path/message/full-text index.
