# Repository-aging provider-restart payback — corrected rerun

Each maintenance result is paired with the no-maintenance result from the same database, cache state and independent repeat. Break-even is maintenance duration divided by the per-read saving. A negative saving has no finite payback.

## Aggregate point estimates

| Backend / cache | Maintenance | Mean maintenance | Mean saving per reopen | Mean break-even | Paired repeat range |
|---|---|---:|---:|---:|---:|
| postgresql / cold | compact-only | 71.3 ms | 12.342 ms | 5.78 reads | 5.31–6.24 |
| postgresql / cold | read-optimized | 94.3 ms | 12.090 ms | 7.80 reads | 7.25–8.13 |
| postgresql / warm | compact-only | 67.7 ms | -0.986 ms | none | – |
| postgresql / warm | read-optimized | 97.0 ms | -0.844 ms | none | – |
| sqlserver / cold | compact-only | 87.7 ms | 24.338 ms | 3.60 reads | 3.56–3.69 |
| sqlserver / cold | read-optimized | 120.0 ms | 24.843 ms | 4.83 reads | 4.60–5.03 |
| sqlserver / warm | compact-only | 83.7 ms | -1.020 ms | none | – |
| sqlserver / warm | read-optimized | 119.0 ms | -1.517 ms | none | – |

## Interpretation

- PostgreSQL cold compact-only pays back after about 5.78 equivalent reopens; read-optimized after about 7.80.
- SQL Server cold compact-only pays back after about 3.60 reopens; read-optimized after about 4.83 in this rerun.
- Every warm repeat regresses for both databases and both maintenance modes, so warm reopen has no finite payback.
- The SQL Server cold payback differs from the first run because its absolute cold timings and maintenance cost changed. The direction is stable, but the exact number is not yet a portable constant.

These values apply only to the measured ten-pack provider-restart fixture. They are not a default threshold. The paired inputs are retained in [the machine-readable CSV](repository-aging-restart-payback-2026-09-04-rerun.csv).
