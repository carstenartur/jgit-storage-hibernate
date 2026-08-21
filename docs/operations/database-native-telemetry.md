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
```

The write-queue coordinate includes the benchmark method, shared versus isolated repository scope, backend/pool variant, execution mode, stripe count, pool size, payload, thread count and measurement-iteration number.

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

The first integration is the real pack-layout benchmark because it already provides exact PostgreSQL and SQL Server write, sequential-read, short-read and random-read coordinates. This can answer questions such as:

- whether fewer chunk rows reduce transaction-log bytes as well as JDBC calls;
- whether a sequential-read improvement comes from fewer physical reads or only client-side effects;
- whether SQL Server large-chunk gains coincide with lower data-file I/O but higher sparse-read bytes;
- whether WAL/log flushing, page I/O or another wait category dominates a retained measurement.

The counters are observational. Cluster-wide PostgreSQL WAL/I/O and server-wide SQL Server waits can include background work. Production decisions still require repeated measurements and the existing cross-database correctness and regression budgets.

## Concurrent write-queue interpretation

The second integration brackets complete measurement iterations of `DurableWriteQueueBenchmark`. It covers direct publication and one/four/eight-stripe queues, shared-repository contention and independent repositories, and the internal Hibernate pool versus HikariCP at the same pool size. Iteration-level WAL, I/O and wait deltas can therefore be compared with JMH latency, queue delay, storage transactions, repository-lock time and payload amplification without pretending that a cluster-wide counter belongs to one overlapping invocation.

This is evidence for the durable scheduling/publication path, not completion of Git-aware combined receiver-record batching. A queued command still performs its full object insertion and ref update. The narrower atomic multi-record processor and its batch-size distribution remain work in #162 and #187.

## Remaining issue #187 work

This foundation does not close #187. Follow-up integrations must apply the same contract to:

1. repository aging, MIDX and repack;
2. atomic durable receiver-record batches beyond queue scheduling;
3. Hibernate Search incremental indexing and rebuild;
4. representative history queries;
5. safe focused plan capture without copying sensitive parameters or arbitrary SQL into artifacts;
6. dashboard charts for WAL/log bytes per logical MiB, logical/physical reads and wait categories.

Each additional integration must preserve zero telemetry queries when disabled and retain an exact benchmark coordinate in the companion artifact.
