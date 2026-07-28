# Performance optimization roadmap

This document records the performance work that remains after adaptive small-pack persistence and the expanded JMH workload matrix.

## Implemented in the current performance slice

- Store PACK, IDX and REFTABLE files up to 256 KiB in the existing inline payload column.
- Retain bounded one MiB chunks for larger files.
- Rewrite only the previous partial chunk and appended chunks after a repeated flush.
- Compare PostgreSQL with Hibernate's built-in pool and HikariCP.
- Measure warm and JGit-cache-reset reads, open-repository ref resolution, 100-object batches and ten-commit series.

## Next implementation candidates

1. Replace identity-generated chunk IDs with a key strategy that permits real JDBC insert batching.
2. Measure 16, 32 and 64 statement batch sizes before selecting a default.
3. Implement chunk read-ahead and compare one-, four- and sixteen-chunk windows.
4. Add `ReceivePack` initial/incremental push and `UploadPack` clone/fetch benchmarks.
5. Record SQL statement counts, transaction counts, connection acquisitions and repository-lock wait time per workload.
6. Add independent-`SessionFactory` concurrency benchmarks with reader/writer mixes.
7. Evaluate finer lock granularity only after contention measurements demonstrate that the repository lock is the limiting resource.
8. Consider a small generation-aware metadata cache for pack manifests and ref-stack versions; do not duplicate JGit object payloads in Hibernate's second-level cache without evidence.

## Acceptance criteria for later optimizations

An optimization should be merged only when it:

- preserves the transactional pack/ref publication contract;
- passes H2, HSQLDB and PostgreSQL integration tests;
- improves at least one production-style workload without materially regressing another;
- records raw benchmark output and a reproducible Maven command;
- avoids turning filesystem comparisons into claims that database storage should match local page-cache latency for tiny synchronous writes.
