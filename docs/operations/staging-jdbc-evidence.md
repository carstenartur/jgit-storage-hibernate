# Measuring synchronous pack staging and JDBC writes

The [JMH Benchmarks workflow](../../.github/workflows/performance.yml) includes a
PostgreSQL 17 smoke test that publishes a 16 MiB incompressible JGit object three
times through the synchronous writer with its 16 MiB window. The test verifies
the SHA-256 digest after closing and reopening the repository. PostgreSQL runs
with `fsync`, synchronous commit and I/O timing enabled.

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
