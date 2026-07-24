# jgit-storage-hibernate

[![Java CI with Maven](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/maven.yml/badge.svg)](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/maven.yml)
[![Coverage](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/main/docs/badges/coverage.json)](docs/badges/coverage.json)
[![Tests](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/main/docs/badges/tests.json)](docs/badges/tests.json)
[![JMH Benchmarks](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/performance.yml/badge.svg)](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/performance.yml)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue)](pom.xml)
[![License: BSD-3-Clause](https://img.shields.io/badge/License-BSD--3--Clause-blue.svg)](LICENSE)

https://doi.org/10.5281/zenodo.21210132

## Git history as indexed application knowledge

JGit is the authoritative engine for Git objects, commits, trees, refs, revision walking and repository operations. `jgit-storage-hibernate` adds a relational storage backend plus persistent query models over that history: transaction-safe pack/ref publication, structured history queries, Lucene full-text search, binding-aware Java histories and explainable architecture analysis.

The important distinction is not merely that the library saves application code. It changes **when and how often** expensive work is performed:

```text
Without a projection
query -> walk commits -> diff trees -> parse/filter content -> return result
query -> walk commits -> diff trees -> parse/filter content -> return result
query -> walk commits -> diff trees -> parse/filter content -> return result

With jgit-storage-hibernate
commit/reindex -> walk/diff/parse once -> persist relational + Lucene indexes
query          -> execute indexed predicates/full-text search
query          -> execute indexed predicates/full-text search
query          -> execute indexed predicates/full-text search
```

Indexing moves revision traversal, first-parent diffing, text extraction and index construction from query time to ingestion or explicit reindex time. Queries then use relational indexes and Hibernate Search/Lucene rather than recomputing repository history for every request.

Git and JGit do not normally provide a general full-text search engine over commit messages, actual changed paths and changed-file contents. The Search module adds that capability as a rebuildable read model while Git remains authoritative.

## Two questions that show the difference

### Which changes did one person make in one subsystem during one interval?

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

JGit can compute such an answer by walking commits and diffing each candidate tree. This module instead materializes actual first-parent changed paths when a commit is indexed, so repeated audit, support and reporting queries become database operations. Root commits treat every path as changed; merge commits use first-parent semantics. Added and modified changed-file content is also available to Lucene full-text search, while deleted files remain represented by path.

### Which code locations used this Java class in each version?

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

Git does not contain Java declaration identities, JDT binding keys, type-usage relations or symbol continuity across package moves. Java Analysis derives that semantic index from each version and combines it with symbol timelines, allowing an old qualified name to lead to usages after a move or rename.

See the complete [change-audit and Java-usage use case](docs/use-cases/change-audit-and-java-usage.md), the executable [compound history test](jgit-storage-hibernate-search/src/test/java/io/github/carstenartur/jgit/storage/hibernate/search/CompoundCommitHistoryQueryH2Test.java) and the executable [Java usage test](jgit-storage-hibernate-java-analysis/src/test/java/io/github/carstenartur/jgit/storage/hibernate/javaanalysis/JavaTypeUsageHistoryQueryTest.java).

## What it adds on top of JGit

| Need | What the project adds | Operational effect |
|---|---|---|
| Operate Git without a filesystem-backed `.git` directory | Hibernate-backed DFS/Reftable storage with transactional pack publication | Repository state follows database backup, access-control and lifecycle practices; readers do not observe partially published pack rows. |
| Run repeated structured history queries | Materialized first-parent changed paths plus indexed author and timestamp fields | Ingestion performs traversal/diff work once; queries combine predicates without re-walking history. |
| Search history content | Hibernate Search/Lucene indexes for messages, changed paths and selected changed-file text | Full-text queries use an inverted index, a capability standard Git/JGit does not normally expose. |
| Understand Java evolution beyond tokens and lines | Binding-aware symbols, references, semantic diff, timelines and software graphs | Follow declarations through moves and ask which code locations used a logical type in each version. |
| Keep architecture intent connected to implementation | Versioned rules, evidence, code mapping and drift evaluation | Produce deterministic findings that explain which rule was violated, where and why. |

Git objects and refs remain authoritative. Search, Java Analysis and Architecture are derived projections: they may be updated asynchronously, can be rebuilt and must not be treated as the source of truth.

## Module elevator pitches

| Module | Elevator pitch | Choose it when... |
|---|---|---|
| `jgit-storage-hibernate-core` | Use the public JGit repository API while storing packs, refs, reftables and reflogs in the relational database your application already operates. | You need database-backed Git semantics and transaction-safe repository publication. |
| `jgit-storage-hibernate-search` | Move history traversal, first-parent diffing and text indexing to ingestion/reindex time, then run compound relational and Lucene full-text queries at request time. | Users or services need repeated audit, support, reporting or content-search queries. |
| `jgit-storage-hibernate-java-analysis` | Derive a versioned semantic index of Java declarations, bindings and usages rather than searching source text alone. | You need to know which logical declaration changed, who uses it and which versions are affected. |
| `jgit-storage-hibernate-architecture` | Version architecture intent beside the code and compare rules and evidence with the observed software graph. | You need explainable architecture drift, constraints and decision provenance. |
| `jgit-storage-hibernate-benchmarks` | Measure storage operations through repeatable JMH workloads. | You maintain or review performance; it is not a runtime dependency. |

## Five-minute production setup

The documented release line is **0.1.7**. Java 21 is required. PostgreSQL 17 is the production-oriented tested database; HSQLDB 2.7 is supported for embedded persistent deployments; H2 2.4.x remains supported for tests, demos and lightweight development.

### 1. Add the Core dependency

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-core</artifactId>
  <version>0.1.7</version>
</dependency>
```

Add `jgit-storage-hibernate-search` and `jgit-storage-hibernate-java-analysis` at the same version when their derived query layers are needed.

### 2. Apply the packaged migration before Hibernate starts

```java
Flyway.configure()
    .dataSource(dataSource)
    .locations(CoreSchemaMigrations.POSTGRESQL_LOCATION)
    .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
    .load()
    .migrate();
```

Use `CoreSchemaMigrations.HSQLDB_LOCATION` for HSQLDB. A shared schema, an existing 0.1.4 installation or a copied pre-library Taxonomy schema requires a deliberate one-time procedure. Do not enable `baselineOnMigrate` blindly; follow the provisioning runbooks in [docs/consuming.md](docs/consuming.md) and the [Taxonomy adoption runbook](docs/taxonomy-adoption.md).

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

Framework-managed applications can supply their own Hibernate `SessionFactory` and share a `DataSource`, mappings and lifecycle with application entities. `CoreEntities.annotatedClasses()` provides the stable entity-registration contract.

## Transaction-safe database publication

Core uses explicit Hibernate transactions rather than presenting partially written database rows as repository state:

- pack extensions are first persisted with `committed=false` and are invisible to `listPacks()` and `openFile(...)`;
- publishing all extensions of a pack and deleting replaced packs happens in one transaction and rolls back on failure;
- normal JGit `RefUpdate` operations publish the Reftable and append the queryable `git_reflog` row in the same repository-scoped transaction;
- failed optimistic ref updates do not append a queryable reflog entry;
- Search projection upserts remain separate, retryable derived-state operations.

This is the ACID benefit described in the original [JGit discussion #251](https://github.com/eclipse-jgit/jgit/discussions/251).

The guarantee is deliberately **per storage operation**. The implementation does not provide one ambient transaction spanning an arbitrary application entity, Git object insertion, Search indexing and Java analysis. Those are separate retryable steps. See the [precise transaction contract and failure behavior](docs/use-cases/versioned-approval-workflows.md#database-transaction-guarantees).

## Indexing and consistency model

- Core Git objects and refs are authoritative.
- Search indexing is performed when commits are added to the projection or during an explicit rebuild.
- The additional ingestion cost buys cheaper repeated queries and full-text search through Lucene indexes.
- If indexing fails after a ref was published, the Git history remains valid and the projection can be retried or rebuilt.
- Consumers may run indexing synchronously, asynchronously or through an outbox, depending on their consistency requirements.
- Existing projections should be rebuilt after analyzer or changed-path-semantics changes.

## Versioned database contract

Core packages Flyway-compatible SQL resources for H2, HSQLDB and PostgreSQL. Search currently packages H2 and PostgreSQL resources. Modules use separate history tables so they can evolve independently inside one application schema.

| Artifact | H2 location | HSQLDB location | PostgreSQL location | History table |
|---|---|---|---|---|
| Core | `classpath:db/migration/jgit-storage-hibernate/core/h2` | `classpath:db/migration/jgit-storage-hibernate/core/hsqldb` | `classpath:db/migration/jgit-storage-hibernate/core/postgresql` | `jgit_storage_hibernate_core_schema_history` |
| Search | `classpath:db/migration/jgit-storage-hibernate/search/h2` | — | `classpath:db/migration/jgit-storage-hibernate/search/postgresql` | `jgit_storage_hibernate_search_schema_history` |

The copied pre-library schema has dedicated HSQLDB and PostgreSQL adoption locations plus a separate adoption history table. See [docs/taxonomy-adoption.md](docs/taxonomy-adoption.md) for duplicate detection, BLOB-preserving migration, Spring-managed integration, reflogs and repository deletion.

## Verification and compatibility

`mvn verify` exercises H2, HSQLDB in-memory/file-backed restart paths and, with Docker, PostgreSQL through Testcontainers. CI also checks JGit 7.5/7.6/7.7 compatibility, dependency changes, JMH benchmarks and release/documentation consistency.

## Documentation

- [Change-audit and Java-usage use case](docs/use-cases/change-audit-and-java-usage.md)
- [Approval-workflow use case and transaction contract](docs/use-cases/versioned-approval-workflows.md)
- [History query cookbook](docs/query-cookbook.md)
- [Consumer and migration operations guide](docs/consuming.md)
- [Taxonomy/Spring adoption runbook](docs/taxonomy-adoption.md)
- [Core module guide](jgit-storage-hibernate-core/README.md)
- [Search module guide](jgit-storage-hibernate-search/README.md)
- [Java-analysis module guide](jgit-storage-hibernate-java-analysis/README.md)
- [Architecture module guide](jgit-storage-hibernate-architecture/README.md)
- [Release process](docs/release-process.md)

## Design boundaries

- This project extends JGit; it is not a fork and is not affiliated with the Eclipse Foundation.
- Public consumers use module-owned facades and DTOs, not JGit, JDT or Hibernate Search implementation internals.
- Git data is authoritative; semantic and search indexes are rebuildable.
- Domain-specific workflows and application tables remain owned by consuming applications.
- Java 21 is the baseline.

## License

BSD-3-Clause. See [LICENSE](LICENSE).
