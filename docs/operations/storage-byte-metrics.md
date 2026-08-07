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

This distinction also applies after `close/reopen`: the reopened `HibernateRepository` starts a new local counter set. Capture a new baseline from that instance instead of subtracting a snapshot from the closed handle.

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

Consumption accounting examines only the requested range and keeps a running distinct-byte total. It does not rescan the complete one-MiB chunk for every read, and a seek followed by a repeated read of the same cached bytes does not increase the consumed counter again.

### Measured policy trade-off

The focused HSQLDB smoke matrix compared one-, four- and sixteen-chunk windows for three deliberately different access profiles. Values are point estimates from the smoke profile; the retained raw artifact is the evidence source rather than a claim of universal database timing.

#### Sequential 20-MiB stream

| Window | Elapsed | Chunk queries | Fetched | Overfetch |
|---:|---:|---:|---:|---:|
| 1 chunk | 26.57 ms | 21 | approximately 20 MiB | 0 |
| 4 chunks | 25.43 ms | 6 | approximately 20 MiB | 0 |
| 16 chunks | 14.40 ms | 2 | approximately 20 MiB | 0 |

Large sequential access benefits strongly from the sixteen-chunk upper bound: query count falls without fetching bytes that the caller does not consume.

#### Random 32 × 4-KiB reads

| Window | Elapsed | Chunk queries | Fetched | Approx. fetched/requested |
|---:|---:|---:|---:|---:|
| 1 chunk | 30.87 ms | 30 | 31.46 MiB | 240× |
| 4 chunks | 53.46 ms | 26 | 100.70 MiB | 768× |
| 16 chunks | 86.63 ms | 18 | 191.96 MiB | 1,464× |

For sparse random reads, reducing query count with a wider window is counterproductive because payload overfetch dominates.

#### Deliberately short 64-KiB read

| Window | Elapsed | Fetched | Approx. fetched/requested |
|---:|---:|---:|---:|
| 1 chunk | 6.50 ms | 1 MiB | 16× |
| 4 chunks | 7.42 ms | 4 MiB | 64× |
| 16 chunks | 10.65 ms | 16 MiB | 256× |

A short read should therefore remain at one chunk.

### Resulting production behavior

Core does not force one static window for every access. It continues to honor JGit's read-ahead hint and translates it into a bounded number of one-MiB chunks, capped at sixteen:

- sequential consumers may request the full sixteen-chunk window;
- random or short consumers should request one chunk;
- zero or small hints do not silently become sixteen-chunk prefetches.

This is preferable to a global default because the benchmark shows opposite optima for sequential and sparse access. Operational tuning should compare elapsed time, query count and overfetch together.

## Write amplification

Temporary-file traffic makes the current staging cost explicit:

```text
JGit write
  -> temporaryFileBytesWritten
  -> temporaryFileBytesRead during publication
  -> databasePayloadBytesWritten
```

For the measured 16-MiB large-pack publication, database payload bytes were only about 0.04% above logical Git payload. The larger application-level movement comes from one staging write and one staging reread before the database transfer. This identifies staging/copy elimination—not database row inflation—as the next possible write-amplification target.

If an inline extension never spills to disk, both temporary-file counters remain unchanged while database payload bytes remain identical.

## Boundaries

These are application-level logical byte counters. They do not include:

- SQL framing, row headers or index maintenance;
- JDBC protocol overhead;
- database WAL, page or replication amplification;
- compression performed below the Java payload boundary;
- JGit DFS block-cache hits that avoid this backend entirely.

Use database-native telemetry when WAL, network or physical storage bytes are required.
