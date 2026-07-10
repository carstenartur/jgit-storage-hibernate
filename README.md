# jgit-storage-hibernate

[![Java CI with Maven](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/maven.yml/badge.svg)](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/maven.yml)
[![Coverage](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/main/docs/badges/coverage.json)](docs/badges/coverage.json)
[![Tests](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/main/docs/badges/tests.json)](docs/badges/tests.json)
[![JMH Benchmarks](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/performance.yml/badge.svg)](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/performance.yml)
[![Publish Snapshot](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/publish-snapshot.yml/badge.svg)](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/publish-snapshot.yml)
[![Release](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/release.yml/badge.svg)](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/release.yml)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue)](pom.xml)
[![License: BSD-3-Clause](https://img.shields.io/badge/License-BSD--3--Clause-blue.svg)](LICENSE)

https://doi.org/10.5281/zenodo.21210132

## Turn Git into a queryable semantic history

`jgit-storage-hibernate` combines database-backed JGit storage with rebuildable search and language-aware projections. Git remains authoritative; Hibernate, Hibernate Search and JDT make commits, Java symbols and references queryable.

The semantic-history MVP can now analyze all Java sources of a commit, resolve sibling compilation units through a shared JDT source path, derive Maven source/dependency context, compare two snapshots by semantic identity and query the resulting declarations and references.

It is designed to answer questions ordinary line-based Git tools cannot answer directly:

```text
Which methods changed signature between two commits?
Where did a declaration move?
Which calls resolve to a given semantic method identity?
Which references could not be resolved because the build context is incomplete?
```

## Implemented semantic-history MVP

- binding-aware Java source and project snapshots,
- project-wide analysis with a shared source tree,
- lightweight Maven POM discovery for modules, source level, source roots and local dependencies,
- explicit unresolved-dependency diagnostics,
- persisted analysis provenance and binding quality,
- persisted projection lifecycle state (`CURRENT`, `PARTIAL`, `STALE`, `FAILED`, ...),
- semantic declaration matching by binding key, stable semantic key and guarded heuristics,
- structured changes including add/remove/move/rename/signature/modifier/annotation/binding-quality changes,
- public query facade without exposing JDT or Hibernate Search types,
- executable `SemanticHistoryDemo`,
- tests for multi-file analysis, Maven context and semantic signature changes.

The next layer is persistent incremental indexing directly from JGit commits, followed by symbol timelines and a versioned call/inheritance/dependency graph. See [Semantic software history roadmap](docs/semantic-history-roadmap.md).

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
       -> JavaProjectSnapshot
       -> MavenJavaAnalysisConfigurationResolver
       -> JavaProjectAnalyzer / JDT bindings
       -> JavaSymbolIndex / JavaReferenceIndex
       -> JavaSemanticDiff / SemanticHistoryQuery
       -> JavaProjectionState

  -> jgit-storage-hibernate-benchmarks
       -> JMH benchmarks
```

## Modules

| Module | Purpose |
|---|---|
| `jgit-storage-hibernate-core` | Database-backed JGit packs, refs, reftables and reflogs. |
| `jgit-storage-hibernate-search` | Generic commit/history and full-text projections. |
| `jgit-storage-hibernate-java-analysis` | Binding-aware project analysis, semantic diff, projection state and query API. |
| `jgit-storage-hibernate-benchmarks` | JMH benchmarks; not a runtime dependency. |

## Run the semantic history demo

Build the project, then run the demo with the Java-analysis module and its dependencies on the classpath. The main class is:

```text
io.github.carstenartur.jgit.storage.hibernate.javaanalysis.demo.SemanticHistoryDemo
```

The demo creates two small project snapshots, analyzes both and prints structured changes such as moved declarations and signature changes.

Core API flow:

```java
JavaProjectAnalysisResult before =
    new JavaProjectAnalyzer().analyze(beforeSnapshot, configuration);
JavaProjectAnalysisResult after =
    new JavaProjectAnalyzer().analyze(afterSnapshot, configuration);

List<SemanticChange> changes = new JavaSemanticDiff().compare(before, after);
List<JavaReferenceIndex> calls =
    new SemanticHistoryQuery(after).methodInvocationsNamed("save");
```

## Maven build-context resolution

```java
MavenJavaAnalysisConfigurationResolver.Resolution resolution =
    new MavenJavaAnalysisConfigurationResolver().resolve(repositoryFiles);

JavaAnalysisConfiguration configuration = resolution.configuration();
List<String> unresolved = resolution.unresolvedDependencies();
```

The resolver intentionally does not download dependencies. It maps dependencies already present in the local Maven repository and reports missing coordinates explicitly. This keeps indexing deterministic and allows consumers to supply their own artifact-resolution policy.

## Hibernate entity registration

```java
List<Class<?>> projectionEntities = new ArrayList<>();
projectionEntities.addAll(SearchEntities.annotatedClasses());
projectionEntities.addAll(JavaAnalysisEntities.annotatedClasses());

try (HibernateSessionFactoryProvider provider =
    new HibernateSessionFactoryProvider(properties, projectionEntities)) {
  // use storage and projections
}
```

## Design stance

- Git data is authoritative; semantic indexes are rebuildable projections.
- Binding context and incomplete/recovered resolution are persisted explicitly.
- JGit and JDT internal implementation types do not leak into public APIs.
- Java analysis is optional and uses module-owned DTOs/entities.
- Eclipse UI, cleanup and quickfix implementation code stay outside this module.
- Java 21 is the project baseline.
- License: BSD-3-Clause.

## Consuming

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-java-analysis</artifactId>
  <version>0.1.3-SNAPSHOT</version>
</dependency>
```

See [docs/consuming.md](docs/consuming.md) for repository setup and all module dependencies.

## Documentation

- [Semantic software history roadmap](docs/semantic-history-roadmap.md)
- [Consuming the modules](docs/consuming.md)
- [JGit compatibility guardrails](docs/jgit-compatibility.md)
- [Release process](docs/release-process.md)
- [Citation metadata](CITATION.md)

## Reference consumers and source concepts

- **Sandbox:** Java diff/mining workflows, deterministic candidates and staged validation.
- **Taxonomy:** structured DSL history, semantic diff, evidence and projection-staleness concepts.
- **Audio Analyzer / Audioprocessor:** versioned domain-model consumer.

Application-specific UI and domain logic remain in those repositories.

## License

BSD-3-Clause. See [LICENSE](LICENSE).
