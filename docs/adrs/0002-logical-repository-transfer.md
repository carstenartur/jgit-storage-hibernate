# ADR-0002: Transfer Git ancestry between logical Hibernate repositories

## Status

Accepted for phase 1; incremental transfer remains follow-up work in issue #236.

## Context

A database can contain multiple repositories isolated by `RepositoryName`. Consumers such as Taxonomy need to provision a workspace or fork from a central repository while retaining the original commit IDs, parents, trees, blobs and tags. Creating a new content-equivalent commit is insufficient because the target then has no shared Git ancestor with the source.

The transfer must not expose JGit DFS/Reftable implementation classes, copy application projection tables or make target refs visible before every required object is durable.

Three implementation families were evaluated.

### A. Public JGit object/pack transfer

Resolve selected source refs, walk the reachable Git graph through public JGit APIs, stream objects or a generated pack into the target and publish refs only after object durability and connectivity verification.

Advantages:

- preserves canonical Git object IDs and ancestry;
- remains independent of the persisted Hibernate row layout;
- naturally supports bounded streaming;
- can evolve towards pack transfer without changing the public request/result contract;
- works with the existing repository lifecycle and ref compare-and-set model.

Costs:

- the first implementation transfers canonical objects individually rather than selecting an optimal pack;
- a failed attempt may leave unreachable target objects that a retry can reuse;
- larger histories still require pack-oriented benchmarking and tuning.

### B. Backend-aware row/chunk copy

Copy committed pack metadata and chunks to rows owned by the target repository.

Advantages:

- potentially fewer decompression/recompression steps and database round trips;
- could be efficient when source and target use the same database and layout.

Risks:

- couples the operation to pack generations, replacement metadata, extension rows, cache rules and future layout versions;
- makes it easier to copy mutable coordination state accidentally;
- requires proving that source-owned pack and Reftable metadata remain valid under another repository name;
- would expose or duplicate storage-sensitive behavior that should remain internal.

### C. In-process Git transport

Connect two repositories through JGit fetch/receive transport machinery without HTTP or filesystem repositories.

Advantages:

- closely follows normal Git negotiation and protocol semantics;
- may become attractive for incremental fetch and remote topologies.

Risks:

- adds protocol/transport setup for the initial same-database case;
- does not by itself define the target lifecycle, ref policy, audit evidence or failure cleanup contract;
- can obscure the exact local atomicity boundary.

## Decision

Use option A as the stable model and phase-1 implementation.

The public API is plan-based:

- `HibernateRepositoryTransferService` executes a `RepositoryTransferRequest`;
- `RefTransferSpec` maps exact source refs to exact target refs;
- `RepositoryTransferMode` distinguishes initial clone from future incremental fetch;
- `TargetRefPolicy` makes create, fast-forward, compare-and-set and force intent explicit;
- `RepositoryTransferResult` records exact ref IDs plus visited/transferred object and byte counts.

Phase 1 implements only:

```text
INITIAL_CLONE + CREATE_ONLY
```

Other modes are represented in the API but fail explicitly until their full concurrency and retry contracts are implemented.

### Source snapshot

All selected source refs are resolved once before the target is opened. Those exact object IDs form the immutable source snapshot. A concurrent source ref update can create a newer commit but cannot alter the in-progress transfer.

Only explicit refs under `refs/heads/` and `refs/tags/` are accepted. Symbolic and unsupported internal namespaces are rejected.

### Object transfer

The implementation uses `ObjectWalk`, `ObjectReader`, `ObjectLoader` and the streaming `ObjectInserter.insert(type, size, stream)` API. It does not materialize the complete history or a complete pack in heap.

For every inserted object, the target inserter's returned ID must equal the source ID. Objects already present in an otherwise ref-empty target are reused, which makes retry after an interrupted attempt efficient.

The object inserter is flushed before any target ref is published. Optional connectivity verification then traverses the target from every captured source root and fails before ref publication if any commit, parent, tree, blob or tag is missing.

### Ref publication

An initial target must not contain refs. All requested target refs are published through one atomic `BatchRefUpdate`, with old ID `zeroId` for create-only compare-and-set behavior. A stale or unrelated target ref is never overwritten.

The phase-1 implementation configures a neutral transfer operation identity for JGit's ref update. It does not copy source reflog history. The dedicated Hibernate reflog integration for atomic multi-ref transfer will be completed with the incremental/ref-policy phase; consumers must use `RepositoryTransferResult` as the authoritative phase-1 audit evidence.

### Failure and retry

Before ref publication, no transferred history is reachable through target refs. A failed object stream, connectivity check or ref precondition therefore cannot expose partial history.

An object flush followed by a ref-publication failure may leave unreachable committed target objects. This is intentional and bounded by the attempted reachable graph. A retry reuses those objects. Repository cleanup or deletion removes them with the target repository.

Source and target handles remain registered with the existing repository lifecycle coordination for the duration of the operation, preventing deletion through the public factory while a transfer is active.

### Isolation and projections

Objects and refs are persisted under the target `RepositoryName`; no source row is shared as target authority. Deleting source after a successful transfer cannot make target history unreadable, and deleting target cannot remove source data.

Search indexes and application projection tables are not copied. They remain derived state and can be rebuilt or incrementally updated from the exact target ref IDs returned by the operation.

## Consequences

- Consumers can create a working copy or fork whose initial head is the exact source commit.
- Git merge-base and ordinary ancestry operations work without a separate content-only sync base.
- Phase 1 remains independent of Hibernate's physical pack/chunk layout and adds no schema.
- The implementation is bounded but not yet pack-negotiated; large-scale evidence remains necessary.
- Incremental missing-object calculation, `FAST_FORWARD_ONLY`, `COMPARE_AND_SET`, explicit force, cross-database coverage, security hooks and retained performance evidence remain in issue #236.
- Backend row-copy and in-process transport remain possible internal optimizations only if future measurements justify them; they must not change the public transfer semantics.
