# jgit-storage-hibernate

[![Java CI with Maven](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/maven.yml/badge.svg)](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/maven.yml)
[![Coverage](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/main/docs/badges/coverage.json)](docs/badges/coverage.json)
[![Tests](https://img.shields.io/endpoint?url=https://raw.githubusercontent.com/carstenartur/jgit-storage-hibernate/main/docs/badges/tests.json)](docs/badges/tests.json)
[![JMH Benchmarks](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/performance.yml/badge.svg)](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/performance.yml)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue)](pom.xml)
[![License: BSD-3-Clause](https://img.shields.io/badge/License-BSD--3--Clause-blue.svg)](LICENSE)

https://doi.org/10.5281/zenodo.21210132

## Turn Git into a queryable semantic and architectural history

`jgit-storage-hibernate` combines database-backed JGit storage with rebuildable semantic projections. Git remains authoritative; Hibernate, Hibernate Search and JDT make commits, Java symbols, software relations and architecture intent queryable.

The project now contains three vertical layers:

1. project-wide binding-aware Java analysis and semantic diff,
2. symbol timelines plus a versioned call/type/inheritance/override graph,
3. versioned architecture DSLs, evidence, constraints and code-to-architecture drift analysis.

It can answer questions such as:

```text
Where did this method move and how did its signature change?
Which callers and implementations are affected by this change?
Which architecture rules changed between two commits?
Which observed code dependencies violate the architecture at this commit?
Which decisions justify a rule, and is that evidence still current?
```

## Implemented capabilities

- binding-aware project snapshots and multi-file JDT analysis,
- Maven module/source/dependency context with explicit unresolved diagnostics,
- semantic declaration diff and Symbol Time Machine,
- versioned software graph and transitive impact analysis,
- language-neutral `ArchitectureDslParser` SPI,
- stable architecture elements, relations, rules and evidence,
- semantic architecture diff by stable IDs,
- code-to-architecture mapping through versioned selectors,
- `ALLOW`, `FORBID` and `REQUIRE` architecture constraints,
- deterministic drift findings with rule, element, code location and evidence provenance,
- searchable projections for Java graph edges, architecture rules, evidence and drift findings.

## Architecture drift example

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

Findings include forbidden observed relations, missing required relations, unmapped or ambiguously mapped symbols, missing evidence and stale evidence.

## Semantic history APIs

```java
JavaProjectAnalysisResult before =
    new JavaProjectAnalyzer().analyze(beforeSnapshot, configuration);
JavaProjectAnalysisResult after =
    new JavaProjectAnalyzer().analyze(afterSnapshot, configuration);

List<SemanticChange> codeChanges = new JavaSemanticDiff().compare(before, after);
List<SymbolTimeline> timelines =
    new SymbolTimeMachine().build(List.of(before, after));

JavaSoftwareGraph beforeGraph = JavaSoftwareGraph.from(before);
JavaSoftwareGraph afterGraph = JavaSoftwareGraph.from(after);
JavaGraphDelta graphDelta = JavaGraphDelta.between(beforeGraph, afterGraph);

List<ArchitectureChange> architectureChanges =
    new ArchitectureSemanticDiff().compare(oldArchitecture, newArchitecture);
```

## Module architecture

```text
Git / JGit Repository API
  -> jgit-storage-hibernate-core
       -> Hibernate-backed DFS/Reftable storage

  -> jgit-storage-hibernate-search
       -> generic commit/blob/path/full-text projections

  -> jgit-storage-hibernate-java-analysis
       -> JDT bindings, semantic diff, timelines and software graph

  -> jgit-storage-hibernate-architecture
       -> DSL parser SPI
       -> architecture elements/relations/rules/evidence
       -> semantic DSL diff
       -> code mapping and drift engine

  -> jgit-storage-hibernate-benchmarks
```

## Architecture mapping selectors

Architecture elements can select code using versioned attributes:

- `codePattern`: regular expression against stable semantic keys,
- `packagePrefix`: Java package prefix,
- `pathPrefix`: repository source-path prefix.

Exactly one match is expected. No match and multiple matches are visible drift findings rather than silent guesses.

## Evidence and provenance

Evidence can point to ADRs, requirements, tickets, source locations or external records. Each item carries repository, commit, path, optional line, rationale, confidence and extensible attributes. Rules may require evidence by ID. `validThroughCommit` can mark evidence that needs reassessment after the code graph advances.

## Hibernate entity registration

```java
List<Class<?>> projectionEntities = new ArrayList<>();
projectionEntities.addAll(SearchEntities.annotatedClasses());
projectionEntities.addAll(JavaAnalysisEntities.annotatedClasses());
projectionEntities.addAll(ArchitectureEntities.annotatedClasses());

try (HibernateSessionFactoryProvider provider =
    new HibernateSessionFactoryProvider(properties, projectionEntities)) {
  // semantic and architecture projections are registered
}
```

## Modules

| Module | Purpose |
|---|---|
| `jgit-storage-hibernate-core` | Database-backed JGit packs, refs, reftables and reflogs. |
| `jgit-storage-hibernate-search` | Generic commit/history and full-text projections. |
| `jgit-storage-hibernate-java-analysis` | Binding-aware analysis, semantic diff, timelines, graph and impact analysis. |
| `jgit-storage-hibernate-architecture` | Versioned DSL SPI, evidence, architecture constraints and drift analysis. |
| `jgit-storage-hibernate-benchmarks` | JMH benchmarks; not a runtime dependency. |

## Consuming

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-architecture</artifactId>
  <version>0.1.4</version>
</dependency>
```

See [docs/consuming.md](docs/consuming.md), the [architecture module guide](jgit-storage-hibernate-architecture/README.md) and the [semantic software history roadmap](docs/semantic-history-roadmap.md).

## Design stance

- Git data is authoritative; all semantic indexes are rebuildable projections.
- DSL adapters produce neutral models; Taxonomy-specific domain classes remain in Taxonomy.
- Binding quality, mapping ambiguity and incomplete evidence are explicit.
- JGit, JDT and Hibernate Search implementation types do not leak into public APIs.
- Java 21 is the project baseline.
- License: BSD-3-Clause.

## License

BSD-3-Clause. See [LICENSE](LICENSE).
