# Measuring synchronous pack staging and JDBC writes

The [native pack WAL chart](../evidence/database-native-evidence-2026-11-07.md#pack-wal-per-logical-mib) shows the WAL generated per logical MiB for the hosted and local staging samples.

The [JMH Benchmarks workflow](../../.github/workflows/performance.yml) includes a
PostgreSQL 17 smoke test that publishes a 16 MiB incompressible JGit object three
times through the synchronous writer with its 16 MiB window. The test closes
the repository, clears the JGit DFS cache and reopens it to verify the SHA-256
digest of the stored payload. PostgreSQL runs with `fsync`, synchronous commit
and I/O timing enabled.

The `jdbc-batch-comparison-results` artifact includes a `staging-jdbc/` directory:

- `phase-timeline.csv`: the start offset and duration of each staging reread and
  chunk JDBC batch. Offsets are relative to the first selected event in each sample.
- `database-telemetry.json` and `database-telemetry.ndjson`: elapsed publication
  time, staging byte count, WAL, database I/O and wait observations for the same
  numbered samples.

The trace selects file reads from the temporary pack during chunk publication and
JDBC batch executions from the pack chunk writer. It excludes class loading,
other database statements, repository setup and reopen verification. Native
snapshots bracket the publication separately. A file read may be served by the
operating system cache, and JDBC batch elapsed time includes driver, network and
database work. Endpoint wait gauges do not measure the duration of waits between
snapshots. JFR adds observation overhead; use the separate raw JMH results in the
same artifact for the existing elapsed-time and allocation baseline.

To collect the diagnostic evidence locally, run with Java 21 or later and Docker:

```sh
mvn -B -pl jgit-storage-hibernate-benchmarks -am test \
  -Dtest=SynchronousPackPublicationEvidenceTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Djgit.storage.benchmark.staging-jdbc.native=true
```

The payload and writer window can be set with
`jgit.storage.benchmark.staging-jdbc.payload_mib` (16, 128 or 512) and
`jgit.storage.benchmark.staging-jdbc.window_mib` (16 or 32). Without the native
flag, only the embedded HSQLDB trace and reopen check runs.

The reference writer runs the two phases in sequence. The smoke test verifies
that both can be attributed to the same publication. Before testing a pipeline,
[issue #189](https://github.com/carstenartur/jgit-storage-hibernate/issues/189)
still needs calibrated disk and network workloads showing that both phases take
enough time for overlap to affect end-to-end latency.

## Decision for local PostgreSQL

Keep the synchronous writer for local PostgreSQL with ordinary staging storage.
The measured rereads leave too little time to recover through double-buffering.
This decision covers the tested local setup. [#189](https://github.com/carstenartur/jgit-storage-hibernate/issues/189)
also covers slower staging storage paired with a remote database.

The 16 MiB GitHub Actions samples from [#442](https://github.com/carstenartur/jgit-storage-hibernate/pull/442)
used PostgreSQL 17.10 and a 16 MiB window. The writer must fill the first window
before it can send the first batch. Only the 5,419-byte tail can be read during
that batch. Across the three retained workflow runs (nine samples), perfect
overlap of the measured tail read would save at most 0.011% of publication time.

A local diagnostic on [`e834c1885d68`](https://github.com/carstenartur/jgit-storage-hibernate/commit/e834c1885d6857001a8838bded585c975054b481)
used the same trace and reopen checks against a disposable PostgreSQL 17.10
server with loopback TCP and one writer. A temporary test adapter supplied the
local connection in place of Testcontainers. A process-local startup shim
allowed the database to start in a single-UID sandbox; the database and JVM
read/write paths were unchanged. The Java 25 test JVM and database both used
container overlay storage. The database had `fsync`, synchronous commit, and
I/O timing enabled, with `max_wal_size=256MB` to limit scratch disk use. That
limit caused frequent checkpoints, and the staging files could be cached. These
measurements are local diagnostics and do not estimate production disk latency.

For each JDBC batch, the next staging window is the only window the two-buffer
scheme can read during that batch. Sum the smaller of each JDBC batch time and
the next window's traced read time, then divide by publication time. This is a
zero-overhead ceiling for overlapping the traced reads. As a deliberately
looser check, do the same calculation with the entire gap from the end of each
JDBC batch to the start of the next. The gap includes unrelated work, including
batch preparation. Both calculations exclude the first window and leave the
rest of publication unchanged.

| Payload / window | Samples | Publication | Summed staging reads | Summed chunk JDBC batches | Traced-read ceiling | Entire-gap ceiling |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 16 / 16 MiB (Actions) | 9 | 0.644–1.333 s | 1.314–2.352 ms | 106.008–407.493 ms | ≤0.011% | ≤0.118% |
| 128 / 16 MiB (local) | 3 | 4.726–5.810 s | 15.811–52.841 ms | 495.745–782.611 ms | ≤0.743% | ≤2.434% |
| 512 / 16 MiB (local) | 3 | 20.020–20.710 s | 123.679–321.120 ms | 2,142.741–2,794.993 ms | ≤1.533% | ≤3.095% |

Raw local files:

- 128 MiB [phase timeline](../evidence/staging-jdbc-local-2026-11-07/128-mib/phase-timeline.csv) and [PostgreSQL telemetry](../evidence/staging-jdbc-local-2026-11-07/128-mib/database-telemetry.json).
- 512 MiB [phase timeline](../evidence/staging-jdbc-local-2026-11-07/512-mib/phase-timeline.csv) and [PostgreSQL telemetry](../evidence/staging-jdbc-local-2026-11-07/512-mib/database-telemetry.json).

Every local publication passed the cold JGit cache reopen and SHA-256 check.
The 128 MiB samples wrote nine batches each and advanced the WAL position by
143.75–143.76 MB. The 512 MiB samples wrote 33 batches each and advanced it by
575.00–575.20 MB. The endpoint wait gauges showed zero I/O or lock waiters.
The snapshots report the state at each capture.

## Paired staging and RTT diagnostic

An additional local diagnostic used one writer, a 128 MiB payload, and a 16 MiB window on the
same source revision and disposable PostgreSQL setup. The shaped variant delayed the loopback
TCP stream by 25 ms in each direction and paced reads of the JVM's temporary JGit pack files to
a target of 150 MiB/s. The resulting JFR staging-read rate was 146.0–146.5 MiB/s. Nine `SELECT 1`
round trips over a persistent connection calibrated each network path. Every sample passed the
reopen and SHA-256 check.

| Conditions | Median query RTT | Publication | Traced staging reads | Chunk JDBC batches | Ideal eligible read overlap |
|---|---:|---:|---:|---:|---:|
| Direct loopback, unpaced staging | 0.147 ms | 4.271–4.563 s | 11.316–12.275 ms | 443.672–593.907 ms | 0.21–0.25% |
| Delayed loopback, paced staging | 51.068 ms | 5.973–6.224 s | 874.112–877.050 ms | 823.456–908.887 ms | 11.31–11.85% |

The paced run identifies a condition under which the two-buffer design could have headroom.
The percentages are ideal ceilings computed from a serial writer. A production decision needs
that condition to occur in a target deployment and a prototype to demonstrate the end-to-end
gain and rollback behavior. The native observations are endpoint snapshots, with timing of
database waits within a publication still open.

- Direct [phase timeline](../evidence/staging-jdbc-local-2026-11-07/paired-direct/phase-timeline.csv), [native telemetry](../evidence/staging-jdbc-local-2026-11-07/paired-direct/database-telemetry.json), and [RTT calibration](../evidence/staging-jdbc-local-2026-11-07/paired-direct/rtt-calibration.csv).
- Shaped [phase timeline](../evidence/staging-jdbc-local-2026-11-07/paired-shaped/phase-timeline.csv), [native telemetry](../evidence/staging-jdbc-local-2026-11-07/paired-shaped/database-telemetry.json), and [RTT calibration](../evidence/staging-jdbc-local-2026-11-07/paired-shaped/rtt-calibration.csv).
