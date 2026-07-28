# Benchmarks

`jgit-storage-hibernate` uses JMH for repeatable performance measurements and publishes the results as time-series charts.

The benchmark module is:

```text
jgit-storage-hibernate-benchmarks
```

It is not a runtime dependency for consumers. It exists for CI, maintainers and release review.

## Repository backend comparison

The storage benchmark runs the same public JGit workload against four repository configurations:

| Backend label | Implementation used by the benchmark | Environment |
|---|---|---|
| `JGit + filesystem` | JGit `FileRepository` | Fresh temporary bare repository |
| `JGit + HSQLDB (in-memory)` | `HibernateRepository` | HSQLDB 2.7 in-memory database with Hibernate's built-in pool |
| `JGit + PostgreSQL` | `HibernateRepository` | PostgreSQL 17.10 Testcontainer with Hibernate's built-in pool |
| `JGit + PostgreSQL + HikariCP` | `HibernateRepository` | The same PostgreSQL container with `hibernate-hikaricp`, maximum pool size 4 and minimum idle 1 |

The built-in PostgreSQL configuration is retained as a stable baseline. Hibernate documents that its built-in pool is not intended for production; the HikariCP variant shows whether a production-grade pool changes steady-state repository latency.

The measured methods use public JGit repository and transport APIs. Backend construction, schema creation and client preparation happen outside measured invocations.

## Measured workloads

The comparison covers thirteen operations in four categories.

### Fixed-cost probes

```text
writeBlob
writeCommitAndUpdateRef
reopenAndResolveMain
```

These intentionally expose the fixed cost of transactions, pack publication, ref locking and repository reconstruction. A tiny synchronously flushed object is not representative of bulk throughput, but it is useful for detecting regressions in application-style single-commit operations.

### Read paths

```text
readBlobFromWarmCache
readBlobAfterJGitCacheReset
readLargeBlobSequentiallyAfterJGitCacheReset
resolveMainOnOpenRepository
```

`readBlobFromWarmCache` measures repeated application-level retrieval through JGit's normal caches. It is deliberately not described as a physical filesystem or database read.

`readBlobAfterJGitCacheReset` clears JGit's DFS block cache immediately before lookup. Operating-system and database caches remain warm, so the result represents a JGit-cache-cold application read rather than cold storage hardware.

`readLargeBlobSequentiallyAfterJGitCacheReset` streams a deterministic non-compressible object slightly larger than two MiB. The object crosses multiple database chunks and approximates the sequential access pattern of clone, fetch and large binary-object export.

`resolveMainOnOpenRepository` measures the common steady-state operation of resolving a frequently used ref without rebuilding the repository object.

### Amortized application workloads

```text
writeBatchOf100Blobs
writeCommitSeries10AndUpdateMain
```

`writeBatchOf100Blobs` inserts 100 unique roughly one KiB blobs through one `ObjectInserter` and performs one flush. It shows whether pack and transaction overhead can be amortized across a batch.

`writeCommitSeries10AndUpdateMain` writes ten linked commits through one inserter, flushes once and publishes the final commit with one update of `refs/heads/main`. This approximates an imported, synchronized or server-side generated change set.

### Real Git protocol workloads

```text
initialPushViaReceivePack
incrementalPushViaReceivePack
initialCloneViaUploadPack
incrementalFetchViaUploadPack
```

These workloads use JGit's in-process `TestProtocol`, `ReceivePack`, `UploadPack` and normal `Transport` client implementation. The server repository uses the selected storage backend; clients use fresh in-memory repositories so client filesystem noise does not hide server storage behavior.

`initialPushViaReceivePack` transfers an unrelated 24-commit history with non-compressible 32 KiB payloads and creates a new remote branch.

`incrementalPushViaReceivePack` starts with a 20-commit base already present on the server and transfers four descendants plus the fast-forward ref update.

`initialCloneViaUploadPack` fetches the complete 24-commit history into an empty bare client repository. It measures clone-style pack negotiation and transfer, but intentionally excludes working-tree checkout.

