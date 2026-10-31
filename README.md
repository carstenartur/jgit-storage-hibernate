# jgit-storage-hibernate

[![Java CI with Maven](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/maven.yml/badge.svg)](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/maven.yml)
[![Coverage](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/gh-pages/badges/coverage.json)](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/maven.yml)
[![Tests](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/gh-pages/badges/tests.json)](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/maven.yml)
[![JMH](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/gh-pages/badges/performance.json)](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/)
[![JMH Workflow](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/performance.yml/badge.svg)](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/performance.yml)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue)](pom.xml)
[![License: BSD-3-Clause](https://img.shields.io/badge/License-BSD--3--Clause-blue.svg)](LICENSE)

## Git history as indexed application knowledge

`jgit-storage-hibernate` gives Java applications database-backed Git repositories and queryable history. Keep workflow definitions under Git version control, answer repeated audit questions, or analyze Java and architecture changes across revisions.

JGit remains the authoritative engine for Git objects, commits, trees, refs, revision walking and repository operations. Core adds Hibernate-backed storage and transaction-safe pack/ref publication. Search adds persistent structured history queries and Lucene full-text search. Java Analysis and Architecture provide semantic in-memory APIs; their module-owned database persistence is still incubating.

The documented release line is **0.11.8**. It publishes optional principal-bound Security and secured JGit Smart HTTP capabilities while keeping users, credentials, ACL persistence and Servlet/JGit HTTP dependencies outside Core.

## Choose your starting point

| You want to... | Start here |
|---|---|
| Embed Git in a Java application | Use Core's public facade and normal JGit APIs. Follow the [library setup](#five-minute-production-setup) and [consumer guide](docs/consuming.md). |
| Run a Git Smart HTTP endpoint | Use the published PostgreSQL-backed server image. Follow the [server quick start](#run-the-published-git-server-image) and [standalone server guide](jgit-storage-hibernate-server/README.md). |
| Analyze repository history, source or architecture | Use [Search](jgit-storage-hibernate-search/README.md) for persistent history queries, [Java Analysis](jgit-storage-hibernate-java-analysis/README.md) for source and symbol history, or [Architecture](jgit-storage-hibernate-architecture/README.md) for versioned rules and drift reports. |

## Existing application integrations

Three applications maintained by the same author use the library in their own workflows:

- [Audio Analyzer](https://github.com/carstenartur/audio-analyzer) keeps Git-backed checkpoints for collaborative DSP workflows.
- [Taxonomy](https://github.com/carstenartur/Taxonomy) versions architecture DSL checkpoints in database-backed repositories for personal and shared workspaces.
- [Sandbox](https://github.com/carstenartur/sandbox) embeds storage through an OSGi bridge and exposes history search and Java analysis in its server.

Each application owns an executable compatibility contract. The [consumer matrix and validation guide](docs/consumer-compatibility.md#pinned-consumers) records the selected modules and scope; the [pinned descriptor](.github/consumer-compatibility.json) identifies the exact consumer revisions and contract scripts. [Compatibility workflow results](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/consumer-compatibility.yml) retain baseline/candidate results, dependency evidence and test reports for the exercised application paths and module combinations.

## Version application data with Git

Consider a purchasing service that publishes approval workflows:

```yaml
id: purchase-approval
approvalLimit: 10000
approvalMode: dualcontrol
```

Each published definition can be addressed by its Git commit ID. Competing changes can live on branches and be merged through normal JGit APIs, while the repository stays inside the application's database operational boundary. A support screen can then ask when dual control was introduced or which versions changed `purchase-approval.yaml`.

The [versioned approval workflow use case](docs/use-cases/versioned-approval-workflows.md) shows the complete application flow. Its executable [publication and history-query test](jgit-storage-hibernate-search/src/test/java/io/github/carstenartur/jgit/storage/hibernate/search/VersionedApprovalWorkflowUseCaseTest.java) verifies the final `main` ref and the indexed workflow-history matches.

The application owns the workflow API and coordination with its domain rows. Git publication and Search indexing are separate operations; sharing a database does not create [one transaction across application data, Git and indexes](#transaction-safe-database-publication).

<a id="two-questions-that-show-the-difference"></a>

## Three questions that show the difference

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

Git does not contain Java declaration identities, JDT binding keys, type-usage relations or symbol continuity across package moves. Java Analysis derives that semantic model from each version and combines it with symbol timelines, allowing an old qualified name to lead to usages after a move or rename. These analysis results are supported as module-owned DTOs and in-memory graphs; persistence of the Java-analysis entity model is incubating and application-owned.

See the complete [change-audit and Java-usage use case](docs/use-cases/change-audit-and-java-usage.md), the executable [compound history test](jgit-storage-hibernate-search/src/test/java/io/github/carstenartur/jgit/storage/hibernate/search/CompoundCommitHistoryQueryH2Test.java) and the executable [Java usage test](jgit-storage-hibernate-java-analysis/src/test/java/io/github/carstenartur/jgit/storage/hibernate/javaanalysis/JavaTypeUsageHistoryQueryTest.java).

### Does this version violate an architecture decision?

Keep an explicit rule beside the code, map Java packages to architecture elements, and evaluate the observed software graph for a selected version. For example, a UI layer must not reference the database layer:

```text
element ui layer "UI" packagePrefix=demo.ui
element database layer "Database" packagePrefix=demo.db
rule no-ui-db forbid REFERENCES_TYPE from ui to database evidence=adr-7 reason="UI must not access database"
evidence adr-7 for no-ui-db kind=ADR path=docs/adr/0007.md rationale="Layering decision" confidence=1.0
```

`ArchitectureDriftEngine` evaluates this versioned intent against a `JavaSoftwareGraph`. For an observed forbidden dependency, the finding retains the rule, affected code location and referenced ADR evidence. Missing evidence and unmapped or ambiguous code mappings are explicit findings. The evaluation API works in memory; module-owned persistence remains incubating.

See the [Architecture guide](jgit-storage-hibernate-architecture/README.md) for the parser and evaluation flow, and [`VersionedArchitectureDriftTest`](jgit-storage-hibernate-architecture/src/test/java/io/github/carstenartur/jgit/storage/hibernate/architecture/VersionedArchitectureDriftTest.java) for an executable forbidden-dependency example that retains `adr-7` in its findings.

## What it adds on top of JGit

| Need | What the project adds | Maturity |
|---|---|---|
| Operate Git without a filesystem-backed `.git` directory | Hibernate-backed DFS/Reftable storage with chunked payloads and transactional publication | Supported Core contract |
| Run repeated structured history queries | Materialized first-parent changed paths plus indexed author, committer and timestamp fields | Supported Search contract |
| Search history content | Hibernate Search/Lucene indexes for messages, changed paths and selected changed-file text | Supported Search contract |
| Enforce multi-user repository and protected-ref access | Explicit principal contexts, database grants/ref rules, final direct-JGit checks, revocable tokens and bounded audit | Supported since `0.11.0` |
| Expose secured clone, fetch and push over JGit Smart HTTP | Request-bound resolver and upload/receive factories over the same Core publication checks | Supported since `0.11.0` |
| Understand Java evolution beyond tokens and lines | Binding-aware symbols, references, semantic diff, timelines and software graphs | Analysis API supported; persistence incubating |
| Keep architecture intent connected to implementation | Versioned rules, evidence, code mapping and drift evaluation | Evaluation API supported; persistence incubating |

Git objects and refs remain authoritative. Search, Java Analysis and Architecture outputs are derived projections or analyses: they can be rebuilt and must not be treated as the source of truth.

## Why repeated queries use a projection

Search changes **when and how often** expensive history-query work is performed:

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

### Measured crossover for repeated history queries

A retained PostgreSQL/JMH fixture now answers the same practical questions through three paths: a normal JGit `FileRepository` with optimized on-demand traversal, the identical JGit traversal over `HibernateRepository`, and the materialized Search projection. The fixture uses canonical nested Git trees and verifies that indexed and on-demand implementations return the same commit set before timing begins.

At 1,000 deterministic commits, the strongest smoke result is the exact-path + changed-content query: **96.20 ms/op** through `FileRepository`/JGit, **22.02 ms/op** through `HibernateRepository`/JGit and **3.47 ms/op** through the indexed projection. The complete `content-v1` rebuild cost was about **840 ms**, which corresponds to a conservative break-even of roughly **nine** such queries versus rebuilding once and then querying the projection. Exact path + time measured 66.86/17.29/3.10 ms/op and the compound author/path/time/content audit query 36.31/10.22/2.62 ms/op.

These are workload point estimates from a bounded smoke fixture, not universal production speedup claims. Their architectural value is that the indexed path is also materially faster than on-demand JGit over the **same database-backed repository**, separating the benefit of materialization from filesystem-versus-database storage effects. See the complete [JGit versus indexed history-query crossover methodology](docs/operations/history-query-crossover.md) and the [public performance history](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/).

📈 **[Performance history](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/)** · 📊 **[Performance status and distance to the ceiling](docs/performance-status.md)** · ⚙️ **[Benchmark workflow](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/performance.yml)**

Performance-chart reading rule: **lower is always better/faster; higher is worse/slower or more expensive.** Timing and throughput evidence is normalized to `ms/op`; non-time chart units are published only when they preserve the same smaller-is-better direction.

## Module guide

Security and Smart HTTP have been published artifacts since `0.11.0`.

| Module | Choose it when... | Persistence/runtime contract |
|---|---|---|
| `jgit-storage-hibernate-core` | You need database-backed Git semantics and transaction-safe repository publication. | Versioned Flyway migrations for H2, HSQLDB, PostgreSQL and SQL Server |
| `jgit-storage-hibernate-security` | You need principals/groups, repository/ref ACLs, local credentials or tokens, revocation and durable authorization/identity audit. | Own Flyway migrations for H2, HSQLDB, PostgreSQL and SQL Server; principal-bound direct-JGit enforcement |
| `jgit-storage-hibernate-smart-http` | You expose authenticated clone, fetch and push through JGit Smart HTTP. | No schema; optional Servlet/JGit HTTP adapter over Core's final ref checks |
| `jgit-storage-hibernate-search` | Users or services need repeated audit, reporting or content-search queries. | Versioned Flyway migrations for H2 and PostgreSQL |
| `jgit-storage-hibernate-java-analysis` | You need to know which logical Java declaration changed and which versions are affected. | In-memory API supported; entity persistence incubating |
| `jgit-storage-hibernate-architecture` | You need explainable architecture drift and decision provenance. | In-memory API supported; entity persistence incubating |
| `jgit-storage-hibernate-benchmarks` | You maintain or review backend performance. | Not a runtime dependency |

## Run the published Git server image

A released multi-architecture image is available for users who need a runnable PostgreSQL-backed Git
Smart HTTP endpoint rather than a Java library integration:

```text
ghcr.io/carstenartur/jgit-storage-hibernate-server:0.11.8
```

The image does not contain its own PostgreSQL server or the host-side Compose configuration. The
commands below therefore first obtain this repository's `compose.yaml` and `.env.example`. From any
working directory:

```bash
git clone --depth 1 https://github.com/carstenartur/jgit-storage-hibernate.git
cd jgit-storage-hibernate

cp .env.example .env
chmod 600 .env
${EDITOR:-vi} .env            # set JSH_DATABASE_PASSWORD and JSH_ADMIN_PASSWORD

docker compose config --quiet
docker compose config --images  # shows PostgreSQL and the published GHCR server image
docker compose pull postgres git-server
docker compose up --no-build --detach --wait postgres git-server
docker compose ps
curl --fail http://localhost:8080/actuator/health/readiness
```

The `git-server` target in `docker compose pull` downloads the GHCR image shown above. The same target
in `docker compose up --no-build` creates and starts a container from that published image; it cannot
fall back to compiling the repository source. PostgreSQL is started alongside it because the server
stores Git data there.

The endpoint works with normal Git CLI, JGit and IDE Smart HTTP clone/fetch/push operations. It is
**not** a drop-in replacement for GitLab, Gitea, Gerrit or other forge images when consumers depend on
SSH, LFS, pull requests, their REST APIs, hooks, webhooks, database schemas or plug-in extension
points. The [standalone server guide](jgit-storage-hibernate-server/README.md) contains the secure
quick start, compatibility matrix, source-build override, digest pinning and production boundaries.

## Five-minute production setup

The documented release line is **0.11.8**. Java 21 is required. PostgreSQL 17 is the production-oriented tested database; HSQLDB 2.7 is supported for embedded persistent Core deployments; H2 2.4.x remains supported for tests, demos and lightweight development.

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
  <version>0.11.8</version>
</dependency>
```

Add `jgit-storage-hibernate-search` when the persistent generic query layer is needed. Java Analysis and Architecture can be added for their analysis APIs, but their entity mappings do not yet constitute a module-owned production schema. Security and Smart HTTP have been independently selectable since `0.11.0`; source/reactor builds keep them aligned through `${project.version}`.

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
| Security | yes | yes | yes | `jgit_storage_hibernate_security_schema_history` |
| Smart HTTP | no schema | no schema | no schema | — |
| Search | yes | no | yes | `jgit_storage_hibernate_search_schema_history` |
| Java Analysis entities | no module-owned contract | no | no | incubating |
| Architecture entities | no module-owned contract | no | no | incubating |

Core owns `git_packs`, `git_pack_chunks`, `git_repository_lock` and `git_reflog`. The chunk migration preserves existing inline BLOBs and applies chunking only to new writes.

## Verification

`mvn verify` exercises H2, HSQLDB in-memory/file-backed restart paths and PostgreSQL through Testcontainers when Docker is available. CI also checks JGit 7.5, 7.6, 7.7 and 7.8 compatibility, dependency changes, release consistency and repeatable JMH workloads. The existing performance workflow additionally runs the 1/16/128 MiB pack-capacity profile manually and weekly.

## Documentation

- [Standalone server image, compatibility and deployment](jgit-storage-hibernate-server/README.md)
- [Performance status and distance to the ceiling](docs/performance-status.md)
- [Performance history](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/)
- [Benchmark methodology](docs/benchmarks.md)
- [JGit versus indexed history-query crossover](docs/operations/history-query-crossover.md)
- [Secured JGit Smart HTTP](docs/operations/secured-smart-http.md)
- [Consumer, migration and database matrix](docs/consuming.md)
- [Real-consumer integrations and compatibility evidence](docs/consumer-compatibility.md)
- [Change-audit and Java-usage use case](docs/use-cases/change-audit-and-java-usage.md)
- [Approval-workflow use case and transaction contract](docs/use-cases/versioned-approval-workflows.md)
- [History query cookbook](docs/query-cookbook.md)
- [Multi-instance operation](docs/operations/multi-instance.md)
- [Capacity and recovery](docs/operations/capacity-and-recovery.md)
- [Taxonomy/Spring adoption runbook](docs/taxonomy-adoption.md)
- [Release process](docs/release-process.md)
- [Cite this project](https://doi.org/10.5281/zenodo.21210132)

## Design boundaries

- This project extends JGit; it is not a fork and is not affiliated with the Eclipse Foundation.
- Consumer code uses module-owned facades and DTOs, not packages marked `@InternalApi`.
- Git data is authoritative; semantic and search indexes are rebuildable.
- Domain-specific workflows and application tables remain owned by consuming applications.
- Security and Smart HTTP are explicit optional capabilities; Core never selects users, credentials, Servlet or an HTTP server transitively.
- Java 21 is the baseline.

## License

BSD-3-Clause. See [LICENSE](LICENSE).
