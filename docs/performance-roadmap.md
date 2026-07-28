# Performance optimization roadmap

This document records the performance work that remains after adaptive small-pack persistence, bounded multi-chunk read-ahead and the real JGit protocol workload matrix.

## Implemented performance slices

- Store PACK, IDX and REFTABLE files up to 256 KiB in the existing inline payload column.
- Retain bounded one MiB chunks for larger files.
- Avoid deleting chunk rows before the first persistence of a new large file.
- Compare PostgreSQL with Hibernate's built-in pool and HikariCP.
- Measure warm and JGit-cache-reset reads, open-repository ref resolution, 100-object batches and ten-commit series.
- Load up to sixteen consecutive chunks through one ordered Hibernate query when JGit requests sequential read-ahead.
- Align writable, inline and chunked DFS channels to the same one MiB block size.
- Restore persisted extension file sizes when rebuilding `DfsPackDescription` instances.
- Measure initial and incremental push through `ReceivePack` plus clone-style and incremental fetch through `UploadPack`.
- Cover incremental fetch from a server containing a base pack and a descendant pack through normal H2 and HSQLDB regression tests.

## Current protocol observations

The first 24-commit/4-commit protocol matrix shows:

- initial PostgreSQL push remains slower than the local filesystem point estimate and is the clearest write-path target;
- PostgreSQL incremental push was competitive in the first run, although push confidence intervals remain wide;
- clone-style and incremental fetch are in the same broad latency range across filesystem, HSQLDB and PostgreSQL;
- HikariCP does not show a repeatable serial latency advantage;
- protocol testing exposed and fixed DFS block-alignment and persisted-file-size contracts that object-level benchmarks did not exercise.

The next implementation decision must use per-operation storage counters instead of relying only on elapsed time.

## Next implementation candidates

1. Record Hibernate queries, prepared statements, transactions and connection acquisitions for each protocol workload.
2. Record top-level repository transactions, repository-lock acquisitions and end-to-end lock acquisition time.
3. Persist growing large pack files incrementally by replacing only the previous partial chunk and appending new chunks after a repeated flush.
4. Replace identity-generated chunk IDs with a key strategy that permits real JDBC insert batching.
5. Measure 16, 32 and 64 statement batch sizes before selecting a default.
6. Record transferred pack bytes and database payload bytes per workflow.
7. Compare one-, four- and sixteen-chunk read-ahead windows.
8. Add independent-`SessionFactory` concurrency benchmarks with reader/writer mixes.
9. Evaluate finer lock granularity only after contention measurements demonstrate that the repository lock is the limiting resource.
10. Consider a small generation-aware metadata cache for pack manifests and ref-stack versions; do not duplicate JGit object payloads in Hibernate's second-level cache without evidence.

## Acceptance criteria for later optimizations

An optimization should be merged only when it:

- preserves the transactional pack/ref publication contract;
- passes H2, HSQLDB and PostgreSQL integration tests;
- passes the supported JGit compatibility matrix;
- improves at least one production-style workload without materially regressing another;
- records raw benchmark output and a reproducible Maven command;
- avoids turning filesystem comparisons into claims that database storage should match local page-cache latency for tiny synchronous writes.
