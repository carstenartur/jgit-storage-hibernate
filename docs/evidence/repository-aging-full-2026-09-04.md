# Full repository-aging matrix — 2026-09-04

The complete age matrix is now retained and reprocessed with the corrected JMH event-counter
converter. For the measured reopen-plus-oldest-object path, cold JGit-cache reads benefit from
maintenance at ten or more packs. Warm reads regress at 10–100 packs and improve at 300–1,000.
These are workload-specific observations; automatic maintenance remains disabled.

## Provenance and completeness

- source commit: [`ecc778510e2899f8f4ec82188c5b0af71e8536a8`](https://github.com/carstenartur/jgit-storage-hibernate/commit/ecc778510e2899f8f4ec82188c5b0af71e8536a8)
- successful workflow: [`33878467696`](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/33878467696)
- raw aggregate: [`performance-investigation-repository-aging-full`](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/33878467696/artifacts/9939824985)
- verified archive SHA-256: `b8c796cf1165c3996518953c013a593f06a578ef63167b3587418e5213cca1cc`
- conversion: [event-counter normalization from PR #347](https://github.com/carstenartur/jgit-storage-hibernate/pull/347), re-applied on 2026-09-07
- durable normalized data: [all 864 coordinates as CSV](repository-aging-full-2026-09-04.csv)

The validated Cartesian product contains three backends (HSQLDB reference, PostgreSQL built-in
pool, PostgreSQL/HikariCP), two JGit-cache states, six ages (1/10/32/100/300/1,000 pushes), three
maintenance modes and eight read operations. All six backend/cache shards and their aggregate
passed. The CSV retains latency mean/error, p50/p95/p99, pack/index/storage counters, maintenance
cost, read savings and the calculated whole-read payback for every coordinate.

This is the `same-provider`, `local-testcontainers` lifecycle with `evidenceRepeat=1`. JMH 1.37
ran on JDK 21.0.12.1, one thread and one fork, with one 500-ms warmup iteration and three 750-ms
sample-time measurement iterations, using `-Xms1g -Xmx3g`. Three timed iterations are not three
independent process repeats. Cold means the JGit block-cache condition, not a cold operating-system
page cache or cold database buffer pool.

The fixture has 1,000 small incremental packs at its largest age, approximately 10.7 MB of stored
extensions and 1.16 MB of pack indexes before maintenance. It measures pack fragmentation, not
large-production-repository capacity.

## Correcting the earlier aggregate

The original artifact predates PR #347. Its auxiliary `EVENTS` scores sum the three measurement
iterations, although each iteration repeats the same fixture/maintenance counters. The current
converter reads the retained per-iteration values instead. Primary latency values are unchanged.

For PostgreSQL/cold/10 pushes/compact-only/reopen, the original aggregate reported six packs,
240 ms maintenance and 36 break-even reads. The corrected values are **two packs, 80 ms and
12 reads**. The CSV contains the corrected values, not the original derived policy JSON.

To reproduce the normalization after extracting the raw aggregate:

```sh
python3 .github/scripts/convert-jmh-repository-aging.py \
  /path/to/extracted/jmh-result.json /tmp/repository-aging-normalized
```

The retained CSV is a direct tabular export of `policyEvidence` from the resulting
`repository-aging-policy-evidence.json`; no timing samples are rescaled or discarded.

## Reopen plus oldest-object lookup

The following tables show the production-oriented PostgreSQL/HikariCP fixture. All elapsed values
are milliseconds. Payback is `ceil(maintenance duration / saved time per equivalent read)` for
compact-only maintenance. A dash means no useful pack-reducing intervention or no positive saving.

### Cold JGit cache

| Pushes | No maintenance | Compact-only | Read-optimized | Compact cost | Compact payback reads |
|---:|---:|---:|---:|---:|---:|
| 1 | 2.280 | 2.251 | 2.215 | 39 | — |
| 10 | 6.839 | 2.443 | 2.425 | 58 | 14 |
| 32 | 18.464 | 2.652 | 2.537 | 89 | 6 |
| 100 | 53.363 | 3.376 | 3.327 | 240 | 5 |
| 300 | 173.933 | 2.822 | 2.861 | 510 | 3 |
| 1,000 | 407.971 | 2.581 | 2.614 | 1546 | 4 |

### Warm JGit cache

| Pushes | No maintenance | Compact-only | Read-optimized | Compact cost | Compact payback reads |
|---:|---:|---:|---:|---:|---:|
| 1 | 1.711 | 1.675 | 1.678 | 44 | — |
| 10 | 1.715 | 2.753 | 2.641 | 73 | — |
| 32 | 1.956 | 2.748 | 2.668 | 116 | — |
| 100 | 2.580 | 3.542 | 3.477 | 284 | — |
| 300 | 4.444 | 2.940 | 2.919 | 592 | 394 |
| 1,000 | 11.007 | 2.540 | 2.462 | 1586 | 188 |

The built-in PostgreSQL pool has the same direction: compact-only cold reopen falls from
10.427 to 3.577 ms at ten pushes and from 605.727 to 3.801 ms at 1,000. Warm reopen regresses
at 10–100 pushes, then improves from 3.759 to 2.866 ms at 300 and from 8.375 to 2.492 ms at
1,000. All corresponding values, including uncertainty and other operations, are in the CSV.

For warm reopen, the measured sign change is between 100 and 300 packs in both PostgreSQL pool
configurations. The JMH mean/error intervals for none versus either maintenance mode are separate
at these two measured endpoints. Independent full-matrix repeats are still required before treating
this bracket as stable across machines or choosing an operational threshold.

At 1,000 packs, compact-only cold payback is three equivalent reopens with the built-in pool and
four with HikariCP. Warm payback is much longer: 306 and 188 equivalent reopens respectively.
These estimates exclude interference while maintenance runs and apply only while the resulting
read advantage persists. Other operations have different payback; the minimum across operations
is not a universal trigger.

## Confirming the provider-restart result

The separate [rerun `33900635892`](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/33900635892)
uses source [`cefb3cd4f9d16c77737f4681d79b3f6450cf76ae`](https://github.com/carstenartur/jgit-storage-hibernate/commit/cefb3cd4f9d16c77737f4681d79b3f6450cf76ae),
after the counter correction. PostgreSQL and SQL Server × cold/warm × three independent repeats
all passed, producing 36 operation/maintenance groups and 108 read scores at ten pushes.

The [unaltered aggregate JSON](repository-aging-restart-rerun-2026-09-04.json) retains all repeat
scores and dispersion. Its [source artifact](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/33900635892/artifacts/9947633457)
has verified archive SHA-256 `b7cd60d948203eb4e62e16cd1d43b8372171703f5db8245c79d2aa0a10b7f8ef`.

| Backend / cache | No maintenance | Compact-only | Read-optimized |
|---|---:|---:|---:|
| postgresql / cold | 18.384 ms | 6.042 ms | 6.294 ms |
| postgresql / warm | 3.819 ms | 4.805 ms | 4.663 ms |
| sqlserver / cold | 36.467 ms | 12.129 ms | 11.625 ms |
| sqlserver / warm | 7.061 ms | 8.081 ms | 8.578 ms |

The repeated lifecycle result confirms the earlier direction: cold reopen benefits and warm reopen
regresses at ten packs on both databases. This table measures a complete provider/pool restart
before retained reads; its absolute timings must not be pooled with the same-provider age matrix.

## Operational consequence and remaining work

- Prefer explicit compact-only maintenance as the first candidate when cold reopen has degraded;
  its read result is close to the more expensive read-optimized preset in this fixture.
- Avoid a global ten-pack trigger for warm repositories. At 10–100 packs it would regress this
  reopen path by roughly 35–61% across the two PostgreSQL pool configurations and both modes.
- At 300–1,000 packs, evaluate the observed saving against the number and type of future reads;
  the measured crossover bracket is a candidate for repeated validation, not a production default.
- Issue #165 still needs the full SQL Server age axis, independent repeats at the larger ages,
  read-latency impact during active maintenance, native resource costs at scale and a condition-based
  policy. Persistable JGit MIDX capability also needs a separate implementation/performance evaluation.

No runtime selector, maintenance schedule or production default changes in this evidence update.
See [policy evidence](../operations/repository-aging-policy-evidence.md) and the
[performance status](../performance-status.md#repository-aging-and-maintenance).
