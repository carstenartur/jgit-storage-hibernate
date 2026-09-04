# Repository-aging provider-restart reproducibility — corrected rerun

This record retains the second protected-main PostgreSQL/SQL Server cold/warm provider-restart matrix. It was requested after the repository-aging event-counter converter was corrected. The benchmark source and automatic-maintenance behavior were unchanged.

## Provenance

- Workflow run: [`33900635892`](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/33900635892)
- Exact source commit: [`cefb3cd4f9d16c77737f4681d79b3f6450cf76ae`](https://github.com/carstenartur/jgit-storage-hibernate/commit/cefb3cd4f9d16c77737f4681d79b3f6450cf76ae)
- Coordinate artifacts: 12, all successful and non-expired
- Aggregate artifact: `repository-aging-restart-reproducibility-summary`
- Aggregate artifact digest: `sha256:b7cd60d948203eb4e62e16cd1d43b8372171703f5db8245c79d2aa0a10b7f8ef`
- Coordinates: PostgreSQL and SQL Server × cold and warm cache × repeats 1, 2 and 3
- Operations: clone-style traversal, direct oldest-object lookup and reopen plus oldest-object lookup
- Maintenance modes: none, compact-only and read-optimized

Every coordinate rebuilt the complete Hibernate provider and connection pool before retained reads. The workflow validated the restart phase, exact coordinates and absence of credentials before uploading each artifact.

## Reopen plus oldest-object lookup

| Backend / cache | None | Compact-only | Read-optimized | Compact change | Read-optimized change |
|---|---:|---:|---:|---:|---:|
| PostgreSQL / cold | 18.384 ms | 6.042 ms | 6.294 ms | +67.1% | +65.8% |
| PostgreSQL / warm | 3.819 ms | 4.805 ms | 4.663 ms | -25.8% | -22.1% |
| SQL Server / cold | 36.467 ms | 12.129 ms | 11.625 ms | +66.7% | +68.1% |
| SQL Server / warm | 7.061 ms | 8.081 ms | 8.578 ms | -14.4% | -21.5% |

Positive percentages mean lower latency. Cold reconstruction is substantially faster after maintenance on both databases. Both warm paths regress.

## Dispersion

| Backend / cache | Mode | Mean | CV | Repeat range |
|---|---|---:|---:|---:|
| PostgreSQL / cold | none | 18.384 ms | 2.88% | 17.703–18.997 ms |
| PostgreSQL / cold | compact-only | 6.042 ms | 4.62% | 5.845–6.437 ms |
| PostgreSQL / cold | read-optimized | 6.294 ms | 2.82% | 6.074–6.508 ms |
| PostgreSQL / warm | none | 3.819 ms | 9.43% | 3.494–4.321 ms |
| PostgreSQL / warm | compact-only | 4.805 ms | 4.24% | 4.639–5.092 ms |
| PostgreSQL / warm | read-optimized | 4.663 ms | 4.25% | 4.522–4.944 ms |
| SQL Server / cold | none | 36.467 ms | 3.39% | 34.745–37.596 ms |
| SQL Server / cold | compact-only | 12.129 ms | 8.04% | 11.434–13.509 ms |
| SQL Server / cold | read-optimized | 11.625 ms | 4.77% | 10.889–12.230 ms |
| SQL Server / warm | none | 7.061 ms | 0.88% | 7.015–7.149 ms |
| SQL Server / warm | compact-only | 8.081 ms | 4.72% | 7.645–8.575 ms |
| SQL Server / warm | read-optimized | 8.578 ms | 4.55% | 8.027–8.881 ms |

PostgreSQL cold remains low-dispersion and nearly reproduces the first run. SQL Server cold absolute times differ from the first run, but dispersion is materially lower and the maintenance direction remains strong. SQL Server warm is almost unchanged across the two runs.

## Other operations

- PostgreSQL cold clone-style traversal improves from 0.245 ms to 0.220 ms with compact-only and 0.204 ms with read-optimized maintenance.
- SQL Server cold clone-style traversal improves from 0.316 ms to 0.207 ms with compact-only and 0.245 ms with read-optimized maintenance.
- Direct oldest-object lookup remains around 0.009–0.014 ms and has enough relative noise that it must not drive policy.
- Warm clone-style point estimates often improve, but they are sub-millisecond and do not override the clearly slower warm reopen path.

## Decision

The rerun confirms that a universal pack-count trigger is unsafe. Maintenance is valuable for cold provider reconstruction at ten incremental packs, but the same repository with an already warm read path can be slower. Compact-only remains the lower-cost first intervention; automatic maintenance remains disabled.

The complete repeat values are retained in [the machine-readable CSV](repository-aging-restart-reproducibility-2026-09-04-rerun.csv). The paired maintenance-cost calculation is retained separately in [the payback record](repository-aging-restart-payback-2026-09-04-rerun.md).
