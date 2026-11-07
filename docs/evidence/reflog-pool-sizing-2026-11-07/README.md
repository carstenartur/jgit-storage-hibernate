# PostgreSQL durable reflog pool sizing evidence

These files support the [operator guidance](../../operations/reflog-pool-sizing.md).
The production source is main revision `a906cf0184ef166e4a8b9774e8dc8f47fbce6211`;
the benchmark changes accompanying this evidence only instrument that source.

The fixture used PostgreSQL 17.10, PgJDBC 42.7.14, HikariCP 7.0.2,
Toxiproxy 2.12.0 and Eclipse Temurin 25.0.5+8. JDBC SSL was disabled on
the loopback links. The client, proxy and database ran on one Linux host
with an AMD EPYC 9V74 and an 8-vCPU quota; the database used
local overlay storage. PostgreSQL had `fsync=on`, `synchronous_commit=on`,
`track_io_timing=on`, `track_wal_io_timing=on`, 128 MiB of shared buffers,
and a 96 MiB WAL size target. `log_lock_waits` recorded waits passing the
50 ms deadlock-check threshold. Both Toxiproxy latency directions had zero
configured jitter. Requested RTT was split as evenly as possible between
upstream and downstream (2 and 3 ms for the 5 ms profile).

The plan crosses all stripe/pool pairs for 16 independent producers
on direct loopback. It also compares shared and independent repository
layouts, 1/4/8/16 producers, and six selected coordinates at each requested
RTT of 5, 20 and 50 ms. The size comparison uses 13-character and
2,000-character ASCII messages at local and 20 ms RTT. There are three
sequential, shuffled trials per coordinate in one JVM. Every trial used
fresh Hibernate-generated tables and repository state; migrated schemas
and production history statistics can differ. The retained plan specifies
warmup and measured command counts per producer; shaped runs use shorter
phases to stay inside the fixture's 60-second deadline. The default
2 ms collection window, 50-command cap and production queue bounds apply.

| File | What it contains |
| --- | --- |
| [plan.csv](plan.csv) | Exact trial order, configurations and command counts |
| [per-trial.csv](per-trial.csv) | Throughput, nearest-rank p50/p95/p99, paired latency phases, occupancy peaks, counts and WAL |
| [acknowledgements.csv.gz](acknowledgements.csv.gz) | One row per accepted, committed command; batch IDs are local to the trial |
| [pool-borrows.csv.gz](pool-borrows.csv.gz) | One Hikari acquisition and hold observation per measured transaction |
| [occupancy.csv.gz](occupancy.csv.gz) | Roughly 20 ms samples of physical/active connections, pool waiters, JVM heap, queue occupancy and PostgreSQL lock waiters |
| [rtt-calibration.csv.gz](rtt-calibration.csv.gz) | 20 retained SELECT 1 round trips per trial after discarding five |
| [native.ndjson.gz](native.ndjson.gz) | Native PostgreSQL deltas and gauges, source revision, server version and trial metadata |

Heap and lock waiters are sampled observations. A zero lock sample does not
exclude shorter waits. WAL insert-position movement is immediate and covers
the isolated cluster's measurement window. Native WAL sync counts and time
can lag by a PostgreSQL statistics refresh; a reported zero in a short trial
is not proof that no WAL sync occurred. The raw native capture retains
unsupported counters and observation boundaries.

## Shared history diagnostic

Two exploratory trials outside the sizing matrix used 16 producers,
128 measured commands per producer and one shared repository. Their median Hikari holds were 90 ms and 147 ms,
while median pool acquisition was below 0.004 ms. The raw
[acknowledgements](history-exploratory-acknowledgements.csv.gz)
and [pool borrows](history-exploratory-pool-borrows.csv.gz) retain these exploratory
observations, which prompted a separate SQL diagnostic. The
[representative SQL](history-depth-probe.sql) and [raw plans](history-depth-explain.txt)
show the history subquery ran 128, 1,024 and 2,048 times as seeded history
grew, each time returning only 16 tips. Before explicitly analyzing the table,
the corresponding SQL executions took 0.37, 5.73 and 16.72 ms. The probe uses
literal parameters and deterministic seeded history. It identifies extra
work in the query shape; it does not reproduce the exact JDBC prepared plan
or account for the complete end-to-end transaction time.

## Reproduce

Start a dedicated PostgreSQL 17.10 instance on loopback with a disposable
database named `jgit_reflog_sizing_disposable` and a local-only `benchmark`
role. Configure the database as above and set `lock_timeout=10000` and
`statement_timeout=15000` (milliseconds). The fixture **creates and drops
its mapped tables** for every trial. Start Toxiproxy and create a dedicated, initially
unshaped route from a second loopback port to the database:

```sh
curl -fsS -X POST -H 'Content-Type: application/json' \
  -d '{"name":"reflog-sizing","listen":"127.0.0.1:56462","upstream":"127.0.0.1:55462","enabled":true}' \
  http://127.0.0.1:58462/proxies
```

From the repository root:

```sh
evidence="$PWD/docs/evidence/reflog-pool-sizing-2026-11-07"
prefix=jgit.storage.benchmark.reflog-batch.sizing
mvn -pl jgit-storage-hibernate-benchmarks -am \
  -Dtest=ReflogPoolSizingBenchmarkTest#writesDisposablePostgresqlMatrix \
  -Dsurefire.failIfNoSpecifiedTests=false \
  "-D$prefix.revision=$(git rev-parse HEAD)" \
  "-D$prefix.directUrl=jdbc:postgresql://127.0.0.1:55462/jgit_reflog_sizing_disposable" \
  "-D$prefix.routedUrl=jdbc:postgresql://127.0.0.1:56462/jgit_reflog_sizing_disposable" \
  "-D$prefix.toxiproxy=http://127.0.0.1:58462/proxies/reflog-sizing" \
  "-D$prefix.externalPlan=$evidence/plan.csv" \
  "-D$prefix.externalOutput=$PWD/target/reflog-sizing" test

cp "$evidence/plan.csv" target/reflog-sizing/
python .github/scripts/summarize-reflog-pool-sizing.py target/reflog-sizing --charts
```

Use Python 3.10 or later and Matplotlib 3.7 or later for the optional SVG generation.
The script also accepts the compressed raw files in this directory. Run
without `--charts` to validate and summarize a different trial plan.
