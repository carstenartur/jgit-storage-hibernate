# Storage byte metrics

Core can expose repository-instance byte counters alongside the existing transaction, lock and pack-file read diagnostics.

Enable all storage diagnostics through the established property:

```properties
jgit.storage.hibernate.metrics.enabled=true
```

Read a monotone snapshot from `HibernateRepository`:

```java
StorageByteMetrics before = repository.getStorageByteMetrics();
// execute one measured operation
StorageByteMetrics delta = repository.getStorageByteMetrics().minus(before);
```

Diagnostics are disabled by default. A disabled repository returns `StorageByteMetrics.ZERO` and does not update byte counters on hot paths.

## Operational sampling

Snapshots are thread-safe, cumulative and local to one `HibernateRepository` instance. Calculate a delta only from an earlier snapshot of that same instance. Independent repository handles intentionally maintain independent counters even when they point to the same logical database repository.

For request-level telemetry, capture the first snapshot immediately before the operation and the second after every stream or readable channel opened by that operation has been closed. This produces a stable overfetch classification and avoids attributing unrelated concurrent work to the request.

## Counters

| Counter | Meaning |
|---|---|
| `temporaryFileBytesWritten` | Bytes physically appended while JGit constructs pack-related extensions in staging files. |
| `temporaryFileBytesRead` | Bytes physically read back from staging, both for JGit positional reads and database persistence. |
| `databasePayloadBytesWritten` | Inline and chunk payload bytes committed by successful payload transactions. A pre-persisted but later abandoned invisible generation still counts because those bytes crossed the database boundary. |
| `databasePayloadBytesRead` | Inline BLOB and chunk byte arrays materialized by database queries. Metadata-only queries do not count. |
| `readAheadBytesFetched` | Chunk bytes loaded into bounded channel-local read-ahead windows. |
| `readAheadBytesConsumed` | Distinct fetched bytes copied to a caller before the corresponding cached window was discarded. Re-reading the same cached bytes does not count twice. |
| `readAheadOverfetchBytes` | Fetched bytes discarded without being copied to a caller. The value becomes final for a window when it is evicted or its channel is closed. |

The counters describe traffic rather than unique Git data. One logical payload byte may legitimately be counted as a staging write, a staging read and a database write.

## Interpreting read-ahead

For a closed channel, the fetched bytes from every completed cache window reconcile as:

```text
readAheadBytesFetched
  = readAheadBytesConsumed
  + readAheadOverfetchBytes
```

A snapshot taken while a channel remains open may contain bytes that are fetched but not yet classified as consumed or overfetch. Close measured channels before using the relationship as a benchmark assertion.

A high overfetch ratio suggests that the configured JGit read-ahead hint or the sixteen-chunk upper bound is too aggressive for the workload. A low ratio with many chunk queries suggests the opposite. Neither result alone proves that a larger or smaller window improves end-to-end latency; compare query count, transferred bytes and elapsed time together.

## Write amplification

Temporary-file traffic makes the current staging cost explicit:

```text
JGit write
  -> temporaryFileBytesWritten
  -> temporaryFileBytesRead during publication
  -> databasePayloadBytesWritten
```

This provides the baseline for bounded memory-first staging. If an inline extension never spills to disk, both temporary-file counters should remain unchanged while database payload bytes remain identical.

## Boundaries

These are application-level logical byte counters. They do not include:

- SQL framing, row headers or index maintenance;
- JDBC protocol overhead;
- database WAL, page or replication amplification;
- compression performed below the Java payload boundary;
- JGit DFS block-cache hits that avoid this backend entirely.

Use database-native telemetry when WAL, network or physical storage bytes are required.
