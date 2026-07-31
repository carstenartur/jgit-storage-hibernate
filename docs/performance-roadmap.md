# Performance optimization roadmap

This document records the performance work that remains after adaptive inline persistence, atomic staging, JDBC chunk batching, bounded read-ahead, committed-pack handoff, persistent logical metadata, read-optimized DFS maintenance, concurrent publication measurement and lock-shortened additive chunk publication.

## Implemented performance slices

- Store PACK, IDX and Reftable files up to 256 KiB in the inline payload column.
- Retain ordered one-MiB chunks for larger files.
- Start open extensions in random-readable bounded memory and spill once when the inline threshold or process staging budget is exhausted.
- Publish fully inline logical packs in one locked transaction.
- Pre-persist complete additive chunked groups invisibly and apply one short locked visibility update.
- Keep pack replacement and compaction on the direct locked path so no ref update can cross JGit's race check.
- Avoid durable database work for ordinary output-stream flush calls.
- Batch chunk inserts using stable `(pack_id, chunk_index)` identity.
- Read each persisted chunk directly into the final array retained by Hibernate instead of cloning a one-MiB scratch buffer.
- Load up to sixteen consecutive chunks through one ordered query when JGit requests sequential read-ahead.
- Align writable, inline and chunked DFS channels to one-MiB blocks.
- Persist pack source, last-modified time, object/delta counts, index version and Reftable update indexes across reopen.
- Hand locally published inline PACK and Reftable bytes to JGit through a hard-bounded repository-instance cache.
- Delete replaced logical packs with one parent mutation and database cascade.
- Remove redundant pack, chunk and reflog indexes while retaining measured access paths.
- Expose read-optimized DFS garbage collection with single-pack compaction, bitmaps, commit graph, changed-path Bloom filters and Reftable compaction.
- Measure initial/incremental push through `ReceivePack` and clone/fetch through `UploadPack`.
- Measure four independent `SessionFactory` writers on one logical repository and on independent repositories.
- Record transaction counts, connections, statements, repository-lock wait/held time and per-operation storage categories.
- Attribute committed database fallback reads by extension and representation.
- Normalize every public benchmark-history value, including retained historical throughput points, to `ms/op` so one chart never changes scale or direction mid-series.

See [Protocol storage metrics](protocol-storage-metrics.md), [Pack capacity and recovery](operations/capacity-and-recovery.md) and [Repack, garbage collection and read acceleration](operations/repack-and-gc.md).

## Current performance observations

The real JGit protocol matrix shows:

- initial PostgreSQL push remains the clearest serial write-path target;
- clone-style and incremental fetch remain in the same broad latency range across filesystem, HSQLDB and PostgreSQL;
- HikariCP has no repeatable serial latency advantage;
- portable JDBC batching reduces statement execution but is not by itself the dominant local latency improvement;
- persisted logical metadata keeps lookup, Reftable and maintenance ordering stable after reopen;
- read-optimized maintenance trades background CPU and auxiliary index bytes for fewer active packs and lower future graph-walk work;
- object-level microbenchmarks alone do not expose DFS block-alignment, persisted-size or ref-race contracts.

The four-thread publication benchmark established the shared-repository contention boundary:

| Backend | Same logical repository | Four independent repositories | Independent advantage |
|---|---:|---:|---:|
| Filesystem | 290.7 ops/s | 299.0 ops/s | 2.9% |
| HSQLDB | 75.8 ops/s | 90.6 ops/s | 19.5% |
| PostgreSQL | 54.8 ops/s | 85.0 ops/s | 55.0% |
| PostgreSQL + HikariCP | 53.1 ops/s | 82.5 ops/s | 55.4% |

A rejected three-transaction prototype added a locked reservation before payload transfer. It was correct but reduced PostgreSQL throughput by 7–26% because it added an extra lock, connection boundary and flush window. The accepted design uses one lock-free complete pre-persistence transaction and one short locked visibility transaction.

The chunk-array optimization then removed one full payload-sized heap copy without changing SQL or transaction structure. A controlled contemporaneous 12-MiB PostgreSQL rerun improved all three JDBC modes by **7.7–8.3%** while preserving exactly 13 chunk inserts, two parent inserts, two connections and five Hibernate flushes per invocation.

Memory-first staging targets the opposite end of the size range: inline extensions should no longer create, write, reread and delete a temporary file. Large extensions retain the established spill/chunk path, so this optimization is expected to affect tiny object writes, small commits, IDX and Reftable publication rather than large-payload throughput.

## Next implementation candidates

1. Record payload bytes written to memory and temporary files, read from staging, persisted to the database and fetched/consumed by read-ahead windows.
2. Select direct versus pre-persisted publication adaptively using payload size and measured contention, retaining the direct path where an extra transaction costs more than it saves.
3. Measure repository-open and object-lookup cost after 1, 10, 100 and 1,000 incremental pushes, including close/reopen and before/after maintenance comparisons.
4. Evaluate JGit multi-pack-index maintenance before full repack where retaining payloads is cheaper than rewriting them.
5. Derive automatic maintenance thresholds from aging measurements rather than a universal pack-count constant.
6. Compare one-, four- and sixteen-chunk read-ahead policies using fetched versus consumed bytes.
7. Prototype repository-striped durable write queues and micro-batching with bounded backpressure and acknowledgement only after database commit.
8. Profile allocation and GC pressure for multi-hundred-MiB publication before replacing Hibernate chunk persistence with a lower-level JDBC writer.
9. Evaluate whether very large payload transactions need configurable chunk-group commit boundaries plus a durable manifest; do not weaken atomic visibility.

## Deprioritized ideas

- Do not add a Hibernate second-level payload cache without evidence; JGit already owns the specialized DFS block cache.
- Do not increase read-ahead simply because larger windows are possible; first measure overfetch.
- Do not enable vendor-specific batch rewrite, bulk-copy or large-object options as defaults.
- Do not reintroduce durable work while JGit is still constructing an extension.
- Do not add a preliminary repository lock when unique identity, invisible persistence and final visibility locking already preserve correctness.
- Do not split replacement/compaction across an unlocked interval; the ref-race contract takes precedence.
- Do not acknowledge an in-memory queued Git write before its database commit in the normal durability mode.

## Acceptance criteria for later optimizations

An optimization should be merged only when it:

- preserves transactional pack/ref publication;
- passes H2, HSQLDB, PostgreSQL and SQL Server integration tests;
- passes the supported JGit compatibility matrix;
- improves a production-style workload without materially regressing another;
- retains raw benchmark output and a reproducible Maven command;
- distinguishes latency, throughput, lock-held time, statements, transferred bytes and allocation;
- documents crash, rollback, lease and deletion behavior;
- keeps public benchmark histories in a stable comparable unit;
- avoids claiming that database storage must match a local page-cache hit for every tiny synchronous write.
