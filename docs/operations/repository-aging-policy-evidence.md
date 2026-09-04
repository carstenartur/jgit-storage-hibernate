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

A separate protected-main restart profile closes the repository and the complete Hibernate
`SessionFactory`/connection pool, reconstructs them from the same durable database and only then
executes the retained read. Its first complete run covers PostgreSQL and SQL Server, cold and warm
JGit cache states, and three independent repeats at ten incremental pushes. This lifecycle matrix
tests a different question from the full age axis and must not be treated as a substitute for the
32/100/300/1,000-push breakpoints.

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

The complete protected-main provider-restart result from 2026-09-04 is retained as:

- a [human-readable reproducibility table](../evidence/repository-aging-restart-reproducibility-2026-09-04.md);
- its [machine-readable repeat and dispersion CSV](../evidence/repository-aging-restart-reproducibility-2026-09-04.csv);
- a [paired maintenance-payback calculation](../evidence/repository-aging-restart-payback-2026-09-04.md); and
- the [machine-readable payback inputs](../evidence/repository-aging-restart-payback-2026-09-04.csv).

Together these files preserve all three repeat scores, mean, range, population standard deviation,
coefficient of variation, matching maintenance duration and the exact workflow run and artifact
digest.

## Interpreting break-even evidence

A low break-even value is not by itself a production trigger. A useful recommendation also requires:

1. the same structural condition to recur across retained runs;
2. stable direction on the production database and relevant cache state;
3. no unacceptable storage, WAL, lock-time or temporary-disk cost;
4. no atomic-publication or ref-race regression;
5. a deployment workload that actually performs enough of the improved operation before the repository changes materially again.

The generated summary chooses the lowest measured break-even among important read operations only as a candidate for inspection. Automatic maintenance remains disabled.

## Provider-restart evidence at ten pushes

Protected workflow run
[`33873297888`](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/33873297888)
executed PostgreSQL and SQL Server × cold and warm cache state × repeats 1, 2 and 3 from exact
protected-main commit
[`929dd72a9228b7b93468bf5284a41971910816cb`](https://github.com/carstenartur/jgit-storage-hibernate/commit/929dd72a9228b7b93468bf5284a41971910816cb).
All twelve coordinate jobs and the aggregate job passed. The aggregate artifact digest is
`sha256:1d1aa28fd56b6b1192998787ae882747fcab4798607231193f7a60571c56cc55`.

### Reopen plus oldest-object lookup

| Backend / cache | No maintenance | Compact-only | Read-optimized | Maintenance versus none |
|---|---:|---:|---:|---|
| PostgreSQL cold | 18.556 ms (CV 2.22%) | 6.046 ms (CV 4.37%) | 5.941 ms (CV 2.29%) | 67.4–68.0% faster |
| SQL Server cold | 28.972 ms (CV 20.03%) | 8.586 ms (CV 19.36%) | 8.545 ms (CV 20.47%) | 70.4–70.5% faster |
| PostgreSQL warm | 3.660 ms (CV 4.34%) | 4.555 ms (CV 3.13%) | 4.712 ms (CV 5.87%) | 24.5–28.8% slower |
| SQL Server warm | 7.069 ms (CV 9.58%) | 8.101 ms (CV 2.35%) | 8.493 ms (CV 5.57%) | 14.6–20.2% slower |

The cold result is directionally strong. PostgreSQL has low repeat dispersion, and even the wider SQL
Server ranges do not overlap between the ten-pack baseline and either maintenance mode. The warm
result points in the opposite direction: at this small age, an already warmed read path is faster
without maintenance.

### Maintenance payback for the retained reopen path

Each maintenance result is paired with the no-maintenance result from the same backend, cache state
and repeat. Break-even is maintenance duration divided by the paired per-read saving.

| Backend / cache | Maintenance | Mean maintenance | Mean saving per read | Break-even reads | Paired repeat range |
|---|---|---:|---:|---:|---:|
| PostgreSQL cold | compact-only | 71.7 ms | 12.510 ms | 5.73 | 5.31–6.23 |
| PostgreSQL cold | read-optimized | 98.7 ms | 12.615 ms | 7.82 | 7.49–8.10 |
| SQL Server cold | compact-only | 124.3 ms | 20.386 ms | 6.10 | 3.29–13.89 |
| SQL Server cold | read-optimized | 147.7 ms | 20.427 ms | 7.23 | 4.46–15.72 |
| PostgreSQL warm | both modes | 65.3–98.0 ms | negative | none | none |
| SQL Server warm | both modes | 84.0–111.0 ms | negative | none | none |

For the cold ten-pack fixture, compact-only maintenance pays back after approximately six equivalent
reopen-plus-oldest-object reads; the read-optimized preset needs roughly seven to eight. For the warm
fixture there is no finite payback because every paired repeat regresses. The SQL Server cold
direction is stable, but its exact break-even range remains wider than PostgreSQL's.

### Other retained reads

For PostgreSQL cold clone-style traversal, compact-only maintenance lowers the mean from 0.279 ms to
0.196 ms (29.8%) and read-optimized maintenance lowers it to 0.233 ms (16.6%). The warm PostgreSQL
improvement is only about 7%. SQL Server clone-style differences are around 0–3% and are not
actionable because cold CV reaches 17.8–32.7%.

Direct oldest-object lookup remains roughly 0.008–0.012 ms. Its relative CV reaches 15.9% on
PostgreSQL and 40.8% on SQL Server, so it is too small and noisy to drive maintenance policy.

The evidence therefore rejects a universal `10 packs => repack` rule. It supports evaluating
maintenance when **cold reconstruction/reopen latency** has degraded, while showing that warm
repositories at the same pack count can regress. Compact-only and read-optimized are effectively
tied for the cold reopen path; `compactOnly()` remains the lower-cost first intervention unless
bitmap, commit-graph or changed-path workloads justify the read-optimized structures.

## Multi-Pack-Index capability

Every supported JGit compatibility job writes `jgit-dfs-midx-capability.json`. It records the selected JGit implementation version, every exposed `PackExt`, and whether a persisted DFS Multi-Pack-Index extension is visible.

The maintenance API must not expose a MIDX setting unless the selected supported JGit DFS version has a persistable extension that this backend can store. When the capability artifact reports no such extension, the supported choices remain no maintenance, compact-only repack and read-optimized repack.

## Remaining evidence before an automatic policy

Issue #165 remains open. The completed restart, payback and cross-database correctness slices narrow
the remaining work to broader quantitative policy evidence:

- retain the full 32/100/300/1,000-push age matrix on PostgreSQL, PostgreSQL+HikariCP and SQL Server;
- extend lifecycle-specific maintenance payback beyond the retained ten-push condition;
- measure read latency while maintenance is actively consuming database, WAL/log, I/O and storage resources;
- repeat SQL Server cold measurements or use a controlled production-like runner before relying on exact percentages;
- derive a stable condition from pack count, small-pack ratio, index bytes, unreachable bytes, measured degradation and time since maintenance;
- publish direct links from the public performance history to retained charts and raw evidence;
- re-evaluate MIDX only when a supported JGit DFS version exposes a persistable extension.

Until then, operators should call `PackStorageMaintenance` explicitly and retain `PackRepackResult` together with application-level latency evidence.