`incrementalFetchViaUploadPack` prepares a client with the 20-commit base and then fetches only four descendants.

The repository and object-level probes use JMH average time in `ms/op`. Protocol workflows use single-shot time with one warm-up and five measured fresh repositories, avoiding accumulated repository growth across samples. Lower values are better. Batch and protocol operations report time per complete operation, not per individual object or commit.

The first complete protocol run produced these point estimates:

| Operation | Filesystem | HSQLDB | PostgreSQL | PostgreSQL + HikariCP |
|---|---:|---:|---:|---:|
| Initial push, 24 commits | 133.3 ms | 203.6 ms | 220.6 ms | 220.3 ms |
| Incremental push, 4 commits | 53.0 ms | 70.2 ms | 42.2 ms | 76.1 ms |
| Initial clone-style fetch | 129.6 ms | 113.0 ms | 117.5 ms | 128.1 ms |
| Incremental fetch, 4 commits | 18.8 ms | 18.6 ms | 23.9 ms | 19.8 ms |

The single-shot confidence intervals are still wide, especially for pushes. These values establish workload scale and regression history; they are not yet production sizing claims. They do show that database storage is competitive for clone/fetch and incremental workflows, while initial durable ingestion remains the clearest optimization target. HikariCP does not provide a repeatable serial latency advantage in this matrix.

## Adaptive pack persistence and read-ahead under test

The Hibernate backend stores small PACK, IDX and REFTABLE payloads up to 256 KiB in the existing inline payload column. Larger payloads continue to use bounded one MiB chunks.

This removes the additional chunk row, chunk insert and preliminary chunk delete from common small application commits. New large files also skip the previously unconditional delete before their first chunk insert. Repeated flushes of an already persisted large file still use the conservative full-rewrite path; incremental append-only chunk persistence remains a separately measured follow-up.

For sequential large reads, JGit's requested read-ahead window is translated into one ordered Hibernate query for up to sixteen consecutive chunks. The cache is local to the readable channel, is cleared on unrelated seeks, and does not keep a Hibernate session or JDBC connection open between reads. A core H2 test requires three consecutive chunks to be served with one query and retains hard failure on missing or corrupt intermediate chunks.

Writable, inline and chunked channels report the same one MiB DFS alignment. Persisted pack descriptions restore every extension's file size when the pack list is rebuilt. Both contracts are required by JGit's `UploadPack` copy-as-is path. H2 and HSQLDB regression tests exercise incremental fetch from a server containing a base pack plus a descendant pack after cache and pack-list reload.

The core migration, deletion and roundtrip tests accept both valid payload representations while still requiring every committed non-empty file to have exactly one representation. Historical inline rows remain readable, and large pack capacity remains bounded through the chunk table.

## Maven, JUnit and Testcontainers architecture

The canonical entry point is the Maven profile `benchmark-comparison`.

Maven Failsafe runs `RepositoryBackendBenchmarkIT`. That JUnit integration test:

1. starts PostgreSQL with the repository's established Testcontainers/JUnit extension pattern;
2. obtains the dynamically mapped JDBC URL and credentials;
3. launches JMH forks for filesystem, HSQLDB, PostgreSQL built-in pooling and PostgreSQL HikariCP;
4. passes the Testcontainers connection properties to every PostgreSQL JMH fork;
5. asserts that all thirteen operations were recorded for all four configurations;
6. writes the raw JMH JSON and text output.

GitHub Actions does not declare or manage a PostgreSQL service. The workflow only sets up Java and invokes Maven. The same Maven profile is therefore executable from a normal checkout on any machine with Java 21, Maven and Docker.

The profile is not active during ordinary `mvn verify`, so routine builds do not unexpectedly run a performance suite or start a container.

## Public performance history

The benchmark workflow records successful `main` results with the repository-owned history publisher and deploys the chart dashboard at:

```text
https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/
```

