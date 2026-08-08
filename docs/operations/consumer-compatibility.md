# Consumer compatibility

`jgit-storage-hibernate` is not developed in isolation. Its current production and
integration surface is exercised by three repositories with different dependency,
runtime and performance constraints:

| Consumer | Primary contract | Library areas that matter most |
|---|---|---|
| `audio-analyzer` | Embeddable runtime and stable packaged application | Core storage, explicitly selected optional modules, binary/runtime linkage and release artifacts |
| `Taxonomy` | Transactional multi-repository application with indexed history | Core transactions and refs, PostgreSQL, Flyway, Hibernate Search, rebuild/restart and bounded queries |
| `sandbox` | Eclipse/Tycho consumer and Java tooling | Java 21, Java Analysis APIs, OSGi/Tycho resolution, compile-time footprint and feature packaging |

A change is not considered compatible merely because the `jgit-storage-hibernate`
reactor and its local benchmarks pass. Relevant pull requests are also tested against
fresh checkouts of these consumers.

## Exact-current-artifact testing

The `Consumer Compatibility` workflow turns the current library commit into a
commit-qualified Maven version:

```text
<reactor-version>-consumer-<12-character-commit>-SNAPSHOT
```

That version cannot be confused with an unrelated remote snapshot. The workflow builds
it once in an isolated Maven repository and copies only
`io.github.carstenartur:jgit-storage-hibernate-*` artifacts into the three consumer
jobs.

Each consumer job then:

1. checks out the consumer's current default branch and records the exact commit;
2. finds direct and dependency-managed `io.github.carstenartur:jgit-storage-hibernate-*`
   coordinates;
3. substitutes only those literal versions or an exclusive property used by those
   coordinates;
4. rejects project identity placeholders such as `revision`, `sha1` and `changelist`;
5. rejects a version property shared with an unrelated dependency or plugin;
6. verifies from the Maven dependency tree that every resolved library module uses the
   commit-qualified candidate;
7. runs a repository-owned contract when available, otherwise the documented bounded
   fallback;
8. retains the POM patch, consumed modules, dependency tree, commits, logs and test
   reports.

No compatibility run commits changes to a consumer repository. A missing dependency,
an ambiguous property, a non-POM modification or an unresolved candidate version is a
failure rather than a silent skip.

## Failure attribution

Successful candidate runs do not repeat an expensive baseline build. When the candidate
fails, the job restores the unchanged consumer checkout, cleans generated output and runs
the same contract against the consumer's declared release:

| Candidate | Unchanged baseline | Classification |
|---|---|---|
| passes | not run | `candidate-compatible` |
| fails | passes | `candidate-regression` |
| fails | fails | `consumer-baseline-failure` |

Both failure classifications fail the release gate because the candidate has not been
validated. The distinction prevents an already broken consumer default branch from being
misreported as a newly introduced library regression.

The final non-matrix check, `Consumer compatibility gate`, succeeds only when the exact
library build and all three consumer jobs succeed. It is the stable required-check name
for branch protection and release automation.

## Permanent tooling and module boundaries

The workflow validates its own scripts before building consumers and refuses to run with
a `temporary-*.yml` repair workflow still present. It also derives the published module
graph from the reactor POMs and enforces this layering:

```text
Core
  ↑
Search
  ↑
Java Analysis
  ↑
Architecture

Benchmarks = development-only
```

Published modules may not depend upward, create a cycle, force a non-optional JDBC driver
from Core, depend on the benchmark artifact at runtime or pull Spring Boot application
artifacts into the library surface.

## audio-analyzer

The audio application must remain able to embed only the modules it deliberately
selected. Its gate therefore emphasizes:

- compilation and packaging against the current artifacts;
- unit/runtime linkage instead of dependency resolution alone;
- absence of benchmark/test artifacts from the runtime tree;
- stable Core and optional Search APIs across released and development lines;
- bounded memory and transaction behavior for repositories that may coexist with large
  audio-processing workloads.

Performance work must not move audio payloads into Search indexes or add heavyweight
transitive runtime dependencies merely to improve a benchmark that the application does
not use.

## Taxonomy

Taxonomy is the strongest end-to-end consumer of database-backed Git plus indexed
history. Its gate emphasizes:

- PostgreSQL startup and schema migration;
- repository creation and isolation;
- transactional Git object/ref publication;
- Hibernate Search mapping, indexing, query and rebuild behavior;
- restart with persistent relational and Search state;
- bounded query, purge and rebuild work as histories grow.

Changes to Flyway migrations, Search identifiers, analyzers, path fields, repository
naming or transaction boundaries require Taxonomy evidence before merge. Search
optimizations remain derived-state optimizations: authoritative Git persistence and ref
visibility must not become dependent on Search success.

## sandbox

Sandbox represents the Java-tooling and Eclipse packaging boundary. Its gate emphasizes:

- Java 21 source and binary compatibility;
- Java Analysis API linkage;
- Tycho and OSGi resolution;
- installable feature/plugin packaging;
- avoidance of accidental UI, Spring application, benchmark or database-driver
  implementation dependencies.

A Core or Java Analysis refactoring that is source-compatible in a plain Maven test may
still be incompatible with OSGi metadata or the Eclipse target platform; the sandbox
contract exists to expose exactly that class of regression.

## Repository-owned contracts

A consumer may provide an executable file at:

```text
.github/jgit-storage-hibernate-contract.sh
```

The central workflow passes these environment variables:

```text
JGIT_STORAGE_HIBERNATE_CONSUMER
JGIT_STORAGE_HIBERNATE_CONTRACT_MODE
JGIT_STORAGE_HIBERNATE_CANDIDATE_VERSION
MAVEN_REPO_LOCAL
```

Repository-owned scripts must use the supplied Maven repository for candidate mode and
must not modify or publish consumer source. Until such a script is available on the
default branch, the central fallback remains explicit and consumer-specific.

## Relationship to performance evidence

Consumer gates answer **whether a measured optimization remains usable**. The benchmark
dashboard answers **whether it improves the measured workload**. Both are required for
behavior-changing defaults.

Examples:

- Hibernate Search batching and lightweight projections need the Search charts and the
  Taxonomy gate.
- Java-analysis indexing changes need dedicated performance evidence and the sandbox
  Tycho contract.
- Core pack/ref/reflog optimizations need the database/JGit benchmarks plus
  audio-analyzer and Taxonomy runtime contracts.
- A new dependency or backend may be faster but is not acceptable when it leaks into
  consumers that do not select it.

The detailed performance sources remain:

- [Hibernate Search performance](hibernate-search-performance.md)
- [Reverse-reflog performance](reflog-performance.md)

## Merge and release policy

- `candidate-regression` blocks the library pull request until corrected or intentionally
  versioned as a breaking change.
- `consumer-baseline-failure` blocks release evidence and must be repaired in the
  consumer or pinned to a known-good reviewed commit before release.
- Consumer-specific optional behavior may be guarded by a feature/configuration boundary;
  it must not be forced onto all three applications.
- A release intended for DOI publication includes a successful consumer matrix for the
  exact release commit in addition to retained performance charts and ordinary repository
  gates.
