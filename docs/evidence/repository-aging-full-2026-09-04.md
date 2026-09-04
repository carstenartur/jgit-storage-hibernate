# Full repository-aging age-axis evidence — 2026-09-04

This decision record retains the corrected interpretation of the complete repository-aging age-axis run. It is evidence for operator guidance, not an automatic maintenance trigger.

## Provenance and scope

- Workflow run: [`33878467696`](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/33878467696)
- Measured source commit: [`ecc778510e2899f8f4ec82188c5b0af71e8536a8`](https://github.com/carstenartur/jgit-storage-hibernate/commit/ecc778510e2899f8f4ec82188c5b0af71e8536a8)
- Aggregate artifact: `performance-investigation-repository-aging-full`
- Aggregate artifact digest: `sha256:b8c796cf1165c3996518953c013a593f06a578ef63167b3587418e5213cca1cc`
- Counter-normalization implementation: [`842398fde103937b5320cdf79657c3d9905affec`](https://github.com/carstenartur/jgit-storage-hibernate/commit/842398fde103937b5320cdf79657c3d9905affec)
- Matrix size: 864 JMH coordinates
- Backends: HSQLDB reference, PostgreSQL built-in pool and PostgreSQL with HikariCP
- Ages: 1, 10, 32, 100, 300 and 1,000 deterministic incremental pushes
- Cache states: cold and warm JGit DFS block cache
- Maintenance modes: none, compact-only and read-optimized
- Read operations: oldest/newest/missing object lookup, clone-style traversal, incremental-fetch traversal, revision walk, ref read and reopen plus oldest-object lookup

All six backend/cache shards and the aggregate job completed successfully. The raw JMH primary scores, confidence data and p50/p95/p99 values were not modified.

## Why the derived counters were regenerated

JMH reports `AuxCounters.Type.EVENTS` aggregate `score` as the sum of measurement iterations. The original converter treated that sum as a single repository condition, so three-iteration runs multiplied pack counts, byte counts and maintenance duration by three. The corrected converter uses the mean of retained `rawData`, then the secondary p50, with the aggregate score only as a compatibility fallback.

This correction changes structural counters and break-even calculations. It does **not** change any measured read latency. The retained CSVs on this page are generated with the corrected interpretation; for example, 100% small packs is stored as 10,000 basis points rather than `1`.

## Decision summary

- One-pack repositories must not be maintained: both presets produce two active packs and increase stored bytes.
- From ten cold incremental packs onward, compact-only maintenance is the lowest-cost candidate for the measured reopen problem.
- On PostgreSQL, cold reopen break-even drops from 12 equivalent reads at ten packs to 3 at 300–1,000 packs; HikariCP shows the same direction with break-even 14, 6, 5, 3 and 4.
- Warm repositories behave differently. Ten through 100 packs regress on the reopen path after maintenance; only at 300–1,000 packs does warm reopen improve materially.
- Read-optimized maintenance does not produce a consistent reopen advantage over compact-only, while it normally costs more and stores more auxiliary data. It remains justified only by bitmap, commit-graph or changed-path workloads.
- Pack count alone is not a safe production trigger. Automatic maintenance remains disabled.

## Structural effect

| Incremental packs | Active packs after compact-only | Pack reduction | Compact-only stored-byte delta | Read-optimized stored-byte delta |
|---:|---:|---:|---:|---:|
| 1 | 2 | -1 | +1 076 B | +2 464 B |
| 10 | 2 | 8 | -8 860 B | -6 608 B |
| 32 | 2 | 30 | -33 148 B | -28 536 B |
| 100 | 2 | 98 | -108 220 B | -94 424 B |
| 300 | 2 | 298 | -329 021 B | -300 863 B |
| 1 000 | 2 | 998 | -1 101 821 B | -1 029 897 B |

At one push, maintenance is structurally counterproductive. At ten or more pushes, both modes reduce the measured history to two active ordinary packs. Compact-only retains fewer bytes than the read-optimized preset at every measured age.

## Cold reopen plus oldest-object lookup

| Backend | Packs | None | Compact-only | Improvement | Maintenance | Break-even reads | Read-optimized | Break-even reads |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| PostgreSQL | 10 | 10.427 ms | 3.577 ms | 65.7% | 80 ms | 12 | 3.603 ms | 16 |
| PostgreSQL | 32 | 26.292 ms | 3.701 ms | 85.9% | 124 ms | 6 | 3.607 ms | 8 |
| PostgreSQL | 100 | 76.094 ms | 4.538 ms | 94.0% | 283 ms | 4 | 4.659 ms | 5 |
| PostgreSQL | 300 | 248.965 ms | 4.076 ms | 98.4% | 591 ms | 3 | 4.462 ms | 3 |
| PostgreSQL | 1 000 | 605.727 ms | 3.801 ms | 99.4% | 1623 ms | 3 | 3.853 ms | 3 |
| PostgreSQL + HikariCP | 10 | 6.839 ms | 2.443 ms | 64.3% | 58 ms | 14 | 2.425 ms | 19 |
| PostgreSQL + HikariCP | 32 | 18.464 ms | 2.652 ms | 85.6% | 89 ms | 6 | 2.537 ms | 8 |
| PostgreSQL + HikariCP | 100 | 53.363 ms | 3.376 ms | 93.7% | 240 ms | 5 | 3.327 ms | 6 |
| PostgreSQL + HikariCP | 300 | 173.933 ms | 2.822 ms | 98.4% | 510 ms | 3 | 2.861 ms | 4 |
| PostgreSQL + HikariCP | 1 000 | 407.971 ms | 2.581 ms | 99.4% | 1546 ms | 4 | 2.614 ms | 4 |
| HSQLDB reference | 10 | 2.476 ms | 0.832 ms | 66.4% | 55 ms | 34 | 0.777 ms | 47 |
| HSQLDB reference | 32 | 6.886 ms | 0.898 ms | 87.0% | 92 ms | 16 | 0.853 ms | 19 |
| HSQLDB reference | 100 | 15.197 ms | 0.896 ms | 94.1% | 198 ms | 14 | 0.831 ms | 17 |
| HSQLDB reference | 300 | 48.285 ms | 0.674 ms | 98.6% | 481 ms | 11 | 0.905 ms | 12 |
| HSQLDB reference | 1 000 | 137.771 ms | 0.821 ms | 99.4% | 1341 ms | 10 | 0.652 ms | 11 |

The cold baseline grows roughly with the number of incremental packs, while either maintenance preset keeps the measured reopen in a narrow low-millisecond range. That is the strongest and most stable age-axis signal.

## Warm reopen behavior

| Backend | Packs | None | Compact-only | Change | Compact break-even | Read-optimized | Change |
|---|---:|---:|---:|---:|---:|---:|---:|
| PostgreSQL | 10 | 1.637 ms | 2.529 ms | -54.5% | – | 2.485 ms | -51.8% |
| PostgreSQL | 32 | 1.858 ms | 2.605 ms | -40.2% | – | 2.528 ms | -36.1% |
| PostgreSQL | 100 | 2.392 ms | 3.516 ms | -47.0% | – | 3.355 ms | -40.3% |
| PostgreSQL | 300 | 3.759 ms | 2.866 ms | +23.8% | 702 | 2.766 ms | +26.4% |
| PostgreSQL | 1 000 | 8.375 ms | 2.492 ms | +70.2% | 306 | 2.398 ms | +71.4% |
| PostgreSQL + HikariCP | 10 | 1.715 ms | 2.753 ms | -60.5% | – | 2.641 ms | -54.0% |
| PostgreSQL + HikariCP | 32 | 1.956 ms | 2.748 ms | -40.5% | – | 2.668 ms | -36.4% |
| PostgreSQL + HikariCP | 100 | 2.580 ms | 3.542 ms | -37.3% | – | 3.477 ms | -34.8% |
| PostgreSQL + HikariCP | 300 | 4.444 ms | 2.940 ms | +33.8% | 394 | 2.919 ms | +34.3% |
| PostgreSQL + HikariCP | 1 000 | 11.007 ms | 2.540 ms | +76.9% | 188 | 2.462 ms | +77.6% |

Positive percentages mean lower latency. At ten, 32 and 100 packs, both maintenance modes make the already warm reopen path slower. At 300 and 1,000 packs, the pack-catalog cost becomes large enough that maintenance wins again, but its payback remains far higher than in the cold case.

## Generated recommendation semantics

The recommendation CSV selects the lowest measured break-even among five important operations (`reopenAndLookupOldest`, direct oldest-object lookup, clone traversal, incremental-fetch traversal and revision walk) only when the candidate also reduces active packs. It is deliberately permissive evidence: a candidate with thousands of required reads is **not** an instruction to run maintenance.

For cold conditions at ten or more packs, every backend selects compact-only maintenance for the reopen path. Warm candidates often come from tiny clone-traversal savings and require hundreds to tens of thousands of repetitions; they therefore do not justify an automatic trigger.

## Multi-Pack-Index finding

The selected JGit version exposes `PackExt.MULTI_PACK_INDEX`, so the capability flag is true. This is not yet equivalent to restart-safe backend support. JGit's MIDX description also carries covered-pack and base-MIDX relationships; the current Hibernate schema and `listPacks()` reconstruction persist only scalar pack metadata. A MIDX file without those relationships would not be a complete durable representation after provider restart.

Consequently MIDX remains disabled until the relationship metadata, migrations, reconstruction logic and restart tests are implemented. Repack and read-optimized maintenance remain the supported choices.

## Retained machine-readable files

- [All 864 corrected policy rows, gzip-compressed CSV](repository-aging-full-policy-2026-09-04.csv.gz) (`sha256:a98700dfbc6f88c14211a72fee0e2b54f6f6ad93bd13807c688404197a702a06`)
- [The 36 generated backend/age/cache recommendations](repository-aging-full-recommendations-2026-09-04.csv)
- [Focused reopen axis with all backends, ages and modes](repository-aging-full-reopen-2026-09-04.csv)

## Remaining evidence

The completed age axis closes the missing 32/100/300/1,000-push PostgreSQL and PostgreSQL-Hikari measurement gap. Before enabling automatic maintenance, the project still needs:

1. the same full age axis on SQL Server;
2. latency and database-native telemetry while maintenance competes with live reads/writes;
3. repeated age-axis runs or production-like runners to establish stable thresholds rather than one-host point estimates;
4. a workload-aware decision that includes cache/lifecycle state, read mix, pack count, small-pack ratio, index bytes, unreachable bytes and time since previous maintenance;
5. durable MIDX relationship support before evaluating MIDX as an alternative.

Until those conditions are met, operators should invoke `PackStorageMaintenance` explicitly, prefer `compactOnly()` for simple pack reduction, and retain `PackRepackResult` together with application-level latency evidence.
