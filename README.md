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

`jgit-storage-hibernate` combines database-backed JGit storage with rebuildable search and language-aware projections. Git remains authoritative; Hibernate, Hibernate Search and JDT make commits, Java symbols, references and software relations queryable.

The project now contains two vertical semantic-history layers:

1. project-wide binding-aware Java analysis and semantic diff,
2. symbol timelines plus a versioned call/type/inheritance/override graph.

This enables questions such as:

```text
Where did this method move and how did its signature change?
Which methods call the changed declaration at this commit?
Which implementations and overrides depend on this interface?
Which graph relations were added or removed between releases?
What is the transitive impact radius of a changed method or type?
```

## Implemented semantic-history capabilities

- binding-aware project snapshots and shared multi-file JDT analysis,
- Maven module/source/dependency context with explicit unresolved diagnostics,
- persisted analysis provenance, binding quality and projection lifecycle,
- semantic declaration matching and structured commit-to-commit changes,
- **Symbol Time Machine** across ordered commits,
- stable logical symbol tracks across moves and compatible signature changes,
- versioned graph edges for calls, construction, field/type use, annotations, inheritance, implementation and overrides,
- method-level source attribution for call edges,
- incoming/outgoing relation queries,
- bounded transitive impact analysis,
- graph deltas between commits,
- searchable persisted `JavaGraphEdgeIndex` projections,
- public APIs without exposing JDT or Hibernate Search internals.

## Core APIs

```java
JavaProjectAnalysisResult before =
    new JavaProjectAnalyzer().analyze(beforeSnapshot, configuration);
JavaProjectAnalysisResult after =
    new JavaProjectAnalyzer().analyze(afterSnapshot, configuration);

List<SemanticChange> changes = new JavaSemanticDiff().compare(before, after);

List<SymbolTimeline> timelines =
    new SymbolTimeMachine().build(List.of(before, after));

JavaSoftwareGraph beforeGraph = JavaSoftwareGraph.from(before);
JavaSoftwareGraph afterGraph = JavaSoftwareGraph.from(after);
JavaGraphDelta graphDelta = JavaGraphDelta.between(beforeGraph, afterGraph);

Set<String> impacted = beforeGraph.transitiveImpact(changedSemanticKey, 3);
```

`SymbolTimelineEntry` keeps the commit, concrete symbol projection and semantic changes from the previous occurrence. `JavaGraphEdge` keeps repository/commit provenance, source location and binding quality.

## Architecture

```text
Git / JGit Repository API
  -> jgit-storage-hibernate-core
       -> Hibernate-backed DFS/Reftable storage

  -> jgit-storage-hibernate-search
       -> generic commit/blob/path/full-text projections

  -> jgit-storage-hibernate-java-analysis
       -> JavaProjectAnalyzer / JDT bindings
       -> JavaSemanticDiff
       -> SymbolTimeMachine
       -> JavaSoftwareGraph
       -> JavaGraphDelta / impact analysis
       -> JavaSymbolIndex / JavaReferenceIndex / JavaGraphEdgeIndex

  -> jgit-storage-hibernate-benchmarks
```

## Graph semantics

The graph currently represents:

- `CALLS`
- `CONSTRUCTS`
- `READS_FIELD`
- `REFERENCES_TYPE`
- `ANNOTATED_WITH`
- `EXTENDS`
- `IMPLEMENTS`
- `OVERRIDES`

Call and reference edges are resolved to the narrowest enclosing indexed symbol, normally the containing method. Hierarchy edges use indexed type identities and preserve partial binding quality when a parent type is external or unresolved.

## Hibernate entity registration

```java
List<Class<?>> projectionEntities = new ArrayList<>();
projectionEntities.addAll(SearchEntities.annotatedClasses());
projectionEntities.addAll(JavaAnalysisEntities.annotatedClasses());

try (HibernateSessionFactoryProvider provider =
    new HibernateSessionFactoryProvider(properties, projectionEntities)) {
  // JavaGraphEdgeIndex is included in JavaAnalysisEntities.
}
```

## Modules

| Module | Purpose |
|---|---|
| `jgit-storage-hibernate-core` | Database-backed JGit packs, refs, reftables and reflogs. |
| `jgit-storage-hibernate-search` | Generic commit/history and full-text projections. |
| `jgit-storage-hibernate-java-analysis` | Binding-aware analysis, semantic diff, timelines, graph and impact analysis. |
| `jgit-storage-hibernate-benchmarks` | JMH benchmarks; not a runtime dependency. |

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

See [docs/consuming.md](docs/consuming.md) and the [semantic software history roadmap](docs/semantic-history-roadmap.md).

## Reference consumers and source concepts

- **Sandbox:** Java diff/mining workflows, deterministic candidates and staged validation.
- **Taxonomy:** structured DSL history, semantic diff, evidence and projection-staleness concepts.
- **Audio Analyzer / Audioprocessor:** versioned domain-model consumer.

Application-specific UI and domain logic remain in those repositories.

## License

BSD-3-Clause. See [LICENSE](LICENSE).
