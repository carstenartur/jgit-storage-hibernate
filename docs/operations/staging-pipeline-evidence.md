# Repeating the bounded staging experiment

The production pack writer remains synchronous. The benchmark-only experiment
compares synchronous, virtual-reader and platform-reader transfers of one staged
payload through transaction commit. It verifies every reopened byte by SHA-256.
The payload is a deterministic file generated in 64-KiB blocks; it is shared by
the selected writers and is never allocated as one large heap array.

These measurements do **not** include Git pack construction, ref publication,
protocol negotiation or a complete Git push. They cannot establish a full Git
publication gain. The earlier [27 native observations](../evidence/staging-pipeline-2026-11-07/README.md)
retain the same narrower boundary.

## Native evidence profiles

The [Bounded Staging Pipeline Evidence workflow](../../.github/workflows/staging-pipeline-evidence.yml)
provides three explicit profiles. Pull requests run one smoke coordinate. A
controlled `evidence/staging-pipeline-*` branch runs the eight coverage coordinates.
Manual dispatch defaults to the smoke profile; the full matrix is opt-in.
At most two cells run concurrently, and each cell has a 60-minute limit.

| Profile | Coordinates | Purpose |
| --- | ---: | --- |
| `smoke` | 1 | 16 MiB, 16 MiB window, one writer, runner storage, direct PostgreSQL |
| `coverage` | 8 | Includes 512 MiB, 4/16 writers, 16/32 MiB windows, runner/tmpfs/paced staging and 0/5/20/50 ms RTT |
| `full` | 216 | The Cartesian product of the original issue's payload, window, writer, storage and RTT dimensions |

Coverage and full profiles execute one unretained warm-up round per mode, followed
by the selected 1, 3, 5 or 9 repetitions. Mode order rotates between retained
rounds. A single-window control uses 16 MiB with a 32 MiB window. Coverage is a
bounded selection of useful controls; it does not qualify all 216 coordinates.

PostgreSQL 17.10 runs with `fsync`, synchronous commit and I/O timing enabled.
Toxiproxy 2.12.0 supplies the requested RTT, and nine retained `SELECT 1` samples
over one persistent connection calibrate the actual route. Native telemetry uses
the direct route. Runner and tmpfs cells use unpaced reads; paced cells target
150 MiB/s. The actual staging filesystem is recorded and a tmpfs cell fails unless
the fixture really resides on tmpfs. Runner-file reads can still be served from
the operating system cache. No cell claims to measure physical disk latency.

Every writer retains at most two configured payload windows. The maximum at
16 writers and 32 MiB is 1,024 MiB of live pipeline payload arrays. JDBC, Hibernate,
the repository reader and JVM require additional heap; the workflow caps the JVM
at 4 GiB. A 512 MiB payload changes the number of bounded transfers, not the
per-writer window bound. The shared source file is written once and read once by
each writer. The raw trial reports both counts separately.

## Failure boundary and rollback latency

Each retained round also exercises both failure directions for every reader mode
using **one rollback writer**, independently of the successful trial's writer
count. The source fixture exceeds one writer window. A reader failure occurs at
the next window; a real database CHECK constraint rejects chunks after the first
window. The experiment verifies that one complete window reached the existing
chunk writer before the failure and that pack/chunk row counts return to their
pre-publication values.

`failure_to_return_nanos` starts where the injected read exception or JDBC failure
is detected and ends when the failed publication returns after cancelling and
joining its reader, rolling back and closing the owner session.
`rollback_nanos` measures the transaction's `rollback()` call alone. The later
verification queries and constraint removal are outside both timings. These are
native single-writer failure observations, not multiwriter fault-injection or
process-crash recovery measurements.

## Artifacts and qualification

Every cell uploads raw `trials.csv`, `timeline.csv`, `rollback.csv`,
`rtt-calibration.csv`, native telemetry and `environment.properties` alongside its
Maven and Surefire reports. The environment includes the exact source commit,
warm-up count, heap limit, filesystem and measurement boundary. Qualification
requires all modes and repetitions, both rollback directions, positive native WAL
movement, calibrated RTT, consistent phase totals and byte counts, correct window
bounds, a measured paced-read limit within 10% timer tolerance, matching native
coordinates, and no rows leaked by failures. A failed or incomplete cell retains its
logs without receiving a qualified summary, provided those logs pass the connection
privacy scan. The scan runs even after build or benchmark failure, covers all raw
output, build/native logs and Surefire reports, and blocks artifact upload unless
it succeeds. Both test fixtures suppress connection metadata before constructing
their Hibernate provider.

The standard-library [evidence tool](../../.github/scripts/staging_pipeline_evidence.py)
produces a JSON summary and an SVG chart from qualified raw files. The chart shows
staged transfer latency, heap upper estimates, process CPU and a same-writer phase
timeline. Heap estimates sum per-pool peaks that can occur at different times.
Native counter snapshots bracket a publication; they do not time waits within it.

With Docker and Java 21 or later, a single cell can also be collected directly:

```bash
mvn -B -pl jgit-storage-hibernate-benchmarks -am install -DskipTests
export JGIT_EVIDENCE_SOURCE_SHA="$(git rev-parse HEAD)"
prefix=jgit.storage.benchmark.staging-pipeline
mvn -B -pl jgit-storage-hibernate-benchmarks test \
  '-Dtest=StagedChunkPipelineEvidenceTest#recordsPostgresqlPipelineEvidence' \
  '-DargLine=-Xmx4g' \
  "-D$prefix.enabled=true" "-D$prefix.payload_mib=512" \
  "-D$prefix.window_mib=32" "-D$prefix.writers=4" \
  "-D$prefix.requested_rtt_millis=50" "-D$prefix.read_mib_per_second=150" \
  "-D$prefix.storage=paced" "-D$prefix.repeats=3" "-D$prefix.warmups=1" \
  "-D$prefix.output=$PWD/target/staging-pipeline"
python3 .github/scripts/staging_pipeline_evidence.py summarize \
  target/staging-pipeline --expected-commit "$JGIT_EVIDENCE_SOURCE_SHA"
```

## Production decision

Keep the synchronous production writer. Issue [#189](https://github.com/carstenartur/jgit-storage-hibernate/issues/189)
requires repeatable non-regression and complete Git publication measurements in
addition to transfer evidence. Warm-up and repeated native cells make transfer
comparisons more useful, but do not remove that acceptance gate. Record native
run links and exact successful coordinates before treating requested matrix
coverage as completed evidence.
