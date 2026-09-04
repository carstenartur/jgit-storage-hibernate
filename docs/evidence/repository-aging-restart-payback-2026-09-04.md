# Provider-restart maintenance payback — 2026-09-04

This record derives maintenance payback from the raw JMH output of the protected-main
[repository-aging provider-restart run](repository-aging-restart-reproducibility-2026-09-04.md).
It is evidence for one ten-push fixture, not an automatic maintenance policy.

## Provenance and method

- source and request merge commit: [`929dd72a9228b7b93468bf5284a41971910816cb`](https://github.com/carstenartur/jgit-storage-hibernate/commit/929dd72a9228b7b93468bf5284a41971910816cb)
- workflow run: [`33873297888`](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/33873297888)
- matrix: PostgreSQL and SQL Server × cold and warm JGit cache × repeats 1, 2 and 3
- operation: `reopenAndLookupOldest`
- maintenance duration: the `maintenanceElapsedMillis` secondary JMH result from the matching trial
- paired latency saving: no-maintenance latency minus maintained latency for the same backend, cache state and repeat
- break-even reads: `maintenanceElapsedMillis / paired latency saving`
- when the paired saving is zero or negative, no finite break-even is reported

The accompanying [CSV](repository-aging-restart-payback-2026-09-04.csv) retains every paired input
and result. This calculation excludes resource interference while maintenance is running and assumes
the measured post-maintenance advantage remains relevant until the indicated number of equivalent
reads has occurred.

## Result

The summary uses mean maintenance duration divided by mean paired latency saving. The final column
shows the range of break-even values calculated independently for the three repeats.

| Backend / cache | Maintenance | Mean maintenance | Mean saving per reopen | Mean saving | Break-even reads | Paired repeat range |
|---|---|---:|---:|---:|---:|---:|
| PostgreSQL cold | compact-only | 71.7 ms | 12.510 ms | 67.4% | 5.73 | 5.31–6.23 |
| PostgreSQL cold | read-optimized | 98.7 ms | 12.615 ms | 68.0% | 7.82 | 7.49–8.10 |
| SQL Server cold | compact-only | 124.3 ms | 20.386 ms | 70.4% | 6.10 | 3.29–13.89 |
| SQL Server cold | read-optimized | 147.7 ms | 20.427 ms | 70.5% | 7.23 | 4.46–15.72 |
| PostgreSQL warm | compact-only | 65.3 ms | −0.895 ms | 24.5% slower | none | none |
| PostgreSQL warm | read-optimized | 98.0 ms | −1.052 ms | 28.8% slower | none | none |
| SQL Server warm | compact-only | 84.0 ms | −1.032 ms | 14.6% slower | none | none |
| SQL Server warm | read-optimized | 111.0 ms | −1.425 ms | 20.2% slower | none | none |

## Interpretation

For the cold ten-pack lifecycle, one maintenance run pays back after approximately six equivalent
reopen-plus-oldest-object reads with compact-only maintenance and roughly seven to eight with the
read-optimized preset. PostgreSQL is repeatable enough to support those bounded point estimates;
SQL Server has the same direction but a materially wider payback range because both maintenance and
cold reopen measurements vary more on the hosted runner.

For the warm lifecycle, neither mode has a payback: every paired repeat is slower after maintenance.
This is a direct reason not to use a global pack-count threshold. A useful operational trigger must
incorporate lifecycle/cache behavior and an observed read-path degradation.

Compact-only remains the first intervention for the measured cold reopen problem. It reaches nearly
the same post-maintenance latency as read-optimized maintenance with lower maintenance cost and a
shorter payback. Read-optimized maintenance remains appropriate when its bitmaps, commit graph and
changed-path structures benefit other clone/fetch or path-history workloads enough to repay the
additional work.

## Limits and next evidence

- The fixture has ten deterministic incremental packs; larger 32/100/300/1,000-push conditions may
  move both maintenance cost and per-read saving.
- The calculation prices only the selected read after maintenance. It does not yet measure latency
  experienced by concurrent readers while maintenance consumes database, WAL/log, I/O and CPU.
- Repository writes can shorten the interval in which the measured benefit remains applicable.
- SQL Server cold results need more repetitions or a controlled production-like runner before exact
  payback values should be used as deployment defaults.
- Automatic maintenance remains disabled and opt-in.
