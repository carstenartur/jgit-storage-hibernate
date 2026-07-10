# jgit-storage-hibernate

[![Java CI with Maven](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/maven.yml/badge.svg)](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/maven.yml)
[![Coverage](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/main/docs/badges/coverage.json)](docs/badges/coverage.json)
[![Tests](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/main/docs/badges/tests.json)](docs/badges/tests.json)
[![JMH Benchmarks](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/performance.yml/badge.svg)](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/performance.yml)
[![JMH Performance](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/main/docs/badges/performance.json)](docs/badges/performance.json)
[![Publish Snapshot](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/publish-snapshot.yml/badge.svg)](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/publish-snapshot.yml)
[![Release](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/release.yml/badge.svg)](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/release.yml)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue)](pom.xml)
[![License: BSD-3-Clause](https://img.shields.io/badge/License-BSD--3--Clause-blue.svg)](LICENSE)
[![Maven: GitHub Packages](https://img.shields.io/badge/Maven-GitHub%20Packages-blue)](docs/consuming.md)
[![Citation: CFF](https://img.shields.io/badge/Citation-CFF-blue)](CITATION.cff)

https://doi.org/10.5281/zenodo.21210132

## Turn Git into a queryable semantic history

`jgit-storage-hibernate` combines a database-backed JGit repository with rebuildable search and analysis projections. Git remains the authoritative history, while Hibernate, Hibernate Search and optional language/DSL analyzers make commits, symbols, references and architecture relations queryable.

The long-term goal is not only to store Git without a filesystem. It is to answer questions ordinary text-based Git tools cannot answer directly:

```text
Which methods changed signature in this release, and which callers are affected?
```

```text
Where did com.example.UserService#save(User) move or get renamed during its history?
```

```text
Which classes stopped implementing an interface between two commits?
```

```text
Which architecture relations changed without accepted evidence?
```

```text
Which semantic transformation occurred repeatedly across many repositories?
```

This turns Git from a file-and-line archive into the foundation of a versioned software knowledge graph.

## Why this is different

Normal Git knows blobs, trees, commits and textual diffs. This project adds optional, reproducible projections for:

- Java types, methods, fields and resolved references,
- binding quality and complete analysis provenance,
- semantic changes across commits,
- historical call, inheritance and dependency graphs,
- structured DSL elements, relations and evidence,
- mining candidates with deterministic identity and review lifecycle.

Every derived fact is intended to remain traceable to repository, commit, blob, source position, analyzer version and binding context.

See [Semantic software history roadmap](docs/semantic-history-roadmap.md) for the planned evolution and highest-attention demonstrators.

## Status

The storage and generic search layers are implemented. A binding-aware Java/JDT analysis module is being introduced as the first language-specific projection. The next major steps are project-wide binding resolution, semantic diff, symbol timelines and versioned graph queries.

Concepts are being extracted or generalized from two existing reference consumers:

- **Sandbox:** JGit history traversal, Java diff analysis, refactoring mining, deterministic candidates and staged validation/promotion.
- **Taxonomy:** structured architecture DSL history, semantic diff, evidence, projection staleness, branch comparison and merge/cherry-pick preview.

Application-specific Eclipse UI, cleanup logic and Taxonomy domain models remain outside this repository.

## Architecture

```text
Git / JGit Repository API
  -> jgit-storage-hibernate-core
       -> Hibernate-backed DFS/Reftable storage
       -> relational database

  -> jgit-storage-hibernate-search
       -> generic commit/blob/path/full-text projections
       -> Hibernate Search / Lucene

  -> jgit-storage-hibernate-java-analysis
       -> JDT Core binding-aware Java analysis
       -> Java symbol and reference projections

  -> future semantic history layers
       -> semantic diff and symbol timelines
       -> versioned call/inheritance/dependency graph
       -> language-neutral DSL analysis SPI
       -> mining candidate lifecycle

  -> jgit-storage-hibernate-benchmarks
       -> JMH benchmarks for storage and analysis operations
```

## Modules

| Module | Purpose | Intended consumers |
|---|---|---|
| `jgit-storage-hibernate-core` | Database-backed JGit storage for packs, reftables and queryable reflogs. | Applications that need Git semantics without filesystem-backed `.git` directories. |
| `jgit-storage-hibernate-search` | Optional commit/history projections and full-text search over messages, paths and content. | Applications that want searchable generic Git history. |
| `jgit-storage-hibernate-java-analysis` | Optional JDT analysis with binding-aware Java symbol/reference projections. | Applications that want semantic Java search without Eclipse UI, cleanup or quickfix dependencies. |
| `jgit-storage-hibernate-benchmarks` | JMH benchmarks for core repository operations. | CI, maintainers and release reviewers; not a runtime dependency. |

The split is intentional. Core consumers do not carry Lucene, JDT Core, mining or DSL dependencies.

## Current capabilities

- Open or create a JGit repository backed by Hibernate-managed database tables.
- Persist Git pack data and reftable data in relational databases.
- Keep newly written pack extensions hidden until JGit commits the pack.
- Persist and read queryable reflog entries.
- Support JGit reftable reference updates through the DFS abstraction.
- Index Git commit metadata, paths and text content through Hibernate Search.
- Analyze Java source snapshots with JDT Core.
- Persist binding status, raw/declaration/type binding keys and stable semantic keys.
- Persist Java declarations and references with repository, commit, blob and source positions.
- Record compiler, classpath, sourcepath, modulepath, JDT and analyzer provenance for analysis runs.
- Verify JGit compatibility through a dedicated CI matrix.

## Planned headline features

### Symbol Time Machine

Track a type or method across file moves, renames and signature changes using stable semantic identity rather than path history alone.

### Semantic Diff

Compare commits as structured software changes: added/removed/moved symbols, signature changes, inheritance changes, annotation changes and affected callers.

### Historical Software Graph

Query callers, implementations, type uses, inheritance and dependencies at any commit, then compare graph deltas between versions.

### Semantic Search Across History

Search by resolved method/type identity instead of matching only names or text.

### Architecture Drift Detection

Compare code-level dependency graphs with versioned architecture DSL constraints and evidence.

### Refactoring Pattern Mining

Discover recurring semantic transformations, persist deterministic candidates and promote only validated/tested rules.

## Design stance

- Git data is authoritative; semantic indexes are rebuildable projections.
- The project is not a fork of JGit and is not affiliated with the Eclipse Foundation.
- JGit and JDT internal implementation types must not leak into public APIs.
- Binding context and incomplete/recovered resolution are persisted explicitly.
- Java analysis is optional and uses own stable DTOs/entities.
- Java 21 is the project baseline.
- The project is BSD-3-Clause licensed.

## Consuming

See [docs/consuming.md](docs/consuming.md) for Maven repository setup and dependency snippets.

Core dependency:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-core</artifactId>
  <version>0.1.3-SNAPSHOT</version>
</dependency>
```

Optional generic search dependency:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-search</artifactId>
  <version>0.1.3-SNAPSHOT</version>
</dependency>
```

Optional Java analysis dependency:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-java-analysis</artifactId>
  <version>0.1.3-SNAPSHOT</version>
</dependency>
```

## Basic storage usage

```java
Properties properties = new Properties();
properties.put("hibernate.connection.url", "jdbc:h2:mem:demo;DB_CLOSE_DELAY=-1");
properties.put("hibernate.connection.driver_class", "org.h2.Driver");
properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
properties.put("hibernate.hbm2ddl.auto", "update");

try (HibernateSessionFactoryProvider provider = new HibernateSessionFactoryProvider(properties)) {
    HibernateRepositoryFactory factory =
        new DefaultHibernateRepositoryFactory(provider.getSessionFactory());

    try (HibernateGitStorage storage = factory.open(new RepositoryName("demo"))) {
        Repository repository = storage.repository();
        // Use standard JGit APIs here.
    }
}
```

Register optional projection entities when creating the session factory:

```java
SearchEntities.annotatedClasses();
JavaAnalysisEntities.annotatedClasses();
```

## Documentation

- [Consuming the modules](docs/consuming.md)
- [JGit compatibility guardrails](docs/jgit-compatibility.md)
- [Semantic software history roadmap](docs/semantic-history-roadmap.md)
- [Release process](docs/release-process.md)
- [Citation metadata](CITATION.md)

## Expected consumers

- Sandbox
- Taxonomy
- Audio Analyzer / Audioprocessor
- Other applications that need database-backed Git semantics or queryable semantic history

## Non-goals

- General Git hosting server functionality.
- Replacing JGit.
- Embedding application-specific Sandbox or Taxonomy UI/domain logic.
- Exposing JGit/JDT internal package types.
- Putting Eclipse cleanup/quickfix implementation code into the Java analysis module.

## License

BSD-3-Clause. See [LICENSE](LICENSE).
