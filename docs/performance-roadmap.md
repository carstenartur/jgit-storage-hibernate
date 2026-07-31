# Performance optimization roadmap

This document records the performance work that remains after adaptive small-pack persistence, atomic local staging, JDBC chunk batching, bounded multi-chunk read-ahead, committed-pack handoff, persistent logical pack metadata, read-optimized DFS maintenance, repository-scoped concurrency measurement, adaptive large-payload pre-persistence, real JGit protocol workloads and per-operation storage instrumentation.

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
- Expose JGit DFS garbage collection and repack with single-pack compaction, bitmaps, commit graph, changed-path Bloom filters and Reftable compaction through `PackStorageMaintenance`.
- Compare four independent `SessionFactory` writers publishing to one shared repository with writers publishing to four independent repositories.
- Separate durable repository lifecycle ownership from the pessimistically locked publication-coordination row.
- Pre-persist chunked extensions as invisible leased rows before acquiring the repository publication lock, while retaining the one-transaction path for inline extensions.

See [Protocol storage metrics](protocol-storage-metrics.md) for counter semantics and interpretation limits. See [Pack capacity and recovery](operations/capacity-and-recovery.md) for payload publication and crash recovery. See [Repack, garbage collection and read acceleration](operations/repack-and-gc.md) for the maintenance contract.

## Current protocol and concurrency observations

The current 24-commit/4-commit protocol and four-thread publication matrices show:

- initial PostgreSQL push remains the clearest serial write-path target;
- atomic local staging substantially reduced locked transaction boundaries and improved incremental push in repeated measurements;
- clone-style and incremental fetch remain in the same broad latency range across filesystem, HSQLDB and PostgreSQL;
- HikariCP does not show a repeatable serial latency advantage;
- portable JDBC batching reduces statement execution but is not itself a dominant local end-to-end latency improvement;
- the committed-pack catalog and bounded local payload handoff remove deterministic metadata and local inline fallback reads;
- persisted logical pack metadata keeps JGit lookup ordering, Reftable ordering and maintenance decisions stable after repository reopen;
- read-optimized maintenance can trade background CPU and auxiliary index bytes for fewer active packs and lower future graph-walk work without weakening atomic publication;
- JGit's DFS garbage collector does not currently emit a persisted reverse-index extension, so the library does not expose a misleading option for it;
- independent logical repositories retain substantially more PostgreSQL publication throughput than four writers contending for one repository lock row;
- the extra `SessionFactory` and connection-pool abstraction is not the cause of that gap: built-in pooling and Hikari show the same shared-repository contention pattern;
- chunk transfer can therefore execute before the lock, but visibility, replacement and writer validation must remain one short atomic publication transaction;
- protocol testing exposed and fixed DFS block-alignment and persisted-file-size contracts that object-level benchmarks did not exercise.

Performance claims for adaptive pre-persistence must compare the same benchmark before and after the change. The important outputs are shared-repository throughput, independent-repository throughput, lock-acquisition time, lock-held time and total transaction duration—not merely the elapsed time of one serial push.

## Next implementation candidates

1. Record payload bytes written to temporary files, read from temporary files, persisted to the database and fetched by read-ahead windows.
2. Measure repository-open and object-lookup cost after 1, 100 and 1,000 incremental pushes, including close/reopen boundaries and before/after repack comparisons.
3. Evaluate a configurable pre-persistence threshold only after the standard chunked workload, larger multi-chunk payloads and independent-repository throughput establish that one universal representation boundary is insufficient.
4. Evaluate a bounded memory-first staging buffer for extensions that remain below the inline threshold, spilling to a temporary file when the threshold or repository memory budget is exceeded.
5. Evaluate JGit multi-pack-index maintenance before full repack for very large repositories where rewriting payloads is more expensive than retaining multiple packs.
6. Derive automatic maintenance thresholds from aging benchmarks instead of hard-coding a universal pack-count trigger.
7. Compare one-, four- and sixteen-chunk read-ahead policies using fetched versus consumed bytes rather than query count alone.
8. Profile allocation and GC pressure in multi-hundred-MiB pre-persistence before replacing Hibernate chunk persistence with a lower-level JDBC writer.

## Deprioritized ideas

- Do not add a Hibernate second-level payload cache without evidence. JGit already owns the specialized DFS block cache, and the local inline handoff is deliberately narrow and bounded.
- Do not increase the read-ahead maximum merely because larger windows are possible. First measure fetched versus consumed bytes.
- Do not enable vendor-specific batch rewrite, bulk-copy or large-object options as library defaults. Benchmark them in the deployment that owns the JDBC driver, network and rollback policy.
- Do not reintroduce repeated durable flush persistence for normal staged writers. The measured staging model creates at most one payload transaction and one publication transaction per logical pack.

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
