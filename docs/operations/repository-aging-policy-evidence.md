# Repository-aging policy evidence

Long-lived repositories accumulate incremental packs. This page defines how the project measures lookup, traversal, reopen and storage amplification before recommending maintenance. It is an evidence contract, not an automatic maintenance policy.

## Evidence layers

The project separates three questions that must not be collapsed into one threshold:

1. **Age axis:** how one, ten, 32, 100, 300 and 1,000 deterministic incremental packs affect reads with a cold or warm JGit DFS cache.
2. **Provider restart:** how the same durable repository behaves after the repository, Hibernate `SessionFactory` and connection pool have been rebuilt.
3. **Live interference:** how maintenance affects concurrent readers, writers, WAL/log generation, I/O and lock time while it runs.

The first two layers now have retained evidence. The third remains required before any automatic policy.

## Complete age-axis matrix

Protected workflow run [`33878467696`](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/33878467696) measured 864 JMH coordinates from commit [`ecc778510e2899f8f4ec82188c5b0af71e8536a8`](https://github.com/carstenartur/jgit-storage-hibernate/commit/ecc778510e2899f8f4ec82188c5b0af71e8536a8):

- HSQLDB as a local reference;
- PostgreSQL with the built-in pool and with HikariCP;
- one, ten, 32, 100, 300 and 1,000 incremental pushes;
- cold and warm JGit cache state;
- no maintenance, compact-only and read-optimized maintenance;
- eight lookup, traversal, ref and reopen operations.

The aggregate artifact digest is `sha256:b8c796cf1165c3996518953c013a593f06a578ef63167b3587418e5213cca1cc`. The complete interpretation, tables and generated recommendation CSV are retained in [Full repository-aging age-axis evidence](../evidence/repository-aging-full-2026-09-04.md).

### Corrected event counters

The original derived report treated JMH `AuxCounters.Type.EVENTS` aggregate scores as one condition. JMH sums those counters over measurement iterations, so three-iteration runs tripled pack counts, bytes and maintenance duration. Commit [`842398fde103937b5320cdf79657c3d9905affec`](https://github.com/carstenartur/jgit-storage-hibernate/commit/842398fde103937b5320cdf79657c3d9905affec) changed the converter to use retained raw iteration values, then p50, with score-only evidence as a compatibility fallback.

Primary read scores and p50/p95/p99 values were never altered. The corrected structural values and break-even calculations are the authoritative interpretation.

### Cold reopen result

For `reopenAndLookupOldest`, compact-only maintenance produces the following PostgreSQL age curve:

| Packs | Built-in pool: no maintenance | Built-in pool: compact | Break-even | HikariCP: no maintenance | HikariCP: compact | Break-even |
|---:|---:|---:|---:|---:|---:|---:|
| 10 | 10.427 ms | 3.577 ms | 12 reads | 6.839 ms | 2.443 ms | 14 reads |
| 32 | 26.292 ms | 3.701 ms | 6 reads | 18.464 ms | 2.652 ms | 6 reads |
| 100 | 76.094 ms | 4.538 ms | 4 reads | 53.363 ms | 3.376 ms | 5 reads |
| 300 | 248.965 ms | 4.076 ms | 3 reads | 173.933 ms | 2.822 ms | 3 reads |
| 1,000 | 605.727 ms | 3.801 ms | 3 reads | 407.971 ms | 2.581 ms | 4 reads |

The unmaintained cold reopen cost grows strongly with pack count. Both maintenance presets reduce the active ordinary packs to two and keep this path in a narrow low-millisecond range.

### Warm-path qualification

Warm results do not support the same threshold. On PostgreSQL and PostgreSQL-Hikari, maintenance makes the reopen path slower at ten, 32 and 100 packs. It becomes beneficial again at 300 and 1,000 packs, but compact-only payback is about 702 and 306 reads for the built-in pool and 394 and 188 for HikariCP.

Generated warm recommendations can also arise from very small clone-traversal differences and require hundreds to tens of thousands of reads. They are evidence for workload inspection, not a production trigger.

### Structural result

One-pack maintenance is rejected: either preset creates two packs and increases stored bytes. From ten packs onward, compact-only reduces the fixture to two packs and saves more storage than read-optimized maintenance:

| Packs | Compact stored-byte delta | Read-optimized delta |
|---:|---:|---:|
| 10 | -8,860 B | -6,608 B |
| 32 | -33,148 B | -28,536 B |
| 100 | -108,220 B | -94,424 B |
| 300 | -329,021 B | -300,863 B |
| 1,000 | -1,101,821 B | -1,029,897 B |

## Provider-restart evidence

Two complete protected-main provider-restart runs now cover PostgreSQL and SQL Server, cold and warm cache states, and three independent repeats per condition.

The first run is retained in:

- [First reproducibility record](../evidence/repository-aging-restart-reproducibility-2026-09-04.md);
- [first repeat and dispersion CSV](../evidence/repository-aging-restart-reproducibility-2026-09-04.csv);
- [first paired maintenance-payback record](../evidence/repository-aging-restart-payback-2026-09-04.md);
- [first paired payback inputs](../evidence/repository-aging-restart-payback-2026-09-04.csv).

The corrected rerun from workflow [`33900635892`](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/33900635892), exact commit [`cefb3cd4f9d16c77737f4681d79b3f6450cf76ae`](https://github.com/carstenartur/jgit-storage-hibernate/commit/cefb3cd4f9d16c77737f4681d79b3f6450cf76ae), is retained in:

- [rerun reproducibility record](../evidence/repository-aging-restart-reproducibility-2026-09-04-rerun.md);
- [rerun repeat and dispersion CSV](../evidence/repository-aging-restart-reproducibility-2026-09-04-rerun.csv);
- [rerun paired maintenance-payback record](../evidence/repository-aging-restart-payback-2026-09-04-rerun.md);
- [rerun paired payback inputs](../evidence/repository-aging-restart-payback-2026-09-04-rerun.csv);
- [cross-run comparison](../evidence/repository-aging-restart-cross-run-2026-09-04.md).

### Reproduced lifecycle split

| Backend / cache | First run: none → compact | Rerun: none → compact | Stable conclusion |
|---|---:|---:|---|
| PostgreSQL cold | 18.556 → 6.046 ms | 18.384 → 6.042 ms | about two-thirds faster |
| PostgreSQL warm | 3.660 → 4.555 ms | 3.819 → 4.805 ms | slower after maintenance |
| SQL Server cold | 28.972 → 8.586 ms | 36.467 → 12.129 ms | large benefit; absolute timing varies |
| SQL Server warm | 7.069 → 8.101 ms | 7.061 → 8.081 ms | slower after maintenance |

PostgreSQL cold is highly reproducible: the no-maintenance and compact-only means differ by less than 1% between runs. SQL Server warm is nearly identical between runs. SQL Server cold has runner-sensitive absolute values, but both complete runs show a large, non-overlapping maintenance benefit.

### Paired payback

In the corrected rerun:

- PostgreSQL cold compact-only pays back after a mean 5.78 equivalent reopens; read-optimized after 7.80;
- SQL Server cold compact-only pays back after a mean 3.60 reopens; read-optimized after 4.83;
- every warm repeat on both databases regresses, so the measured warm reopen path has no finite payback.

The exact SQL Server break-even must not be encoded as a portable constant until a full SQL Server age axis and production-like repeated runs exist. The lifecycle direction, however, is now independently reproduced.

## Recommendation semantics

The converter emits a candidate only when a mode both removes active packs and improves the matching operation. It selects the lowest measured break-even among reopen, direct oldest-object lookup, clone traversal, incremental-fetch traversal and revision walk.

A candidate is not an instruction. Automatic maintenance requires all of the following:

1. the same structural condition and direction across retained runs;
2. the relevant production database and cache/lifecycle state;
3. enough expected improved operations to repay maintenance before the repository changes materially;
4. acceptable WAL/log, storage, temporary-disk and lock cost;
5. no publication, ref-race, restart or concurrent-reader regression.

## Multi-Pack-Index capability

The selected JGit release exposes `PackExt.MULTI_PACK_INDEX`, so capability probing reports that the extension exists. Durable backend support is not complete, however: JGit also associates a MIDX description with its covered packs and optional base MIDX, while the current Hibernate entity and `listPacks()` reconstruction retain only scalar pack metadata.

The project must persist and migrate those relationships, reconstruct them after restart and verify replacement/deletion semantics before exposing MIDX as a maintenance option. Extension presence alone is not sufficient.

## Current policy

- Do not maintain a one-pack repository.
- Do not trigger from pack count alone.
- For a measured cold reopen/catalog problem, use `compactOnly()` as the first intervention.
- Use read-optimized maintenance only when bitmap, commit-graph or changed-path workloads justify its extra construction and storage cost.
- Keep automatic maintenance disabled.

## Remaining evidence

Issue #165 is narrowed to:

- the full 1/10/32/100/300/1,000 age axis on SQL Server;
- database-native and application latency while maintenance competes with live reads/writes;
- repeated age-axis evidence on production-like runners;
- a deployable condition combining cache/lifecycle state, operation mix, active packs, small-pack ratio, index bytes, unreachable bytes and time since maintenance;
- durable MIDX relationship support and restart tests.

Until then, operators should invoke `PackStorageMaintenance` explicitly and retain `PackRepackResult` together with application-level latency evidence.
