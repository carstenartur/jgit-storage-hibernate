# Semantic software history roadmap

`jgit-storage-hibernate` can evolve from a database-backed JGit storage implementation into a reusable semantic history engine: Git objects remain the source of truth, while language- and architecture-aware projections make software evolution queryable.

The roadmap deliberately reuses concepts already proven in the author's related projects:

- **Sandbox** provides JGit commit traversal, Java diff extraction, refactoring-mining pipelines, deterministic candidate identities, staged validation and promotion workflows.
- **Taxonomy** provides structured DSL history, semantic diff, branch comparison, merge/cherry-pick previews, evidence links and projection staleness tracking.

Application-specific UI, Eclipse cleanup code and Taxonomy domain models stay in their original projects. Only reusable history, projection, query and analysis concepts belong here.

## Product vision

> Turn Git from a text archive into a queryable semantic history and software knowledge graph.

The differentiator is not merely storing Git in a relational database. The differentiator is making stable software entities and their evolution directly addressable:

- types, methods, fields and references across commits,
- semantic changes rather than line changes,
- call, inheritance and dependency graphs over time,
- architecture elements and relations stored through pluggable DSL projections,
- evidence and provenance linking every derived fact to repository, commit, blob, analyzer version and binding context.

## Architectural principles

1. **Git remains authoritative.** Semantic tables and indexes are reproducible projections.
2. **Stable identities are first-class.** Files are not enough; methods, types, relations and architecture elements need durable semantic keys.
3. **Binding context is provenance.** Classpath, sourcepath, module path, compiler options, JDT version and analyzer version are persisted with every analysis run.
4. **Incomplete analysis is explicit.** Partial or recovered bindings are queryable states, not silent degradation.
5. **Projection lifecycle is observable.** Every branch and commit can report whether projections are current, stale, incomplete or failed.
6. **Language and DSL analyzers are optional modules.** Core storage does not depend on JDT, architecture DSLs or application UI.
7. **Derived facts carry evidence.** A query result must be traceable back to source positions, blobs, commits and analyzer runs.

## Target module structure

```text
jgit-storage-hibernate-core
  Git object, ref and reflog persistence

jgit-storage-hibernate-search
  generic commit/blob/path/full-text projections

jgit-storage-hibernate-analysis-spi
  language-neutral projection and lifecycle contracts

jgit-storage-hibernate-java-analysis
  JDT binding-aware declarations, references and semantic changes

jgit-storage-hibernate-semantic-diff
  stable-identity matching and structured change events

jgit-storage-hibernate-graph
  versioned symbol, call, inheritance and dependency graph projections

jgit-storage-hibernate-dsl-analysis
  generic adapter contracts for structured DSL projections
```

The module split should be introduced only as functionality justifies it. The current Java analysis module can host the first implementation while APIs stabilize.

## Roadmap

### Phase 1 — Reliable semantic projection foundation

- Persist project-wide analysis runs rather than isolated source-file runs.
- Resolve Maven/Gradle/JDK classpaths for a specific commit.
- Batch-parse source files with JDT so bindings can resolve across compilation units.
- Store complete symbol ownership, nesting, generic signatures and declaration/reference links.
- Add branch/commit projection status and idempotent incremental reindexing.
- Add query APIs for symbols and references.

**Outcome:** users can query Java declarations and usages at a commit with known analysis quality.

### Phase 2 — Semantic history and symbol time machine

- Match symbols across parent/child commits using binding keys, stable semantic keys and structural fingerprints.
- Persist semantic change events: add, remove, rename, move, signature change, visibility change, inheritance change, annotation change and body change.
- Build a symbol timeline API independent of file renames.
- Add semantic commit summaries and impact counts.

**Outcome:** users can ask how a method, type or API evolved instead of reading file diffs.

### Phase 3 — Versioned software graph

- Persist method invocation, constructor, field access, type use, inheritance, implementation and annotation edges.
- Resolve edges to declarations where possible and preserve unresolved targets explicitly.
- Add graph queries at a commit and graph-delta queries between commits.
- Compute impact analysis, callers, implementations, dependency cycles and architectural boundary violations.

**Outcome:** Git history becomes a time-versioned software knowledge graph.

### Phase 4 — Mining and candidate lifecycle

Adapt the reusable workflow concepts from Sandbox:

- deterministic candidate identity,
- source commit/repository provenance,
- discovered → validated → tested → ready lifecycle,
- negative examples and evidence,
- explicit promotion rather than direct mutation.

Candidates may represent recurring semantic changes, refactoring patterns, migration rules, architectural violations or suggested DSL rules.

**Outcome:** expensive mining runs become reproducible, reviewable and incrementally improvable.

### Phase 5 — Structured DSL and architecture history

Adapt the reusable concepts from Taxonomy without importing its domain model:

- analyzer SPI for text-to-structured-projection conversion,
- stable element/relation identity,
- structured semantic diff,
- evidence/provenance objects,
- projection staleness tracking,
- merge/cherry-pick preview based on semantic conflicts.

Taxonomy can implement the SPI for its architecture DSL while other consumers can provide different DSL analyzers.

**Outcome:** the same infrastructure supports both programming-language history and architecture-model history.

### Phase 6 — Query language and demonstrator

Provide a small query layer over stable projections. It may start as a fluent Java API and REST representation before introducing a dedicated DSL.

Representative queries:

```text
methods where returnType changed to java.util.Optional
and callerCount > 20
between v1.0 and main
```

```text
types that stopped implementing java.io.Serializable
on branch main
```

```text
architecture relations added without accepted evidence
since commit abc123
```

```text
semantic changes matching the same before/after pattern
in at least 10 repositories
```

**Outcome:** a public demo can communicate the project's value in seconds.

## Highest-attention demonstrators

The following features should be prioritized for visibility because they are easy to explain and difficult to reproduce with ordinary Git tooling:

1. **Symbol Time Machine** — enter a fully qualified method/type and see its history across moves and renames.
2. **Semantic Diff** — compare commits and show API/structure changes with affected callers.
3. **Historical Call Graph** — inspect callers and dependencies at any commit and compare graph changes.
4. **Semantic Search Across History** — query declarations/usages by resolved type and method identity.
5. **Architecture Drift Detection** — compare code-level dependency graphs with versioned architecture DSL constraints.
6. **Refactoring Pattern Mining** — discover repeated semantic transformations and stage reviewable candidates.

## Documentation deliverables

The project documentation should grow around executable use cases rather than implementation internals:

- a five-minute quick start using H2 and a small demo repository,
- screenshots or generated diagrams for symbol history, semantic diff and graph queries,
- a query cookbook with concrete questions and expected output,
- architecture and projection-lifecycle diagrams,
- supported JGit/JDT/Java compatibility tables,
- benchmark results comparing repeated history analysis with and without persisted projections,
- clear distinction between authoritative Git data and rebuildable projections,
- links to Sandbox and Taxonomy as reference consumers and upstream sources of proven concepts.

## Success criteria

The roadmap has succeeded when a new visitor can run one command and answer a question that normal Git cannot answer directly, such as:

> Which methods changed signature in this release, which callers are affected, and where did those methods move during their history?
