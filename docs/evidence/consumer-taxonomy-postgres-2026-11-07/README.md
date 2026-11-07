# Taxonomy-shaped PostgreSQL lifecycle and query evidence

The native smoke run completed all five Taxonomy operations at both configured
network delays, and the lifecycle tests preserved committed partial projections
across provider reopening before restoring the affected workspace. This evidence
completes the small native PostgreSQL execution check for issue #211. It does not
establish a stable latency ranking or complete the larger history matrix.

## Source and retained artifacts

[Workflow run 42419257922](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42419257922)
executed commit
[`23a5f6a6a42d1042f9f68ffafe8a0ffaa0f3097b`](https://github.com/carstenartur/jgit-storage-hibernate/commit/23a5f6a6a42d1042f9f68ffafe8a0ffaa0f3097b).
Its tree is `9f3ae505a0c37aeb1925bb38926d477fc710b791`, identical to the separately
reviewed local commit `02a0dfb52e1a9ce83630771c95a2db5b22c2b473`. The GitHub Git
commit API and local Git tree resolution were compared when retaining this
evidence. These are measurements of that source tree, without the later Audio or
Sandbox follow-up changes.

The original artifact ZIP files are retained byte for byte. Their SHA-256 hashes
match the artifact digests reported by GitHub. Each contains the original JMH
JSON and log, per-operation PostgreSQL telemetry in NDJSON and JSON, round-trip
probe samples, recovery record, fixture manifest and Surefire reports.

| Configured added RTT | Job | Artifact | Original archive SHA-256 |
|---|---|---|---|
| 0 ms | [128543944106](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42419257922/job/128543944106) | [rtt-0ms.zip](rtt-0ms.zip), ID 13592568904 | `6e8d2ae04c6e5dda34f568889fc27cdaa55c660fbc97ca16e7a8ddbad0168acf` |
| 20 ms | [128543943780](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42419257922/job/128543943780) | [rtt-20ms.zip](rtt-20ms.zip), ID 13592768322 | `5e2b8807ed9a77980501fcf56cfb6a2f3650f8a27cf78e06db74c264f209e312` |

[manifest.json](manifest.json) records source, run, job and artifact identities.
[observations.csv](observations.csv) preserves all ten primary observations and
selected counters, with allocation columns explicitly labeled as whole-iteration
measurements. Reproduce and verify the CSV from the retained archives with:

```bash
python3 .github/scripts/verify-consumer-taxonomy-native-evidence.py \
  docs/evidence/consumer-taxonomy-postgres-2026-11-07/manifest.json
```

The verifier checks both archive digests, each fixture's individual file hashes,
source revisions, calibration summaries, complete JMH/native coordinate pairing,
the recovery records and all twelve Java test results. It then requires the CSV
to reproduce the retained raw values exactly. No benchmark is rerun.

## Workload and lifecycle coverage

Both jobs used PostgreSQL 17.10, filesystem Lucene, Java 21, a 1 GiB maximum heap,
one JMH fork and one single-shot measurement per operation with no warmup. Each
history contained 1,000 commits across ten independent 100-commit workspaces.
Indexing batches contained 50 commits; purge batches were configured to 250.
History construction used the direct local database endpoint. The measured
provider then reopened through Toxiproxy. The JGit block cache was cleared;
operating-system and database caches remained warm.

Each job passed two `ConsumerProfileBenchmarkTest` cases and four
`ConsumerTaxonomyLifecycleTest` cases: six tests, zero failures, zero errors and
zero skips. The tests exercised SQL/Lucene fixture membership, provider closure
and reopening, cleanup after failed history setup, and recovery after a committed
partial rebuild. Each JMH trial also compared the complete SQL and indexed path
result membership during setup. The timed methods verified returned commit IDs
through the authoritative Git repository.

The separate recovery record is identical in both jobs:

| Recovery checkpoint | Observed commits or hits |
|---|---:|
| Complete fixture | 1,000 commits |
| Affected workspace before interruption | 100 projections |
| Committed partial projection after interruption | 50 projections |
| Partial projection after closing and reopening the provider | 50 projections |
| Completed recovery after purging and rebuilding the workspace | 100 projections |
| Recovered text hits verified through Git | 58 hits |

The lifecycle assertions also required the neighboring workspace to retain all
100 projections, the affected Git head to remain unchanged, and the complete
original text-hit membership to be restored after another provider reopening.
This is provider close/reopen and cooperative interruption coverage. It does not
exercise a database-server restart, process kill, host failure or remote outbox
replay.

## Network calibration

Each job discarded five JDBC `SELECT 1` probes, then retained twenty probes on a
single connection through the measurement route. The configured delay is added
by the proxy; the zero-delay cell still includes the real local connection and
scheduling cost.

| Configured added RTT | Measured median | Measured p95 | Probe count |
|---|---:|---:|---:|
| 0 ms | 2.194 ms | 4.487 ms | 20 |
| 20 ms | 20.634 ms | 20.686 ms | 20 |

The nonzero-delay calibration gate requires the median to reach at least 80% of
the configured delay. The retained samples allow the actual route to be assessed
instead of treating the requested setting as an exact measured RTT. These probes
were collected once per job before the operation matrix; they do not constitute
a continuous latency trace.

## Operation observations

The following values are individual observations. Every method processes all ten
workspaces. The query methods include Git verification of the returned IDs, and
the rebuild method combines projection purge, reindexing and post-rebuild result
verification. The timing therefore describes that complete benchmark method.

| Operation | 0 ms added RTT, ms | 20 ms added RTT, ms | Result count in each cell | Prepared statements in each cell | Transactions in each cell | JDBC batches in each cell |
|---|---:|---:|---:|---:|---:|---:|
| Exact-path Lucene summary | 136.700 | 2,194.399 | 90 | 50 | 50 | 0 |
| Projection purge and rebuild | 1,178.213 | 5,276.313 | 1,000 | 100 | 90 | 30 |
| Literal-path SQL summary | 148.449 | 2,378.342 | 90 | 60 | 50 | 0 |
| Text entity hydration | 192.756 | 2,412.397 | 200 | 60 | 50 | 0 |
| Text summary projection | 172.247 | 2,212.780 | 200 | 50 | 50 | 0 |

The equal path result counts supplement the complete membership check performed
during setup; they do not imply that literal substring and exact-path predicates
have equivalent semantics for arbitrary histories. The two text operations also
return matching fixture results. SQL path lookup and entity hydration each add
ten prepared statements, one per workspace, relative to their corresponding
indexed summary path. The reopened Git verification itself contributes database
work, so the summary methods' totals are not zero-statement query-only counts.

The delay cells ran in separate CI jobs, and each operation used a fresh fork.
There are no repeated paired samples or confidence intervals. These timings
demonstrate successful execution at both measured network conditions; they are
not evidence for a stable performance ranking, regression boundary or default
configuration change.

## Resource observations and their boundaries

All ten records include native PostgreSQL counters and nonempty filesystem index
size observations. Index snapshots range from 299,914 to 381,884 bytes. They are
captured after the operation while the provider remains open, so background
Lucene merge state can differ between independently constructed trials. They
are not a stable retained-size comparison between query variants or a peak-disk
measurement.

The rebuild observations recorded the following WAL values:

| Configured added RTT | `pg_stat_wal` byte delta | WAL insert-LSN advance |
|---|---:|---:|
| 0 ms | 813,854 bytes | 1,332,176 bytes |
| 20 ms | 1,071,629 bytes | 1,332,032 bytes |

Native counters describe the dedicated database or instance and are explicitly
marked `cumulative-observational`. Their sampling and backend publication can
lag the operation. For example, the rebuild result verifies 1,000 projections,
while the sampled inserted-tuple deltas are only 600 and 800. Those counters
must not replace application membership assertions or be divided into exact
per-row costs. The WAL columns above likewise have different accounting and
publication boundaries.

`track_io_timing` and `track_wal_io_timing` were off in both jobs, and
`pg_stat_statements` was absent. Zero timing counters do not establish zero I/O
cost. The endpoint gauges showed zero lock and I/O waiters, but these gauges do
not capture waits between snapshots. No deadlock or temporary-byte increments
were observed in the captured windows; that does not establish general
contention behavior or peak temporary space.

JMH's GC profiler surrounds the complete iteration, including trial setup and
teardown for these one-iteration forks. The retained normalized allocation totals
are about 2.82 to 3.00 billion bytes and the GC counts are 42 to 52. They include
history construction, provider/index initialization and cleanup. They are not
per-query allocation or retained/peak heap values, even though JMH labels the
normalized metric `B/op`. The primary single-shot clock and native telemetry
window exclude fixture construction. Operation-scoped allocation profiling is
still needed before making memory-efficiency comparisons.

## Remaining evidence

The workflow supports 10,000- and 100,000-commit histories, but this retained run
contains only the 1,000-commit smoke coordinate. Larger histories, repeated
comparisons, the separately selected warm-provider/cache mode, measured peak
resources and process/database failure recovery remain outside this result.
The other consumer shapes retain their own evidence and acceptance scope. No
production threshold or default is changed by this benchmark evidence.
