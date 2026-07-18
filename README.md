# jgit-storage-hibernate

[![Java CI with Maven](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/maven.yml/badge.svg)](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/maven.yml)
[![Coverage](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/main/docs/badges/coverage.json)](docs/badges/coverage.json)
[![Tests](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/main/docs/badges/tests.json)](docs/badges/tests.json)
[![JMH Benchmarks](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/performance.yml/badge.svg)](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/performance.yml)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue)](pom.xml)
[![License: BSD-3-Clause](https://img.shields.io/badge/License-BSD--3--Clause-blue.svg)](LICENSE)

https://doi.org/10.5281/zenodo.21210132

## Git semantics, operated like application data

JGit provides the authoritative Git object and repository model. `jgit-storage-hibernate` adds database-native persistence, transaction-safe pack/ref publication, rebuildable search projections, semantic code history and explainable architecture governance without replacing the standard JGit `Repository` API.

Use it when Git is part of an application's domain model and repository state must live inside the same relational database, backup, deployment, access-control and lifecycle model as the rest of the application—without exposing `org.eclipse.jgit.internal.*` to consumers.

## What it adds on top of JGit

| Need | What the project adds | Practical outcome |
|---|---|---|
| Operate Git without a filesystem-backed `.git` directory | Hibernate-backed DFS/Reftable storage with transactional pack publication for packs, refs and reflogs | Readers do not observe partially published pack rows, and repository data follows normal database backup and access-control practices. |
| Search history without repeatedly walking the object graph | Rebuildable Hibernate Search/Lucene projections | Query commit messages, authors, paths and indexed text with structured and full-text search. |
| Understand code evolution beyond line diffs | Binding-aware Java analysis, semantic diff and symbol timelines | Track declarations across revisions and calculate caller, type and inheritance impact. |
| Keep architecture intent connected to implementation | Versioned rules, evidence, code mapping and drift evaluation | Produce deterministic findings that explain which rule was violated, where and why. |

Git objects remain authoritative. Search, Java-analysis and architecture tables are derived projections and must be rebuildable.

## Application use case: auditable approval workflows

A purchasing platform publishes YAML approval workflows. It needs immutable versions and branches, but it also needs the repository inside PostgreSQL, transaction-safe publication, and repeated business-history queries such as:

```text
When was dual control introduced?
Which workflow versions touched purchase-approval.yaml?
Who published the currently active rule and which commit ID is deployed?
```

Plain Git/JGit supplies commits, trees, refs, merges and revision walking. It does not itself supply a relational storage backend, database transaction boundaries for pack publication, Flyway-managed repository tables or a Hibernate Search projection for these queries.

With this project the application can:

```java
try (HibernateGitStorage storage =
    new DefaultHibernateRepositoryFactory(sessionFactory)
        .open(new RepositoryName("approval-workflows"))) {
  Repository repository = storage.repository();

  ObjectId commitId = commitWorkflow(repository, previousCommit, workflowYaml);
  publishWithExpectedOldId(repository, "refs/heads/main", previousCommit, commitId);

  new CommitIndexer(sessionFactory, "approval-workflows")
      .indexCommit(repository, commitId);

  List<GitCommitIndex> hits =
      new GitHistorySearchService(sessionFactory)
          .searchCommitText("approval-workflows", "dualcontrol", 20);
}
```

The commit helper uses ordinary public JGit APIs. The database-backed repository, transactional publication protocol and query projection are supplied by this library. See the complete [approval-workflow use case](docs/use-cases/versioned-approval-workflows.md) and its [executable integration test](jgit-storage-hibernate-search/src/test/java/io/github/carstenartur/jgit/storage/hibernate/search/VersionedApprovalWorkflowUseCaseTest.java).

## Module elevator pitches

| Module | Elevator pitch | Choose it when... |
|---|---|---|
| `jgit-storage-hibernate-core` | Use the familiar JGit repository API while storing packs, refs, reftables and reflogs in the relational database your application already operates. | You need database-backed Git semantics and no search or code-analysis layer. |
| `jgit-storage-hibernate-search` | Add rebuildable full-text and structured commit-history search instead of parsing the Git object graph for every query. | Users or services need fast history queries by message, author, path or indexed content. |
| `jgit-storage-hibernate-java-analysis` | Go beyond line diffs with binding-aware symbol history, semantic change detection and transitive impact analysis across revisions. | You need to answer what a Java change means, not only which lines changed. |
| `jgit-storage-hibernate-architecture` | Version architecture intent beside the code and compare rules and evidence with the observed software graph. | You need explainable architecture drift, constraints and decision provenance. |
| `jgit-storage-hibernate-benchmarks` | Measure storage operations through repeatable JMH workloads. | You maintain or review performance; it is not a runtime dependency. |

## Five-minute production setup

The documented release line is **0.1.5**. Java 21 is required. PostgreSQL 17 is the production-oriented tested database; H2 2.4.x is supported for tests, demos and lightweight development.

### 1. Add the core dependency

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-core</artifactId>
  <version>0.1.5</version>
</dependency>
```

Add `jgit-storage-hibernate-search` at the same version only when generic history search is needed.

### 2. Apply the packaged migration before Hibernate starts

```java
Flyway.configure()
    .dataSource(dataSource)
    .locations(CoreSchemaMigrations.POSTGRESQL_LOCATION)
    .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
    .load()
    .migrate();
```

A shared schema or an existing 0.1.4 installation requires a deliberate one-time baseline. Do not enable `baselineOnMigrate` blindly; follow the three provisioning runbooks in [docs/consuming.md](docs/consuming.md).

### 3. Make Hibernate validate, not mutate, the production schema

```properties
hibernate.hbm2ddl.auto=validate
```

`update` and `create-drop` are reserved for disposable local databases and isolated tests.

### 4. Open the repository through the public facade

```java
try (HibernateSessionFactoryProvider provider =
        new HibernateSessionFactoryProvider(properties);
    HibernateGitStorage storage =
        new DefaultHibernateRepositoryFactory(provider.getSessionFactory())
            .open(new RepositoryName("workflows"))) {
  Repository repository = storage.repository();
  // Use normal public JGit APIs.
}
```

Framework-managed applications can supply their own Hibernate `SessionFactory` and share a `DataSource`, mappings and lifecycle with application entities.

## Transaction-safe database publication

Core uses explicit Hibernate transactions rather than presenting partially written database rows as repository state:

- pack extensions are first persisted with `committed=false` and are invisible to `listPacks()` and `openFile(...)`;
- publishing all extensions of a pack and deleting replaced packs happens in one transaction and rolls back on failure;
- the Reftable ref database advertises atomic ref transactions, with Reftable files published through the same pack mechanism;
- queryable reflog appends and Search projection upserts each run in their own transaction.

This is the ACID benefit described in the original [JGit discussion #251](https://github.com/eclipse-jgit/jgit/discussions/251).

The guarantee is deliberately **per storage operation**. The current implementation does not provide one ambient transaction spanning an arbitrary application entity, Git object insertion, ref publication, reflog append and Search indexing. `ObjectInserter.flush()`, `RefUpdate.update()` and `CommitIndexer.indexCommit(...)` are separate transactional steps; Search remains retryable derived state. See the [precise transaction contract and failure behavior](docs/use-cases/versioned-approval-workflows.md#database-transaction-guarantees).

## Versioned database contract

Core and Search package Flyway-compatible SQL resources for H2 and PostgreSQL in their own artifacts. They use separate history tables so both modules can evolve independently inside one application schema.

| Artifact | H2 location | PostgreSQL location | History table |
|---|---|---|---|
| Core | `classpath:db/migration/jgit-storage-hibernate/core/h2` | `classpath:db/migration/jgit-storage-hibernate/core/postgresql` | `jgit_storage_hibernate_core_schema_history` |
| Search | `classpath:db/migration/jgit-storage-hibernate/search/h2` | `classpath:db/migration/jgit-storage-hibernate/search/postgresql` | `jgit_storage_hibernate_search_schema_history` |

The migration contract starts with the 0.1.4 schema and is published from 0.1.5 onward. Application-specific workflow, session, audit and outbox tables are intentionally outside these locations.

See [docs/consuming.md](docs/consuming.md) for fresh installation, shared-schema installation, 0.1.4 adoption, backup, failure handling, checksum policy, rollback strategy and multi-version upgrades.

## Semantic history

```java
JavaProjectAnalysisResult before =
    new JavaProjectAnalyzer().analyze(beforeSnapshot, configuration);
JavaProjectAnalysisResult after =
    new JavaProjectAnalyzer().analyze(afterSnapshot, configuration);

List<SemanticChange> changes = new JavaSemanticDiff().compare(before, after);
List<SymbolTimeline> timelines =
    new SymbolTimeMachine().build(List.of(before, after));
JavaGraphDelta graphDelta =
    JavaGraphDelta.between(JavaSoftwareGraph.from(before), JavaSoftwareGraph.from(after));
```

Binding quality and unresolved dependencies are explicit diagnostics rather than hidden guesses.

## Architecture intent and drift

```text
element ui layer "UI" packagePrefix=com.example.ui
element database layer "Database" packagePrefix=com.example.persistence

rule no-ui-db forbid REFERENCES_TYPE from ui to database \
  evidence=adr-7 reason="UI must not access persistence directly"

evidence adr-7 for no-ui-db kind=ADR path=docs/adr/0007.md \
  rationale="Layering decision" confidence=1.0
```

```java
ArchitectureSnapshot intent = parser.parse(dslSource).snapshot();
JavaSoftwareGraph observed = JavaSoftwareGraph.from(javaAnalysis);
ArchitectureDriftReport report =
    new ArchitectureDriftEngine().evaluate(intent, observed);
```

Findings cover forbidden observed relations, missing required relations, unmapped or ambiguous symbols, missing evidence and stale evidence.

## Entity registration

```java
List<Class<?>> projectionEntities = new ArrayList<>();
projectionEntities.addAll(SearchEntities.annotatedClasses());
projectionEntities.addAll(JavaAnalysisEntities.annotatedClasses());
projectionEntities.addAll(ArchitectureEntities.annotatedClasses());

try (HibernateSessionFactoryProvider provider =
    new HibernateSessionFactoryProvider(properties, projectionEntities)) {
  // Core plus selected rebuildable projections share one persistence context.
}
```

## Verification and compatibility

`mvn verify` always exercises H2. When Docker is available, Testcontainers starts PostgreSQL 17.10 and runs the same fresh-install and 0.1.4-upgrade scenarios against a real database. The upgrade tests use immutable legacy DDL fixtures rather than regenerating the old schema from current entity mappings.

CI also checks JGit compatibility, dependency changes, JMH benchmarks and release/documentation consistency. The release workflow refuses to publish when Maven versions, citation metadata, Java requirements or public dependency snippets disagree.

## Documentation

- [Application use case and transaction contract](docs/use-cases/versioned-approval-workflows.md)
- [Consumer and migration operations guide](docs/consuming.md)
- [Core module guide](jgit-storage-hibernate-core/README.md)
- [Search module guide](jgit-storage-hibernate-search/README.md)
- [Java-analysis module guide](jgit-storage-hibernate-java-analysis/README.md)
- [Architecture module guide](jgit-storage-hibernate-architecture/README.md)
- [Release process](docs/release-process.md)
- [Semantic software history roadmap](docs/semantic-history-roadmap.md)

## Design boundaries

- This project extends JGit; it is not a fork and is not affiliated with the Eclipse Foundation.
- Public consumers use module-owned facades and DTOs, not JGit, JDT or Hibernate Search implementation internals.
- Git data is authoritative; semantic and search indexes are rebuildable.
- Domain-specific workflows and application tables remain owned by consuming applications.
- Java 21 is the baseline.

## License

BSD-3-Clause. See [LICENSE](LICENSE).
