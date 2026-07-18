# jgit-storage-hibernate

[![Java CI with Maven](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/maven.yml/badge.svg)](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/maven.yml)
[![Coverage](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/main/docs/badges/coverage.json)](docs/badges/coverage.json)
[![Tests](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/main/docs/badges/tests.json)](docs/badges/tests.json)
[![JMH Benchmarks](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/performance.yml/badge.svg)](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/performance.yml)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue)](pom.xml)
[![License: BSD-3-Clause](https://img.shields.io/badge/License-BSD--3--Clause-blue.svg)](LICENSE)

https://doi.org/10.5281/zenodo.21210132

## Git semantics, operated and queried like application data

JGit provides the authoritative Git object and repository model. `jgit-storage-hibernate` adds database-native persistence, transaction-safe pack/ref publication, reusable commit-history queries, binding-aware Java history and explainable architecture governance without replacing the standard JGit `Repository` API.

Use it when Git is part of an application's domain model and repository state must live inside the same relational database, backup, deployment, access-control and lifecycle model as the rest of the application—without exposing `org.eclipse.jgit.internal.*` to consumers.

## Two questions that show the difference

### Which changes did one person make in one subsystem during one interval?

With JGit, an application can build this by walking commits, checking author metadata, diffing every candidate tree against its parent, filtering paths and sorting the result. The Search module turns that repeated algorithm into a projection and one compound database query:

```java
CommitHistoryQuery query =
    CommitHistoryQuery.forRepository("payment-platform")
        .authoredBy("alice@example.com")
        .touchingPath("services/payments/fraud/")
        .between(
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-03-31T23:59:59Z"))
        .limit(100)
        .build();

List<GitCommitIndex> changes =
    new GitHistorySearchService(sessionFactory).findChanges(query);
```

`CommitIndexer` stores paths actually changed relative to the first parent, not every path present in the snapshot. Root commits treat every path as changed; merge commits use first-parent semantics. Added and modified changed-file content is available for full-text search, and deleted files remain represented by path.

### Which code locations used this Java class in each version?

A token search can find `ApprovalPolicy` in one checkout. It cannot reliably prove which same-named type each occurrence binds to, follow the declaration after a package move, identify the enclosing declaration or expose binding quality.

```java
JavaTypeUsageHistory usageHistory =
    new JavaTypeUsageHistoryQuery()
        .find(orderedAnalyses, "demo.policy.ApprovalPolicy")
        .orElseThrow();

for (JavaTypeUsageHistory.Version version : usageHistory.versions()) {
  System.out.println(version.commitId() + " -> " + version.type().getQualifiedName());
  for (JavaTypeUsageHistory.UsageSite usage : version.usageSites()) {
    System.out.printf(
        "%s:%d %s %s binding=%s%n",
        usage.path(),
        usage.line(),
        usage.relation(),
        usage.sourceQualifiedName(),
        usage.bindingStatus());
  }
}
```

The query combines `SymbolTimeMachine` with one binding-aware software graph per commit. An old qualified name can still lead to usages after the type moved or was renamed.

These questions are not theoretically impossible with custom JGit/JDT code. The library value is that the storage protocol, first-parent change projection, compound query API, semantic identity model and cross-version graph query are supplied and regression-tested together.

See the complete [change-audit and Java-usage use case](docs/use-cases/change-audit-and-java-usage.md), the executable [compound history test](jgit-storage-hibernate-search/src/test/java/io/github/carstenartur/jgit/storage/hibernate/search/CompoundCommitHistoryQueryH2Test.java) and the executable [Java usage test](jgit-storage-hibernate-java-analysis/src/test/java/io/github/carstenartur/jgit/storage/hibernate/javaanalysis/JavaTypeUsageHistoryQueryTest.java).

## What it adds on top of JGit

| Need | What the project adds | Practical outcome |
|---|---|---|
| Operate Git without a filesystem-backed `.git` directory | Hibernate-backed DFS/Reftable storage with transactional pack publication | Readers do not observe partially published pack rows, and repository data follows database backup and access-control practices. |
| Ask combined history questions repeatedly | First-parent changed-path projection plus author/path/time and full-text queries | Support and audit screens query indexed history instead of traversing and diffing every commit for every request. |
| Understand Java evolution beyond tokens and lines | Binding-aware symbols, references, semantic diff, timelines and software graphs | Follow declarations through moves and ask which code locations used a logical type in each version. |
| Keep architecture intent connected to implementation | Versioned rules, evidence, code mapping and drift evaluation | Produce deterministic findings that explain which rule was violated, where and why. |

Git objects remain authoritative. Search, Java-analysis and architecture tables are derived projections and must be rebuildable.

## Module elevator pitches

| Module | Elevator pitch | Choose it when... |
|---|---|---|
| `jgit-storage-hibernate-core` | Use the familiar JGit repository API while storing packs, refs, reftables and reflogs in the relational database your application already operates. | You need database-backed Git semantics and no search or code-analysis layer. |
| `jgit-storage-hibernate-search` | Ask compound author/path/time and full-text questions over actual changed paths without re-walking and re-diffing history for every request. | Users or services need repeated audit, support or reporting queries. |
| `jgit-storage-hibernate-java-analysis` | Follow binding-aware Java declarations and usage sites across commits, moves and semantic changes. | You need to know which declaration changed, who uses a type and which versions are affected. |
| `jgit-storage-hibernate-architecture` | Version architecture intent beside the code and compare rules and evidence with the observed software graph. | You need explainable architecture drift, constraints and decision provenance. |
| `jgit-storage-hibernate-benchmarks` | Measure storage operations through repeatable JMH workloads. | You maintain or review performance; it is not a runtime dependency. |

## Five-minute production setup

The documented release line is **0.1.5**. Java 21 is required. PostgreSQL 17 is the production-oriented tested database; H2 2.4.x is supported for tests, demos and lightweight development.

### 1. Add the Core dependency

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-core</artifactId>
  <version>0.1.5</version>
</dependency>
```

Add `jgit-storage-hibernate-search` and `jgit-storage-hibernate-java-analysis` at the same version only when their derived query layers are needed.

### 2. Apply the packaged migration before Hibernate starts

```java
Flyway.configure()
    .dataSource(dataSource)
    .locations(CoreSchemaMigrations.POSTGRESQL_LOCATION)
    .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
    .load()
    .migrate();
```

A shared schema or an existing 0.1.4 installation requires a deliberate one-time baseline. Do not enable `baselineOnMigrate` blindly; follow the provisioning runbooks in [docs/consuming.md](docs/consuming.md).

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

The guarantee is deliberately **per storage operation**. The current implementation does not provide one ambient transaction spanning an arbitrary application entity, Git object insertion, ref publication, reflog append, Search indexing and Java analysis. Those are separate retryable steps. See the [precise transaction contract and failure behavior](docs/use-cases/versioned-approval-workflows.md#database-transaction-guarantees).

## Versioned database contract

Core and Search package Flyway-compatible SQL resources for H2 and PostgreSQL in their own artifacts. They use separate history tables so both modules can evolve independently inside one application schema.

| Artifact | H2 location | PostgreSQL location | History table |
|---|---|---|---|
| Core | `classpath:db/migration/jgit-storage-hibernate/core/h2` | `classpath:db/migration/jgit-storage-hibernate/core/postgresql` | `jgit_storage_hibernate_core_schema_history` |
| Search | `classpath:db/migration/jgit-storage-hibernate/search/h2` | `classpath:db/migration/jgit-storage-hibernate/search/postgresql` | `jgit_storage_hibernate_search_schema_history` |

The migration contract starts with the 0.1.4 schema and is published from 0.1.5 onward. Application-specific workflow, session, audit and outbox tables are intentionally outside these locations.

See [docs/consuming.md](docs/consuming.md) for fresh installation, shared-schema installation, 0.1.4 adoption, backup, failure handling, checksum policy, rollback strategy and multi-version upgrades.

## Semantic history and impact

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

Binding quality and unresolved dependencies are explicit diagnostics rather than hidden guesses. See the [history query cookbook](docs/query-cookbook.md) for rename, move, impact and type-usage recipes.

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

`mvn verify` always exercises H2. When Docker is available, Testcontainers starts PostgreSQL 17.10 and runs fresh-install and 0.1.4-upgrade scenarios against a real database. The upgrade tests use immutable legacy DDL fixtures rather than regenerating the old schema from current entity mappings.

CI also checks JGit compatibility, dependency changes, JMH benchmarks and release/documentation consistency. The release workflow refuses to publish when Maven versions, citation metadata, Java requirements or public dependency snippets disagree.

## Documentation

- [Change-audit and Java-usage use case](docs/use-cases/change-audit-and-java-usage.md)
- [Approval-workflow use case and transaction contract](docs/use-cases/versioned-approval-workflows.md)
- [History query cookbook](docs/query-cookbook.md)
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
