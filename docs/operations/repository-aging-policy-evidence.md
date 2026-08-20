# Repository-aging policy evidence

Long-lived repositories accumulate incremental packs. This page defines how the project measures the resulting lookup, traversal, reopen and storage amplification before recommending maintenance. It is an evidence contract, not an automatic maintenance policy.

## Matrix

The bounded smoke profile covers 1 and 10 deterministic incremental pushes on HSQLDB with a cold JGit block cache. The full manual/scheduled profile covers:

- 1, 10, 32, 100, 300 and 1,000 pushes;
- HSQLDB as a local reference plus PostgreSQL with the built-in pool and HikariCP;
- cold and warm JGit cache states;
- no maintenance, compact-only maintenance and read-optimized maintenance;
- oldest, newest and missing-object lookup;
- clone-style and incremental-fetch traversal;
- a pure revision walk;
- complete ref/Reftable reads;
- repository reopen followed by oldest-object lookup.

Each fixture uses deterministic content, closes and reopens before measurement, verifies persisted pack ordering and revalidates reachable objects and the main ref after maintenance.

## Retained artifacts

The `Performance Investigations` workflow retains the unmodified JMH JSON and derives three additional files for `repository-aging`:

```text
repository-aging-comparison.json
repository-aging-policy-evidence.json
repository-aging-policy-evidence.md
```

The comparison JSON is suitable for the benchmark dashboard. The policy JSON retains the structural condition and calculated evidence for each backend, age, cache state, operation and maintenance mode:

- active packs and small-pack ratio;
- PACK, IDX and total extension bytes;
- unreachable logical bytes;
- maintenance duration, stored-byte delta and pack reduction;
- operation latency and uncertainty;
- latency saving relative to the matching no-maintenance baseline;
- measured break-even reads, calculated as maintenance time divided by per-read saving.

The converter rejects duplicate conditions and any condition that lacks `none`, `compact-only` or `read-optimized` evidence. A mode is marked beneficial only when it both removes active packs and improves the measured operation. It does not change runtime configuration.

## Interpreting break-even evidence

A low break-even value is not by itself a production trigger. A useful recommendation also requires:

1. the same structural condition to recur across retained runs;
2. stable direction on the production database and relevant cache state;
3. no unacceptable storage, WAL, lock-time or temporary-disk cost;
4. no atomic-publication or ref-race regression;
5. a deployment workload that actually performs enough of the improved operation before the repository changes materially again.

The generated summary chooses the lowest measured break-even among important read operations only as a candidate for inspection. Automatic maintenance remains disabled.

## Multi-Pack-Index capability

Every supported JGit compatibility job writes `jgit-dfs-midx-capability.json`. It records the selected JGit implementation version, every exposed `PackExt`, and whether a persisted DFS Multi-Pack-Index extension is visible.

The maintenance API must not expose a MIDX setting unless the selected supported JGit DFS version has a persistable extension that this backend can store. When the capability artifact reports no such extension, the supported choices remain no maintenance, compact-only repack and read-optimized repack.

## Remaining evidence before an automatic policy

This increment establishes the complete age axis, cold/warm cache semantics, revision-walk coverage, MIDX capability evidence and reproducible policy conversion. Issue #165 remains open until the following are retained and reviewed:

- PostgreSQL full-matrix artifacts at the requested scales;
- equivalent SQL Server artifacts;
- reads during same-repository maintenance and independent-repository maintenance;
- database-native WAL/log, I/O, wait, server CPU and lock evidence;
- a stable condition-based recommendation across repeated runs;
- links from the public performance history to the retained charts and raw evidence.

Until then, operators should call `PackStorageMaintenance` explicitly and retain `PackRepackResult` together with application-level latency evidence.
