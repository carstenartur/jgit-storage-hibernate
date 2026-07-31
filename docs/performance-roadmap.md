# Performance optimization roadmap

This document records the performance work that remains after adaptive small-pack persistence, atomic local staging, JDBC chunk batching, bounded multi-chunk read-ahead, committed-pack handoff, persistent logical pack metadata, real JGit protocol workloads and per-operation storage instrumentation.

## Implemented performance slices

- Store PACK, IDX and REFTABLE files up to 256 KiB in the existing inline payload column.
- Retain bounded one MiB chunks for larger files.
- Stage completed unpublished extensions in bounded local files and publish one logical pack atomically.
- Avoid durable database work for ordinary output-stream flush calls.
- Batch chunk inserts with the stable `(pack_id, chunk_index)` ORM identity.
- Compare PostgreSQL with Hibernate's built-in pool and HikariCP.
- Measure warm and JGit-cache-reset reads, open-repository ref resolution, 100-object batches and ten-commit series.
- Load up to sixteen consecutive chunks through one ordered Hibernate query when JGit requests sequential read-ahead.
- Align writable, inline and chunked DFS channels to the same one MiB block size.
- Restore persisted extension file sizes when rebuilding `DfsPackDescription` instances.
- Persist pack source, last-modified time, object and delta counts, index version and Reftable update indexes across repository reopen.
- Measure initial and incremental push through `ReceivePack` plus clone-style and incremental fetch through `UploadPack`.
- Cover incremental fetch from a server containing a base pack and a descendant pack through normal H2 and HSQLDB regression tests.
- Record Hibernate queries, prepared statements, transactions and connection acquisitions for every protocol workload.
- Record top-level repository transactions, commits, rollbacks and repository-lock acquisitions by stable operation category.
- Record repository-lock acquisition time, complete transaction duration and the actual interval for which a repository lock is held.
- Attribute committed database fallback reads by extension and inline/chunked representation.
- Hand locally published inline PACK and Reftable bytes to JGit through a hard-bounded repository-instance cache.
- Delete replaced logical packs with one parent bulk mutation and the existing database foreign-key cascade.
- Remove redundant pack, chunk and reflog indexes while retaining the measured access paths.

See [Protocol storage metrics](protocol-storage-metrics.md) for counter semantics and interpretation limits.

## Current protocol observations

The current 24-commit/4-commit protocol matrix shows:

- initial PostgreSQL push remains the clearest serial write-path target;
- atomic local staging substantially reduced locked transaction boundaries and improved incremental push in repeated measurements;
- clone-style and incremental fetch remain in the same broad latency range across filesystem, HSQLDB and PostgreSQL;
- HikariCP does not show a repeatable serial latency advantage;
- portable JDBC batching reduces statement execution but is not itself a dominant local end-to-end latency improvement;
- the committed-pack catalog and bounded local payload handoff remove deterministic metadata and local inline fallback reads;
- persisted logical pack metadata keeps JGit lookup ordering, Reftable ordering and future maintenance decisions stable after repository reopen;
- protocol testing exposed and fixed DFS block-alignment and persisted-file-size contracts that object-level benchmarks did not exercise.

The next implementation decision should use lock-held duration, transferred-byte counters and concurrent workload evidence, not the elapsed time of one serial push alone.

## Next implementation candidates

1. Add independent-`SessionFactory` reader/writer benchmarks for the same repository and for different repositories, reporting throughput plus p50, p95 and p99 latency.
2. Record payload bytes written to temporary files, read from temporary files, persisted to the database and fetched by read-ahead windows.
3. Measure repository-open and object-lookup cost after 1, 100 and 1,000 incremental pushes, including close/reopen boundaries.
4. Move large payload transfer before the repository publication lock when lock-held measurements show that serialized database transfer is material. The pre-persisted rows must remain invisible and lease-owned until one short atomic publication transaction validates and exposes them.
5. Evaluate a bounded memory-first staging buffer for extensions that remain below the inline threshold, spilling to a temporary file when the threshold or repository memory budget is exceeded.
6. Evaluate JGit multi-pack-index maintenance before full repack for repositories whose pack count grows substantially.
7. Add threshold-driven DFS garbage collection and repack after aging benchmarks establish useful pack-count and lookup-cost thresholds.
8. Compare one-, four- and sixteen-chunk read-ahead policies using fetched versus consumed bytes rather than query count alone.
9. Profile allocation and GC pressure in multi-hundred-MiB publication before replacing Hibernate chunk persistence with a lower-level JDBC writer.

## Deprioritized ideas

- Do not add a Hibernate second-level payload cache without evidence. JGit already owns the specialized DFS block cache, and the local inline handoff is deliberately narrow and bounded.
- Do not increase the read-ahead maximum merely because larger windows are possible. First measure fetched versus consumed bytes.
- Do not enable vendor-specific batch rewrite, bulk-copy or large-object options as library defaults. Benchmark them in the deployment that owns the JDBC driver, network and rollback policy.
- Do not reintroduce repeated durable flush persistence for normal staged writers. The measured atomic staging model removed those transaction and lock boundaries.

## Acceptance criteria for later optimizations

An optimization should be merged only when it:

- preserves the transactional pack/ref publication contract;
- passes H2, HSQLDB, PostgreSQL and SQL Server integration tests;
- passes the supported JGit compatibility matrix;
- improves at least one production-style workload without materially regressing another;
- records raw benchmark output and a reproducible Maven command;
- reconciles aggregate and per-operation storage metrics;
- states whether a claimed gain is latency, throughput, reduced lock-held time, reduced statements, reduced transferred bytes or reduced allocation;
- avoids turning filesystem comparisons into claims that database storage should match local page-cache latency for tiny synchronous writes.
