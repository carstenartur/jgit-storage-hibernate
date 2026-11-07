# Database-native benchmark telemetry

Java, Hibernate and JDBC counters explain how much work the client requested. They do not show whether the remaining time was spent on transaction-log durability, page I/O, server waits, buffer hits, checkpoint pressure or index maintenance. Issue [#187](https://github.com/carstenartur/jgit-storage-hibernate/issues/187) adds a separate benchmark-only telemetry layer for that boundary.

## Safety and timing contract

Native telemetry is disabled by default:

```properties
jgit.storage.benchmark.database-telemetry.enabled=false
```

When disabled, the benchmark performs no telemetry connection or telemetry SQL. When explicitly enabled, snapshots bracket the narrowest boundary that can be attributed safely. The single-threaded pack-layout path captures one exact **measurement invocation** before cleanup. The concurrent write-queue path captures one complete **measurement iteration**, after all worker invocations have drained, because overlapping invocations share cluster-wide counters and cannot be attributed independently without double counting. Snapshot queries and JSON serialization remain outside timed benchmark methods.

Telemetry is diagnostic evidence, not an application runtime dependency. Core, Search and consumer APIs do not depend on the collector classes.

## Evidence format

Each exact benchmark coordinate produces one strict JSON observation containing:

- backend and server version;
- capture start and completion timestamps;
- cumulative-counter deltas;
- end-of-invocation gauges;
- relevant non-sensitive server settings;
- explicit unsupported capabilities and normalized failure categories.

The integrated runners retain companion files beside raw JMH evidence:

```text
pack-storage-layout-database-telemetry.json
write-queue-database-telemetry.json
read-ahead-database-telemetry.json
repository-aging-database-telemetry.json
reflog-batch-database-telemetry.json
search-database-telemetry.json
```

The write-queue coordinate includes the benchmark method, shared versus isolated repository scope, backend/pool variant, execution mode, stripe count, pool size, payload, thread count and measurement-iteration number. Repository-aging coordinates add pushes, maintenance mode, cache state and an explicit phase: deterministic fixture build, maintenance, provider restart when requested, or measured read operation. Reflog batch coordinates record backend, batch size and first application versus replay.

Counter resets and a counter missing from either snapshot are never converted to an invented value. They appear under `unsupported` as `counter-reset`, `missing-before-snapshot` or `missing-after-snapshot`. JSON serialization accepts only integral values and never emits `NaN` or Infinity.

## Privacy and redaction contract

The native telemetry companion JSON does not contain:

- JDBC URLs, database names, usernames or passwords;
- SQL parameters;
- statement or query text;
- raw JDBC exception messages.

Pack-layout Testcontainers credentials are passed to JMH forks through a short-lived owner-readable properties file outside the retained artifact directory. The file path, but not its contents, may appear in JMH VM arguments. The runner deletes the file before evidence is uploaded and scans raw JMH JSON and console output for direct connection properties and JDBC URLs.

A failed capability is represented by a normalized SQL state and vendor code, for example:

```text
sql-state=42501;vendor-code=0
```

This keeps permission and extension failures actionable without copying deployment-specific or potentially sensitive text into public workflow artifacts.

## PostgreSQL evidence

The PostgreSQL collector currently reads only cumulative statistics and non-sensitive settings:

- current WAL insert and flush positions converted to monotonic byte counters for immediate short-window deltas;
- `pg_stat_wal`: WAL records, full-page images, bytes, buffer-full events, writes, syncs and available write/sync timing;
- `pg_stat_database`: commits, rollbacks, block hits/reads, tuple activity, temporary files/bytes, deadlocks and available block timing;
- `pg_stat_io`: operation counts, byte estimates, timing, buffer hits, evictions, reuse and fsync evidence;
- `pg_stat_activity`: end-of-invocation active, I/O-waiting and lock-waiting session gauges;
- `pg_stat_statements`: aggregate calls, rows, block activity, execution time and WAL bytes only when the extension is installed and usable.

`pg_stat_statements` query text is deliberately not selected. If the extension is absent, not preloaded or unavailable to the benchmark user, that capability is marked unsupported while the remaining benchmark continues.

The artifact also records whether `track_io_timing` and `track_wal_io_timing` are enabled. A zero timing delta is not interpreted as proof of zero physical I/O when the relevant timing setting is disabled.

`pg_stat_wal`, `pg_stat_database` and `pg_stat_io` are cumulative statistics whose publication can lag a very short invocation. The WAL insert/flush-position deltas are therefore the primary immediate PostgreSQL byte signal; the statistics-view deltas remain complementary evidence. All cumulative views are observational and can include background work plus the collector's own read-only statements. The JSON metadata records this scope explicitly.

The serialized `startedAt`/`completedAt` interval begins only after the pre-invocation snapshot has completed and ends immediately before the post-invocation snapshot starts. Snapshot-query duration is therefore not presented as benchmark-window duration.

## SQL Server evidence

The SQL Server collector currently reads:

- `sys.dm_io_virtual_file_stats`: data-file and log-file reads, writes, bytes and stall time;
- `sys.dm_db_log_stats`: total and active log size, VLF counts and current truncation-holdup reason;
- `sys.dm_os_wait_stats`: cumulative `WRITELOG`, page-I/O, lock and network-wait categories;
- `sys.dm_db_index_usage_stats`: seeks, scans, lookups and updates for the current database;
- `sys.database_query_store_options`: Query Store state and configured/current storage size.

Some SQL Server 2022 DMVs require `VIEW SERVER PERFORMANCE STATE`. Missing permission marks only that capability unsupported. It does not make ordinary JMH evidence fail.

SQL Server wait statistics are server-scoped. They are useful on the isolated Testcontainers server used by the retained workflow, but a shared deployment needs additional correlation before a wait delta can be attributed exclusively to one benchmark.

## Current integration and interpretation

The [native evidence charts and selected raw values](../evidence/database-native-evidence-2026-11-07.md#charts) compare pack WAL, read-ahead payload, phase-specific log activity and SQL Server waits.

The first integration is the real pack-layout benchmark because it already provides exact PostgreSQL and SQL Server write, sequential-read, short-read and random-read coordinates. This can answer questions such as:

- whether fewer chunk rows reduce transaction-log bytes as well as JDBC calls;
- whether a sequential-read improvement comes from fewer physical reads or only client-side effects;
- whether SQL Server large-chunk gains coincide with lower data-file I/O but higher sparse-read bytes;
- whether WAL/log flushing, page I/O or another wait category dominates a retained measurement.

The counters are observational. Cluster-wide PostgreSQL WAL/I/O and server-wide SQL Server waits can include background work. Production decisions still require repeated measurements and the existing cross-database correctness and regression budgets.

## Read-ahead policy interpretation

`ReadAheadPolicyBenchmark` exercises the production pack channel with sequential, random and short
reads at one, four and sixteen chunks. The Performance Investigations workflow collects a native
PostgreSQL window for each measured invocation, keyed by method, backend, window size and
measurement invocation. Fixture publication, warmup reads and schema cleanup are outside the
window. The smoke profile retains nine PostgreSQL observations; the full profile retains 54 across
the two PostgreSQL parameter variants and keeps HSQLDB as a JMH-only control.

Compare native database block reads and buffer hits with JMH's fetched bytes, consumed bytes and
query count. A larger window can reduce SQL calls for sequential streaming while fetching more
bytes for sparse or short reads. PostgreSQL database block reads are buffer misses, not proof of
physical disk access: the operating system can still serve them from cache. Statistics publication
can lag these short windows and collector queries can contribute to database-wide counters, so a zero
delta is not evidence of zero I/O. The workflow checks that JMH and the native companions match and
scans the artifact before upload. The runner still supports a telemetry-disabled HSQLDB smoke.

## Concurrent write-queue interpretation

The second integration brackets complete measurement iterations of `DurableWriteQueueBenchmark`. It covers direct publication and one/four/eight-stripe queues, shared-repository contention and independent repositories, and the internal Hibernate pool versus HikariCP at the same pool size. Iteration-level WAL, I/O and wait deltas can therefore be compared with JMH latency, queue delay, storage transactions, repository-lock time and payload amplification without pretending that a cluster-wide counter belongs to one overlapping invocation.

This is evidence for the durable scheduling/publication path. A queued command in this benchmark still performs its full object insertion and ref update.

## Atomic reflog projection batch interpretation

The separate [Git-aware reflog batch contract](git-aware-reflog-batching.md#verification-and-native-telemetry) captures twelve windows: PostgreSQL and SQL Server, batch sizes 1/10/50, and first application versus idempotent replay. [Workflow run 32636555092](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/32636555092) passed the complete matrix, including positive WAL or transaction-log file writes for first application and an unchanged persisted row count on replay. These counters measure append-only reflog projection records, which have a different transaction boundary from pack publication or an authoritative ref update.

## Repository-aging and maintenance interpretation

The focused aging matrix separates three boundaries for every selected JMH coordinate on PostgreSQL and SQL Server:

1. deterministic incremental fixture publication;
2. the selected maintenance action (`none`, compact-only or read-optimized);
3. the later measured lookup/traversal/reopen iteration.

This prevents repack WAL/log and file-I/O work from being misattributed to the read that benefits from it. The bounded native profile uses ten pushes and representative oldest-object, clone-style and reopen lookups. PostgreSQL requires immediate WAL insert-LSN evidence; SQL Server requires transaction-log file-write evidence and retains data/log I/O and server-scoped wait categories from its isolated Testcontainers instance. The [repeated provider-restart profile](repository-aging-policy-evidence.md#provider-restart-evidence-at-ten-pushes) adds a fourth window around rebuilding the provider and pool. The [retained larger age matrix](../evidence/repository-aging-full-2026-09-04.md) measures latency and structural changes; it does not retain native counters across the larger ages.

## Hibernate Search interpretation

The [Search comparison](hibernate-search-performance.md) captures a PostgreSQL snapshot immediately
before and after each measured indexing, projection rebuild or query invocation. The window starts
after history creation and any prebuilt projection, and ends before the fixture is dropped. It
records the operation, index profile, commit count, query limit and measurement invocation so
observations can be matched to the JMH result without including SQL text or connection details.
Positive immediate WAL is required for retained indexing and rebuild evidence. Query counters are
observational; they are useful for comparing SQL-backed and Lucene-only paths, but PostgreSQL
cluster-wide WAL and I/O can include work outside the query.

The Search benchmark remains usable with telemetry disabled. The retained workflow enables it
and verifies that JSON and NDJSON contain one observation per measured invocation. Its fork receives
credentials through a temporary owner-readable file, and artifact upload is gated on a privacy scan.

## Focused pack-chunk read diagnostics

The native telemetry workflow also runs one separate, opt-in read probe on both
engines. It reproduces the ordered pack-chunk range lookup on a disposable
fixture with the same composite key shape (32 packs with 16 small chunks each;
one four-chunk window). Fixture creation, statistics refresh and teardown run
on a separate connection. The diagnostic executes only a fixed, parameterized
read, outside JMH and after the aging evidence has been uploaded.

- PostgreSQL executes [`EXPLAIN (ANALYZE, BUFFERS, WAL, FORMAT XML)`](https://www.postgresql.org/docs/17/sql-explain.html)
  in a read-only transaction. Its root operator reports shared-buffer hits and
  block reads, actual rows and loops, and WAL records, full-page images and
  bytes when reported. An execution time in milliseconds comes from the query
  summary. A shared-buffer miss may be served by the OS cache.
- SQL Server uses [`SET STATISTICS XML ON`](https://learn.microsoft.com/en-us/sql/t-sql/statements/set-statistics-xml-transact-sql)
  on a dedicated connection that closes after the fixed read. The artifact
  retains root operator rows, executions and logical/physical reads, summed
  across threads when reported. Query CPU and elapsed milliseconds are included
  when available. The probe also counts actual-plan `SpillToTempDb` warnings;
  the count is a warning indicator and does not estimate spill volume. SQL
  Server requires `SHOWPLAN` on the fixture database.

Both engines use lock, JDBC query and socket timeouts; PostgreSQL also enforces
a server statement timeout. The workflow retains only
`focused-read-plan-<backend>.json`. The plan XML never leaves memory: only a
fixed operator category, normalized server version and allowlisted numeric
fields are serialized. The probe does not require any extensions. Missing
counters are omitted; unsupported results use fixed categories, including
`permission-denied` and `timeout`. CI requires a supported result on its
disposable databases. A failed diagnostic does not affect JMH or remove the
already uploaded aging evidence.

The probe tests plan capture and shows how each engine accesses the indexed
fixture. It does not measure production pack size, cold-cache reads, application
latency or the effect of changing a storage default. This indexed range does
not exercise SQL Server tempdb spills. PostgreSQL shared buffers
and SQL Server logical reads are engine-specific signals, not directly
interchangeable counts.

To run the focused probe with Testcontainers, first build the benchmark module
with `mvn -B -pl jgit-storage-hibernate-benchmarks -am install -DskipTests`.
Then select `postgresql` or `sqlserver`:

```bash
mvn -B -pl jgit-storage-hibernate-benchmarks test \
  -Dtest=DatabaseTelemetryFocusedReadTest,DatabaseTelemetryFocusedReadIntegrationTest \
  -Djgit.storage.benchmark.database-telemetry.focused-read.enabled=true \
  -Djgit.storage.benchmark.database-telemetry.focused-read.backend=postgresql \
  -Djgit.storage.benchmark.database-telemetry.focused-read.output=/tmp/focused-read-plan.json
```

## Coverage boundaries

The retained native evidence covers bounded repository aging and the append-only
reflog batch contract. The full-scale MIDX and repack series, additional receiver
workloads and larger Search workloads each need separate native observations for
capacity planning. Any additional integration should preserve zero telemetry
queries when disabled and retain an exact benchmark coordinate in its artifact.
