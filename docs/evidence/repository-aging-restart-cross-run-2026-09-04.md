# Repository-aging provider-restart cross-run comparison — 2026-09-04

This comparison evaluates the two complete protected-main provider-restart runs. It separates reproducible direction from runner-sensitive absolute timing.

| Backend / cache | Mode | First run | Corrected rerun | Relative difference | First CV | Rerun CV |
|---|---|---:|---:|---:|---:|---:|
| PostgreSQL / cold | none | 18.556 ms | 18.384 ms | -0.9% | 2.22% | 2.88% |
| PostgreSQL / cold | compact-only | 6.046 ms | 6.042 ms | -0.1% | 4.37% | 4.62% |
| PostgreSQL / cold | read-optimized | 5.941 ms | 6.294 ms | +5.9% | 2.29% | 2.82% |
| PostgreSQL / warm | none | 3.660 ms | 3.819 ms | +4.4% | 4.34% | 9.43% |
| PostgreSQL / warm | compact-only | 4.555 ms | 4.805 ms | +5.5% | 3.13% | 4.24% |
| PostgreSQL / warm | read-optimized | 4.712 ms | 4.663 ms | -1.0% | 5.87% | 4.25% |
| SQL Server / cold | none | 28.972 ms | 36.467 ms | +25.9% | 20.03% | 3.39% |
| SQL Server / cold | compact-only | 8.586 ms | 12.129 ms | +41.3% | 19.36% | 8.04% |
| SQL Server / cold | read-optimized | 8.545 ms | 11.625 ms | +36.0% | 20.47% | 4.77% |
| SQL Server / warm | none | 7.069 ms | 7.061 ms | -0.1% | 9.58% | 0.88% |
| SQL Server / warm | compact-only | 8.101 ms | 8.081 ms | -0.2% | 2.35% | 4.72% |
| SQL Server / warm | read-optimized | 8.493 ms | 8.578 ms | +1.0% | 5.57% | 4.55% |

## Stable conclusions

- PostgreSQL cold is highly reproducible: no-maintenance and compact-only differ by less than 1% between runs; cold compaction still saves about two thirds.
- PostgreSQL warm reproduces the opposite direction: both maintenance modes make reopen slower.
- SQL Server warm is nearly identical between runs and again regresses after maintenance.
- SQL Server cold absolute values moved, but both runs show a large, non-overlapping maintenance benefit. The rerun has much lower dispersion.

## Consequence

The evidence supports lifecycle-aware operator guidance but not a universal automatic trigger. PostgreSQL provides a stable ten-pack cold payback near six reopens. SQL Server confirms the direction but still needs age-axis and production-like repeated evidence before its exact payback can be encoded.
