# ADR-0002: Transfer Git ancestry between logical Hibernate repositories

## Status

Accepted for initial clone and incremental selected-ref transfer. Database matrices, failure injection, security/projection hooks and retained scale evidence remain follow-up work in issue #236.

## Context

A database can contain multiple repositories isolated by `RepositoryName`. Consumers such as Taxonomy need to provision a workspace or fork from a central repository while retaining the original commit IDs, parents, trees, blobs and tags. Creating a new content-equivalent commit is insufficient because the target then has no shared Git ancestor with the source.

The same consumer must later advance its upstream/tracking refs without rescanning or recopying all old history, and without silently overwriting a concurrently changed target ref.

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

- the current implementation transfers canonical objects individually rather than selecting an optimal pack;
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
- may become attractive for remote topologies.

Risks:

- adds protocol/transport setup for the same-database case;
- does not by itself define target lifecycle, ref policy, audit evidence or failure cleanup;
- can obscure the exact local atomicity boundary.

## Decision

Use option A as the stable model for both initial and incremental transfer.

The public API is plan-based:

- `HibernateRepositoryTransferService` executes a `RepositoryTransferRequest`;
- `RefTransferSpec` maps exact source refs to exact target refs and optionally carries an expected target object ID;
- `RepositoryTransferMode` distinguishes initial clone from incremental fetch;
- `TargetRefPolicy` makes create, fast-forward, compare-and-set and force intent explicit;
- `RepositoryTransferResult` records exact ref IDs plus visited/transferred object and byte counts.

Supported combinations are:

```text
INITIAL_CLONE      + CREATE_ONLY
INCREMENTAL_FETCH  + CREATE_ONLY
INCREMENTAL_FETCH  + FAST_FORWARD_ONLY
INCREMENTAL_FETCH  + COMPARE_AND_SET
INCREMENTAL_FETCH  + FORCE
```

### Source and target snapshots

All selected source refs are resolved once before the target is opened. Those exact object IDs form the immutable source snapshot. A concurrent source ref update can create a newer commit but cannot alter the in-progress transfer.

For incremental transfer, each target ref is also resolved once before object copying. The captured target ID becomes the old ID in the final `ReceiveCommand`, so a concurrent target writer cannot be overwritten between validation and atomic publication.

Only explicit refs under `refs/heads/` and `refs/tags/` are accepted. Symbolic and unsupported internal namespaces are rejected.

### Missing-object calculation and transfer

The implementation uses `ObjectWalk`, `ObjectReader`, `ObjectLoader` and the streaming `ObjectInserter.insert(type, size, stream)` API. It does not materialize the complete history or a complete pack in heap.

Selected source tips are interesting roots. Every target ref tip that also exists in the source object database is marked uninteresting. The walk therefore stops at history already known by the target, analogous to a bounded local fetch negotiation. A final target-side `has` check also reuses unrelated or retry-leftover objects that are present without being reachable from a target ref.

For every inserted object, the target inserter's returned ID must equal the source ID. The object inserter is flushed before any target ref is published. Optional connectivity verification then traverses the target from every captured source root and fails before ref publication if any commit, parent, tree, blob or tag is missing.

### Ref policies

`CREATE_ONLY` creates absent refs and treats an identical existing ref as an idempotent no-op. A different existing value is rejected.

`FAST_FORWARD_ONLY` requires an existing commit-valued target ref. The old target commit must be merged into the captured source commit.

`COMPARE_AND_SET` requires an expected target object ID for every ref. The current target must equal that expected ID, and a changed commit-valued ref must still be a fast-forward. An already-published desired value is accepted as an idempotent retry even when the original expected ID is now stale. An expected zero ID can be used for an explicitly guarded create.

`FORCE` is the only policy that permits a non-fast-forward update. An optional expected target ID adds a caller-supplied stale-writer precondition. Even without it, the captured current target ID is used as the final command old ID, so concurrent publication still fails rather than being overwritten.

All changed target refs are published through one atomic `BatchRefUpdate`. A mixed batch succeeds as a unit or leaves every target ref unchanged.

### Reflog and audit evidence

Source reflogs are repository-local operational history and are not copied. The transfer configures a neutral operation identity for JGit's batch update and returns `RepositoryTransferResult` as explicit operation evidence.

Atomic integration with the dedicated queryable Hibernate reflog remains follow-up work. Until then, consumers must not interpret copied source reflog history as part of transfer semantics.

### Failure and retry

Before ref publication, no newly transferred history becomes reachable through target refs. A failed object stream, connectivity check, fast-forward check or compare-and-set precondition therefore cannot expose a partial ref state.

An object flush followed by a ref-policy or publication failure may leave unreachable committed target objects. This is intentional and bounded by the attempted reachable graph. A retry reuses those canonical objects. Repository cleanup or deletion removes them with the target repository.

A successful repeated request whose target refs already equal the captured source IDs returns a no-op result and transfers no objects.

Source and target handles remain registered with the existing repository lifecycle coordination for the duration of the operation, preventing deletion through the public factory while a transfer is active.

### Isolation and projections

Objects and refs are persisted under the target `RepositoryName`; no source row is shared as target authority. Deleting source after a successful transfer cannot make target history unreadable, and deleting target cannot remove source data.

Search indexes and application projection tables are not copied. They remain derived state and can be rebuilt or incrementally updated from the exact target ref IDs returned by the operation.

## Consequences

- Consumers can create a working copy or fork whose initial head is the exact source commit.
- Subsequent fetch-like updates transfer only newly reachable canonical objects.
- Git merge-base and ordinary ancestry operations work without a separate content-only sync base.
- Explicit policies prevent accidental force updates and stale-writer overwrites.
- The implementation remains independent of Hibernate's physical pack/chunk layout and adds no schema.
- The implementation is bounded but not yet pack-negotiated; large-scale evidence remains necessary.
- Dedicated Hibernate reflog integration, cross-database coverage, failure/concurrency matrices, security hooks, projection completion, Taxonomy adoption and retained performance evidence remain in issue #236.
- Backend row-copy and in-process transport remain possible internal optimizations only if future measurements justify them; they must not change the public transfer semantics.
