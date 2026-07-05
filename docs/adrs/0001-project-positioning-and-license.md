# ADR-0001: Project positioning and license

## Status

Accepted

## Context

The project `jgit-storage-hibernate` is intended to consolidate Hibernate-backed JGit storage work into a reusable infrastructure module.

The immediate consumers are expected to be Audio Analyzer / Audioprocessor, Taxonomy and Sandbox. These applications need Git semantics backed by database transactions and optional searchable history projections, but they should not duplicate storage implementation code or depend directly on JGit internal packages.

JGit uses the Eclipse Distribution License v1.0 / BSD-3-Clause-compatible licensing model. The existing Hibernate storage experiments were developed in a JGit fork and should remain close to that licensing style.

## Decision

The project is an independent infrastructure module, not a JGit fork and not an Eclipse project.

The project uses the BSD-3-Clause license.

The project description is:

```text
Hibernate-backed storage backend for JGit repositories, persisting Git packs, refs, reflogs and searchable history in relational databases.
```

The implementation must encapsulate JGit DFS/Reftable internals behind a stable public API. Consuming projects must not import `org.eclipse.jgit.internal.*` packages directly.

## Consequences

- `audio-analyzer`, Taxonomy and Sandbox can depend on this module instead of copying storage code.
- JGit internal API usage is allowed only inside implementation packages of this repository.
- The project keeps a permissive license compatible with JGit-oriented infrastructure work.
- Any code copied or adapted from JGit or the existing fork must preserve its original copyright and license notices.
