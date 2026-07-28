# jgit-storage-hibernate

[![Java CI with Maven](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/maven.yml/badge.svg)](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/maven.yml)
[![Coverage](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/main/docs/badges/coverage.json)](docs/badges/coverage.json)
[![Tests](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/main/docs/badges/tests.json)](docs/badges/tests.json)
[![JMH Benchmarks](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/performance.yml/badge.svg)](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/performance.yml)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue)](pom.xml)
[![License: BSD-3-Clause](https://img.shields.io/badge/License-BSD--3--Clause-blue.svg)](LICENSE)

https://doi.org/10.5281/zenodo.21210132

## Git history as indexed application knowledge

JGit is the authoritative engine for Git objects, commits, trees, refs, revision walking and repository operations. `jgit-storage-hibernate` adds a relational storage backend plus persistent query models over that history: transaction-safe pack/ref publication, structured history queries and Lucene full-text search. The Java Analysis and Architecture modules additionally provide semantic in-memory analysis APIs; their module-owned database persistence is still incubating in the `0.1.x` line.

The important distinction is not merely that the library saves application code. It changes **when and how often** expensive work is performed:

```text
Without a projection
query -> walk commits -> diff trees -> parse/filter content -> return result
query -> walk commits -> diff trees -> parse/filter content -> return result

With jgit-storage-hibernate Search
commit/reindex -> walk/diff/parse once -> persist relational + Lucene indexes
query          -> execute indexed predicates/full-text search
query          -> execute indexed predicates/full-text search
```

Git and JGit do not normally provide a general full-text search engine over commit messages, actual changed paths and changed-file contents. The Search module adds that capability as a rebuildable read model while Git remains authoritative.

## Two questions that show the difference

### Which changes did one person make in one subsystem during one interval?

```java
CommitHistoryQuery query =
    CommitHistoryQuery.forRepository("payment-platform")
        .authoredBy("alice@example.com")
        .touchingPath("services/payments/fraud/")
        .committedBetween(
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-03-31T23:59:59Z"))
        .limit(100)
        .build();

List<GitCommitIndex> changes =
    new GitHistorySearchService(sessionFactory).findChanges(query);
```

JGit can compute such an answer by walking commits and diffing each candidate tree. Search instead materializes actual first-parent changed paths when a commit is indexed, so repeated audit, support and reporting queries become database operations. Root commits treat every path as changed; merge commits use first-parent semantics. Added and modified changed-file content is available to Lucene full-text search, while deleted files remain represented by path.

Time queries use **committer time** by default, matching the point at which a commit entered the current history. `authoredBetween(...)` and `usingAuthorTime()` select the original author timestamp explicitly.

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

Git does not contain Java declaration identities, JDT binding keys, type-usage relations or symbol continuity across package moves. Java Analysis derives that semantic model from each version and combines it with symbol timelines, allowing an old qualified name to lead to usages after a move or rename. In `0.1.x`, these analysis results are supported as module-owned DTOs and in-memory graphs; persistence of the Java-analysis entity model is incubating and application-owned.

See the complete [change-audit and Java-usage use case](docs/use-cases/change-audit-and-java-usage.md), the executable [compound history test](jgit-storage-hibernate-search/src/test/java/io/github/carstenartur/jgit/storage/hibernate/search/CompoundCommitHistoryQueryH2Test.java) and the executable [Java usage test](jgit-storage-hibernate-java-analysis/src/test/java/io/github/carstenartur/jgit/storage/hibernate/javaanalysis/JavaTypeUsageHistoryQueryTest.java).

## What it adds on top of JGit

| Need | What the project adds | Maturity in `0.1.x` |
|---|---|---|
| Operate Git without a filesystem-backed `.git` directory | Hibernate-backed DFS/Reftable storage with chunked payloads and transactional publication | Supported Core contract |
| Run repeated structured history queries | Materialized first-parent changed paths plus indexed author, committer and timestamp fields | Supported Search contract |
| Search history content | Hibernate Search/Lucene indexes for messages, changed paths and selected changed-file text | Supported Search contract |
| Understand Java evolution beyond tokens and lines | Binding-aware symbols, references, semantic diff, timelines and software graphs | Analysis API supported; persistence incubating |
| Keep architecture intent connected to implementation | Versioned rules, evidence, code mapping and drift evaluation | Evaluation API supported; persistence incubating |

Git objects and refs remain authoritative. Search, Java Analysis and Architecture outputs are derived projections or analyses: they can be rebuilt and must not be treated as the source of truth.

## Module guide

| Module | Choose it when... | Persistence contract |
|---|---|---|
| `jgit-storage-hibernate-core` | You need database-backed Git semantics and transaction-safe repository publication. | Versioned Flyway migrations for H2, HSQLDB and PostgreSQL |
| `jgit-storage-hibernate-search` | Users or services need repeated audit, reporting or content-search queries. | Versioned Flyway migrations for H2 and PostgreSQL |
| `jgit-storage-hibernate-java-analysis` | You need to know which logical Java declaration changed and which versions are affected. | In-memory API supported; entity persistence incubating |
| `jgit-storage-hibernate-architecture` | You need explainable architecture drift and decision provenance. | In-memory API supported; entity persistence incubating |
| `jgit-storage-hibernate-benchmarks` | You maintain or review backend performance. | Not a runtime dependency |

## Five-minute production setup

The documented release line is **0.1.13**. Java 21 is required. PostgreSQL 17 is the production-oriented tested database; HSQLDB 2.7 is supported for embedded persistent Core deployments; H2 2.4.x remains supported for tests, demos and lightweight development.

### 1. Configure the anonymous release repository

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

No GitHub token or Maven Central account is required.

### 2. Add the Core dependency

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-core</artifactId>
  <version>0.1.13</version>
</dependency>
```

Add `jgit-storage-hibernate-search` at the same version when the persistent generic query layer is needed. Java Analysis and Architecture can be added for their analysis APIs, but their entity mappings do not yet constitute a module-owned production schema.

### 3. Apply the packaged migration before Hibernate starts

```java
Flyway.configure()
    .dataSource(dataSource)
    .locations(CoreSchemaMigrations.POSTGRESQL_LOCATION)
    .table(CoreSchemaMigrations.SCHEMA_HISTORY_TABLE)
    .load()
    .migrate();
```

Use `CoreSchemaMigrations.HSQLDB_LOCATION` for HSQLDB. A shared schema, an existing 0.1.4 installation or a copied pre-library Taxonomy schema requires a deliberate one-time procedure. Do not enable `baselineOnMigrate` blindly; follow [docs/consuming.md](docs/consuming.md) and the [Taxonomy adoption runbook](docs/taxonomy-adoption.md).

### 4. Make Hibernate validate, not mutate, the production schema

```properties
hibernate.hbm2ddl.auto=validate
```

`update` and `create-drop` are reserved for disposable local databases and isolated tests.

### 5. Open the repository through the public facade

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

Framework-managed applications can supply their own Hibernate `SessionFactory`. `CoreEntities.annotatedClasses()` provides the stable entity-registration contract.

## Transaction-safe database publication

Core uses explicit Hibernate transactions rather than presenting partially written database rows as repository state:

- pack extensions are written through temporary files and persisted as ordered 1 MiB chunks with `committed=false`;
- writer tokens and renewable leases prevent cleanup from deleting slow active writers;
- publishing all extensions of a pack clears their leases and deleting replaced packs removes metadata plus chunks in one transaction;
- normal JGit `RefUpdate` operations publish the Reftable and append the queryable `git_reflog` row in the same repository-scoped transaction;
- failed optimistic ref updates do not append a queryable reflog entry;
- lease-aware `PackStorageMaintenance` removes only old pack groups for which every persisted extension is uncommitted and inactive;
- Search projection upserts remain separate, retryable derived-state operations.

The guarantee is deliberately **per storage operation**. The implementation does not provide one ambient transaction spanning arbitrary application entities, Git insertion, Search indexing and Java analysis.

Independent `SessionFactory` instances are exercised against one PostgreSQL schema for competing expected-old-ID ref updates. Repository deletion is different: its open-handle guard is JVM-local. A clustered deployment must stop writers and obtain application-level exclusive maintenance before deleting a logical repository.

## Current operational boundaries

- Shallow repositories are rejected explicitly; shallow boundaries are not silently retained only in memory.
- New pack-related payloads use bounded chunks, but concurrent writers still require sufficient temporary-disk capacity and database throughput. Existing inline BLOB rows remain readable until replaced by a later repack.
- The optional `pack-capacity` profile verifies 1 MiB, 16 MiB and 128 MiB payloads; it is evidence for bounded behavior, not an unlimited-repository claim.
- Only packages not marked `@InternalApi` form the supported consumer API. DFS/Reftable adapter packages may change with the pinned JGit implementation.
- Search rows created before the author/committer split must be reindexed after upgrading so committer metadata is authoritative.

See [Pack capacity and recovery](docs/operations/capacity-and-recovery.md) for sizing, writer leases and cleanup.

## Versioned database contract

| Artifact | H2 | HSQLDB | PostgreSQL | History table |
|---|---|---|---|---|
| Core | yes | yes | yes | `jgit_storage_hibernate_core_schema_history` |
| Search | yes | no | yes | `jgit_storage_hibernate_search_schema_history` |
| Java Analysis entities | no module-owned contract | no | no | incubating |
| Architecture entities | no module-owned contract | no | no | incubating |

Core owns `git_packs`, `git_pack_chunks`, `git_repository_lock` and `git_reflog`. The chunk migration preserves existing inline BLOBs and applies chunking only to new writes.

## Verification

`mvn verify` exercises H2, HSQLDB in-memory/file-backed restart paths and PostgreSQL through Testcontainers when Docker is available. CI also checks JGit 7.5, 7.6 and 7.7 compatibility, dependency changes, release consistency and repeatable JMH workloads. The existing performance workflow additionally runs the 1/16/128 MiB pack-capacity profile manually and weekly.

## Documentation

- [Consumer, migration and database matrix](docs/consuming.md)
- [Change-audit and Java-usage use case](docs/use-cases/change-audit-and-java-usage.md)
- [Approval-workflow use case and transaction contract](docs/use-cases/versioned-approval-workflows.md)
- [History query cookbook](docs/query-cookbook.md)
- [Multi-instance operation](docs/operations/multi-instance.md)
- [Capacity and recovery](docs/operations/capacity-and-recovery.md)
- [Taxonomy/Spring adoption runbook](docs/taxonomy-adoption.md)
- [Release process](docs/release-process.md)

## Design boundaries

- This project extends JGit; it is not a fork and is not affiliated with the Eclipse Foundation.
- Consumer code uses module-owned facades and DTOs, not packages marked `@InternalApi`.
- Git data is authoritative; semantic and search indexes are rebuildable.
- Domain-specific workflows and application tables remain owned by consuming applications.
- Java 21 is the baseline.

## License

BSD-3-Clause. See [LICENSE](LICENSE).
