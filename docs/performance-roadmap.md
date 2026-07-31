# Performance optimization roadmap

This document records the performance work that remains after adaptive small-pack persistence, atomic local staging, JDBC chunk batching, bounded multi-chunk read-ahead, committed-pack handoff, persistent logical pack metadata, read-optimized DFS maintenance, concurrent publication measurement and adaptive additive chunked publication.

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
- Compare four independent `SessionFactory` writers on one logical repository with four writers on independent repositories.
- Keep fully inline and replacing logical packs on the single locked transaction path while pre-persisting complete additive chunked groups lock-free and applying one short committed-visibility update under the repository lock.

See [Protocol storage metrics](protocol-storage-metrics.md) for counter semantics and interpretation limits. See [Pack capacity and recovery](operations/capacity-and-recovery.md) for the adaptive publication and crash model. See [Repack, garbage collection and read acceleration](operations/repack-and-gc.md) for the maintenance contract.

## Current performance observations

The current 24-commit/4-commit protocol matrix shows:

- initial PostgreSQL push remains the clearest serial write-path target;
- atomic local staging substantially reduced locked transaction boundaries and improved incremental push in repeated measurements;
- clone-style and incremental fetch remain in the same broad latency range across filesystem, HSQLDB and PostgreSQL;
- HikariCP does not show a repeatable serial latency advantage;
- portable JDBC batching reduces statement execution but is not itself a dominant local end-to-end latency improvement;
- the committed-pack catalog and bounded local payload handoff remove deterministic metadata and local inline fallback reads;
- persisted logical pack metadata keeps JGit lookup ordering, Reftable ordering and maintenance decisions stable after repository reopen;
- read-optimized maintenance can trade background CPU and auxiliary index bytes for fewer active packs and lower future graph-walk work without weakening atomic publication;
- JGit's DFS garbage collector does not currently emit a persisted reverse-index extension, so the library does not expose a misleading option for it;
- protocol testing exposed and fixed DFS block-alignment and persisted-file-size contracts that object-level benchmarks did not exercise.

The four-thread publication benchmark established the next additive write-path boundary:

| Backend | Same logical repository | Four independent repositories | Independent advantage |
|---|---:|---:|---:|
| Filesystem | 290.7 ops/s | 299.0 ops/s | 2.9% |
| HSQLDB | 75.8 ops/s | 90.6 ops/s | 19.5% |
| PostgreSQL | 54.8 ops/s | 85.0 ops/s | 55.0% |
| PostgreSQL + HikariCP | 53.1 ops/s | 82.5 ops/s | 55.4% |

For PostgreSQL, repository-lock acquisition rose from roughly 0.6 ms per recorded lock with independent repositories to roughly 12 ms with shared-repository contention. HikariCP produced essentially the same ratio. The nearly flat filesystem result rules out the four-thread harness itself as the cause.

A first three-transaction prototype reserved parents under a lock, transferred payloads without the lock and then acquired the final publication lock. It reduced per-lock held time, but the extra reservation transaction and lock caused a clear throughput regression:

| PostgreSQL workload | Baseline | Three-transaction prototype | Change |
|---|---:|---:|---:|
| Same logical repository | 54.8 ops/s | 50.7 ops/s | -7.5% |
| Four independent repositories | 85.0 ops/s | 65.4 ops/s | -23.1% |
| PostgreSQL + Hikari, same repository | 53.1 ops/s | 49.6 ops/s | -6.6% |
| PostgreSQL + Hikari, independent | 82.5 ops/s | 61.3 ops/s | -25.7% |

The focused twelve-MiB path remained in the same latency range but expanded from one to three JDBC connections and from five to eight Hibernate flushes. That prototype was rejected rather than merged.

The current implementation therefore uses only two transactions for additive chunked packs: one complete lock-free pre-persistence transaction and one short locked visibility transaction. It removes the reservation lock, one connection boundary and the parent re-read while retaining token/lease cleanup and exact final extension-count validation.

Replacing packs deliberately do not use this optimization. JGit performs ref-race validation before source-pack replacement; keeping construction, replacement deletion and publication in one repository-locked transaction prevents a ref update from crossing that check. Repack and compaction therefore optimize later read cost, not their own lock-held write time.

## Next implementation candidates

1. Re-run serial protocol, focused large-pack and four-thread same/different-repository measurements for the final two-transaction design; compare lock-held time, lock acquisition, throughput and the single added transaction boundary.
2. Record payload bytes written to temporary files, read from temporary files, persisted to the database and fetched by read-ahead windows.
3. Measure repository-open and object-lookup cost after 1, 100 and 1,000 incremental pushes, including close/reopen boundaries and before/after repack comparisons.
4. Evaluate a bounded memory-first staging buffer for extensions that remain below the inline threshold, spilling to a temporary file when the threshold or repository memory budget is exceeded.
5. Evaluate JGit multi-pack-index maintenance before full repack for very large repositories where rewriting payloads is more expensive than retaining multiple packs.
6. Derive automatic maintenance thresholds from aging benchmarks instead of hard-coding a universal pack-count trigger.
7. Compare one-, four- and sixteen-chunk read-ahead policies using fetched versus consumed bytes rather than query count alone.
8. Profile allocation and GC pressure in multi-hundred-MiB publication before replacing Hibernate chunk persistence with a lower-level JDBC writer.
9. Evaluate whether very large payload-transfer transactions need configurable chunk-group commit boundaries and a separate durable manifest; do not weaken atomic visibility or permit partial publication.

## Deprioritized ideas

- Do not add a Hibernate second-level payload cache without evidence. JGit already owns the specialized DFS block cache, and the local inline handoff is deliberately narrow and bounded.
- Do not increase the read-ahead maximum merely because larger windows are possible. First measure fetched versus consumed bytes.
- Do not enable vendor-specific batch rewrite, bulk-copy or large-object options as library defaults. Benchmark them in the deployment that owns the JDBC driver, network and rollback policy.
- Do not reintroduce repeated durable flush persistence while JGit is still constructing an extension. Pre-persistence begins only after every expected local extension is complete.
- Do not add a preliminary repository lock when the unique pack identity, invisible transaction and final visibility lock already preserve correctness.
- Do not split replacement/compaction across an unlocked interval merely to reduce transfer lock time; the ref-race contract takes precedence.

## Acceptance criteria for later optimizations

An optimization should be merged only when it:

- preserves the transactional pack/ref publication contract;
- passes H2, HSQLDB, PostgreSQL and SQL Server integration tests;
- passes the supported JGit compatibility matrix;
- improves at least one production-style workload without materially regressing another;
- records raw benchmark output and a reproducible Maven command;
- reconciles aggregate and per-operation storage metrics;
- states whether a claimed gain is latency, throughput, reduced lock-held time, reduced statements, reduced transferred bytes or reduced allocation;
- documents crash, lease, rollback and repository-deletion behavior when durable unpublished state is introduced;
- avoids turning filesystem comparisons into claims that database storage should match local page-cache latency for tiny synchronous writes.
