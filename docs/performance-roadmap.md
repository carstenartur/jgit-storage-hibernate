# Performance optimization roadmap

This document records the performance work that remains after adaptive small-pack persistence, bounded chunk read-ahead and the expanded JMH workload matrix.

## Implemented in the current performance slices

- Store PACK, IDX and REFTABLE files up to 256 KiB in the existing inline payload column.
- Retain bounded one MiB chunks for larger files.
- Avoid deleting chunk rows before the first persistence of a new large file.
- Compare PostgreSQL with Hibernate's built-in pool and HikariCP.
- Measure warm and JGit-cache-reset reads, open-repository ref resolution, 100-object batches and ten-commit series.
- Translate JGit read-ahead requests into one ordered query for up to sixteen consecutive chunks without holding a Session or connection for the channel lifetime.
- Measure a non-compressible multi-chunk blob through JGit's public streaming API.

## Next implementation candidates

1. Persist growing large pack files incrementally by replacing only the previous partial chunk and appending new chunks after a repeated flush.
2. Add `ReceivePack` initial/incremental push and `UploadPack` clone/fetch benchmarks so protocol-level improvements can be evaluated directly.
3. Record SQL statement counts, transaction counts, connection acquisitions, transferred payload bytes and repository-lock wait time per workload.
4. Compare one-, four- and sixteen-chunk read-ahead windows before changing the current bounded maximum or selecting a non-zero default independent of JGit's request.
5. Replace identity-generated chunk IDs with a key strategy that permits real JDBC insert batching.
6. Measure 16, 32 and 64 statement batch sizes before selecting a default.
7. Add independent-`SessionFactory` concurrency benchmarks with reader/writer mixes.
8. Evaluate finer lock granularity only after contention measurements demonstrate that the repository lock is the limiting resource.
9. Consider a small generation-aware metadata cache for pack manifests and ref-stack versions; do not duplicate JGit object payloads in Hibernate's second-level cache without evidence.

## Acceptance criteria for later optimizations

An optimization should be merged only when it:

- preserves the transactional pack/ref publication contract;
- passes H2, HSQLDB and PostgreSQL integration tests;
- improves at least one production-style workload without materially regressing another;
- records raw benchmark output and a reproducible Maven command;
- avoids turning filesystem comparisons into claims that database storage should match local page-cache latency for tiny synchronous writes.