Pull requests execute the benchmark and upload the raw artifacts, but do not modify the public history. This prevents temporary pull-request revisions from becoming chart data.

Each operation/configuration pair is stored as a separate time series, for example:

```text
initialPushViaReceivePack — JGit + PostgreSQL
initialCloneViaUploadPack — JGit + filesystem
readLargeBlobSequentiallyAfterJGitCacheReset — JGit + PostgreSQL + HikariCP
```

The raw JMH output remains available as a workflow artifact beside the converted comparison JSON and Maven logs.

## Run locally

Docker must be available for Testcontainers. No database URL, fixed port, password or manually managed `docker run` command is required.

First build and install the benchmark module and its reactor dependencies without executing tests:

```bash
mvn -B -pl jgit-storage-hibernate-benchmarks -am install -DskipTests
```

Then run the dedicated JUnit/Testcontainers comparison through Maven:

```bash
mkdir -p target/benchmarks
mvn -B -pl jgit-storage-hibernate-benchmarks verify \
  -Pbenchmark-comparison \
  -Dbenchmark.resultFile="$(pwd)/target/benchmarks/jmh-result.json"
```

The important outputs are:

```text
target/benchmarks/jmh-result.json
target/benchmarks/jmh-output.txt
jgit-storage-hibernate-benchmarks/target/failsafe-reports/
```

The JUnit integration test fails if JMH does not return exactly thirteen operations for each of the four configured backends.

### Convert local JMH output to chart input

```bash
python3 .github/scripts/convert-jmh-backend-comparison.py \
  target/benchmarks/jmh-result.json \
  target/benchmarks/backend-comparison.json
```

## CI workflow

`.github/workflows/performance.yml` performs only repository-independent orchestration:

```text
checkout
setup Java
mvn install -DskipTests
mvn verify -Pbenchmark-comparison
convert and publish results
```

PostgreSQL creation, readiness, mapped-port selection and cleanup remain inside JUnit/Testcontainers. This keeps the behavior locally reproducible and avoids encoding integration-test infrastructure in GitHub Actions.

## Interpretation limits

This comparison is a controlled regression and architecture benchmark, not a complete production sizing exercise:

- HSQLDB is deliberately in-memory, while filesystem and PostgreSQL perform operating-system or database I/O;
- warm and JGit-cache-reset reads do not clear the operating-system page cache or PostgreSQL shared buffers;
- PostgreSQL runs locally in a Testcontainers container rather than over a production network;
- host and container performance vary, especially for I/O-heavy measurements;
- HikariCP cannot remove transaction, locking, WAL or ORM costs and is primarily expected to help concurrent or connection-heavy use;
- the single-operation probes exaggerate fixed durable-publication cost, while the batch workloads show amortized throughput;
- the large sequential-read benchmark measures decompression and JGit streaming in addition to database chunk access;
- protocol benchmarks use an in-process transport and therefore exclude network latency, TLS and HTTP/SSH server overhead.

The workflow therefore uses a conservative 150% regression alert threshold and keeps the raw JMH JSON for deeper investigation.

## Next benchmark slices

The next high-value storage measurements are write-path and concurrency scenarios:

- record SQL statement counts, transaction counts, connection acquisitions and repository-lock acquisition time per protocol workload;
- use those counts to select incremental pack persistence or JDBC batching as the next implementation;
- record transferred pack bytes and database payload bytes per workflow;
- compare one-, four- and sixteen-chunk read-ahead windows with query counts and transferred bytes;
- add concurrent readers and writers using independent `SessionFactory` instances;
- report p50, p95 and p99 latency for contended workloads;
- measure repository-open cost with 1, 100 and 10,000 packs or refs.

The Java analysis module also exposes semantic-history queries based on `JavaProjectAnalyzer`, `JavaSemanticDiff` and `SemanticHistoryQuery`. Symbol extraction, semantic diff and moved-symbol query latency should remain a separate benchmark suite so storage and analysis regressions are not conflated.
