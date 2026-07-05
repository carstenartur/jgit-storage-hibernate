# ADR-0001: Project positioning, license and module split

## Status

Accepted

## Context

The project `jgit-storage-hibernate` is intended to consolidate Hibernate-backed JGit storage work into a reusable infrastructure module.

The immediate consumers are expected to be Audio Analyzer / Audioprocessor, Taxonomy and Sandbox. These applications need Git semantics backed by database transactions and optional searchable history projections, but they should not duplicate storage implementation code or depend directly on JGit internal packages.

JGit uses the Eclipse Distribution License v1.0 / BSD-3-Clause-compatible licensing model. The existing Hibernate storage experiments were developed in a JGit fork and should remain close to that licensing style.

The source projects separate naturally into reusable storage, optional search/indexing and application-specific behavior:

- the JGit fork provides the reusable DFS/Reftable/Hibernate storage backend;
- Sandbox adds indexing/search/server experiments;
- Taxonomy validates application-level DSL versioning, branch, diff, merge, cherry-pick, staleness and searchable history workflows.

## Decision

The project is an independent infrastructure module, not a JGit fork and not an Eclipse project.

The project uses the BSD-3-Clause license.

The project is a multi-module Maven project:

```text
jgit-storage-hibernate-core
jgit-storage-hibernate-search
```

The project description is:

```text
Hibernate-backed storage backend for JGit repositories, persisting Git packs, refs, reflogs and searchable history in relational databases.
```

The implementation must encapsulate JGit DFS/Reftable internals behind a stable public API. Consuming projects must not import `org.eclipse.jgit.internal.*` packages directly.

`jgit-storage-hibernate-core` contains the release-critical repository storage backend.

`jgit-storage-hibernate-search` contains optional generic commit/history projections based on Hibernate Search. Java-specific AST extraction, embedding search and REST server endpoints are deferred to future optional modules.

## Consequences

- `audio-analyzer`, Taxonomy and Sandbox can depend on this module instead of copying storage code.
- Core consumers do not need Lucene, Hibernate Search, JDT or server dependencies.
- Search consumers can opt into `jgit-storage-hibernate-search` when they need indexed history.
- JGit internal API usage is allowed only inside implementation packages of this repository.
- The project keeps a permissive license compatible with JGit-oriented infrastructure work.
- Any code copied or adapted from JGit or the existing fork must preserve its original copyright and license notices.
