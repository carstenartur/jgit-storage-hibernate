# ADR-0002: Split core storage and optional search projections

## Status

Accepted

## Context

The existing sources show two different concern groups:

1. Core JGit storage: repository creation, object database, pack/reftable persistence, ref handling and reflog rows.
2. Search/indexing: Hibernate Search projections, file type strategies, Java-aware indexing, query services and possible embeddings.

The Sandbox project already contains a dedicated `sandbox-jgit-storage-hibernate` module with both storage and search dependencies. Taxonomy consumes database-backed JGit semantics in its application layer and combines them with Hibernate Search/Lucene and DSL-specific services. Audio Analyzer should consume the storage backend without pulling application-specific DSL logic or heavyweight search dependencies into its core runtime.

## Decision

Use a multi-module Maven structure:

```text
jgit-storage-hibernate-parent
  ├─ jgit-storage-hibernate-core
  └─ jgit-storage-hibernate-search
```

`jgit-storage-hibernate-core` contains the stable storage facade and the Hibernate-backed JGit implementation. It depends on JGit and Hibernate ORM.

`jgit-storage-hibernate-search` depends on the core module and contains optional Hibernate Search projections and query APIs.

JDT/ECJ-based Java indexing and embedding support should remain out of the core module. They may be added later either to `jgit-storage-hibernate-search` or to more specific optional modules if the dependency weight justifies it.

## Consequences

- Consumers that only need Git semantics can depend on the core artifact.
- Consumers that need searchable history can opt into the search artifact.
- Heavy dependencies remain optional and can evolve independently.
- JGit internal API usage remains contained in the core implementation packages.
- The project can publish releaseable artifacts with clear dependency boundaries.
