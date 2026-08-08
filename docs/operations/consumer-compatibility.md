# Consumer compatibility

`jgit-storage-hibernate` is not developed in isolation. Its current production and integration surface is exercised by three repositories with different dependency, runtime and performance constraints:

| Consumer | Primary contract | Library areas that matter most |
|---|---|---|
| `audio-analyzer` | Embeddable runtime and stable packaged application | Core storage, the explicitly selected optional modules, binary/runtime linkage and release artifacts |
| `Taxonomy` | Transactional multi-repository application with indexed history | Core transactions and refs, PostgreSQL, Flyway, Hibernate Search, rebuild/restart and bounded queries |
| `sandbox` | Eclipse/Tycho consumer and Java tooling | Java 21, Java Analysis APIs, OSGi/Tycho resolution, compile-time footprint and feature packaging |

A change is not considered compatible merely because the `jgit-storage-hibernate` reactor and its local benchmarks pass. Relevant pull requests are also tested against fresh checkouts of these consumers.

## Exact-current-artifact testing

The `Consumer Compatibility` workflow builds the current commit once into an isolated Maven repository. Each consumer job then:

1. checks out the consumer's current default branch;
2. finds direct and dependency-managed `io.github.carstenartur:jgit-storage-hibernate-*` coordinates;
3. substitutes the exact current reactor version, including property-backed versions;
4. resolves a dependency tree from the isolated repository;
5. runs the consumer-specific build or integration contract;
6. retains the POM patch, consumed modules, dependency tree, logs and test reports.

No compatibility run commits changes to a consumer repository. A missing dependency or a version that cannot be substituted deterministically is a failure rather than a silent skip.

## audio-analyzer

The audio application must remain able to embed only the modules it deliberately selected. Its gate therefore emphasizes:

- compilation and packaging against the current artifacts;
- unit/runtime linkage instead of dependency resolution alone;
- absence of benchmark/test artifacts from the runtime tree;
- stable Core and optional Search APIs across released and development lines;
- bounded memory and transaction behavior for repositories that may coexist with large audio-processing workloads.

Performance work must not move audio payloads into Search indexes or add heavyweight transitive runtime dependencies merely to improve a benchmark that the application does not use.

## Taxonomy

Taxonomy is the strongest end-to-end consumer of database-backed Git plus indexed history. Its gate emphasizes:

- PostgreSQL startup and schema migration;
- repository creation and isolation;
- transactional Git object/ref publication;
- Hibernate Search mapping, indexing, query and rebuild behavior;
- restart with persistent relational and Search state;
- bounded query, purge and rebuild work as histories grow.

Changes to Flyway migrations, Search identifiers, analyzers, path fields, repository naming or transaction boundaries require Taxonomy evidence before merge. Search optimizations remain derived-state optimizations: authoritative Git persistence and ref visibility must not become dependent on Search success.

## sandbox

Sandbox represents the Java-tooling and Eclipse packaging boundary. Its gate emphasizes:

- Java 21 source and binary compatibility;
- Java Analysis API linkage;
- Tycho and OSGi resolution;
- installable feature/plugin packaging;
- avoidance of accidental UI, Spring application, benchmark or database-driver implementation dependencies.

A Core or Java Analysis refactoring that is source-compatible in a plain Maven test may still be incompatible with OSGi metadata or the Eclipse target platform; the sandbox contract exists to expose exactly that class of regression.

## Relationship to performance evidence

Consumer gates answer **whether a measured optimization remains usable**. The benchmark dashboard answers **whether it improves the measured workload**. Both are required for behavior-changing defaults.

Examples:

- Hibernate Search batching and lightweight projections need the Search charts and the Taxonomy gate.
- Java-analysis indexing changes need dedicated performance evidence and the sandbox Tycho contract.
- Core pack/ref/reflog optimizations need the database/JGit benchmarks plus audio-analyzer and Taxonomy runtime contracts.
- A new dependency or backend may be faster but is not acceptable when it leaks into consumers that do not select it.

The current performance pages remain the detailed source for chart interpretation:

- [Hibernate Search performance](hibernate-search-performance.md)
- [Reverse-reflog performance](reflog-performance.md)

## Merge and release policy

A consumer failure is handled according to ownership of the regression:

- When the consumer default branch is already failing with its declared release, the evidence records a baseline-consumer failure and the library change is not blamed automatically.
- When the declared release builds but the substituted current reactor fails, the library pull request must be corrected or explicitly versioned as a breaking change.
- Consumer-specific optional behavior may be guarded by a feature/configuration boundary; it must not be forced onto all three applications.
- A release intended for DOI publication should include a successful consumer matrix for the exact release commit in addition to the retained performance charts and ordinary repository gates.
