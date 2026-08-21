# Performance status and distance to the ceiling

This page is the current performance decision record for `jgit-storage-hibernate`. It separates implemented defaults, measured evidence, experimental components and remaining work. The companion [benchmark methodology](benchmarks.md) explains the fixtures and interpretation limits. Existing end-to-end operation charts remain available in the [public performance history](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/).

## Executive assessment

The database backend is no longer uniformly slower than a filesystem repository. In the existing protocol comparison, PostgreSQL is in the same performance class as `FileRepository` for incremental push, clone-style fetch and incremental fetch, while initial durable ingestion remains the largest visible gap.

The large-pack write path is now substantially better understood:

- portable JDBC batching is essential once PostgreSQL has measurable network RTT;
- the production chunk/JDBC batch default is 16 rather than the previous 8;
- 32 and 50 remain bounded deployment overrides for higher RTT and controlled concurrency;
- PgJDBC `reWriteBatchedInserts=true` does not help the measured binary chunk path;
- automatic writer selection uses ordinary stateful Hibernate below 16 MiB and a shared-transaction `StatelessSession` at or above 16 MiB;
- a production receiver queue can commit up to 50 compatible records in one transaction and one observed JDBC batch, or flush a smaller batch after a configurable collection window;
- read-ahead remains access-pattern-aware instead of forcing one global window;
- the complete 512-MiB PostgreSQL/SQL Server matrix retains one-MiB chunks and a 256-KiB inline threshold: larger chunks improve sequential work but regress sparse reads by 37–171%, while 256-KiB chunks regress writes and sequential reads;
- repository maintenance is not enabled automatically: the smoke fixture shows no value at one pack and a strong crossover by ten small packs, but the 100/1,000-push production matrix is still required.

## Current production decisions

| Area | Production behavior | Evidence-based reason |
|---|---|---|
| Hibernate JDBC batch size | Default 16 when the bundled provider owns the setting | Best saved network exchanges per additional retained MiB in the 8/16/32/50 Toxiproxy matrix. |
| Pack chunk window | Default 16, configurable 1–64 | One-MiB chunks make the peak retained payload per active writer explicit and bounded. |
| Persisted pack layout | One-MiB chunks; 256-KiB inline threshold | Complete 512-MiB PostgreSQL and SQL Server evidence rejects 256-KiB, two-MiB and four-MiB alternatives: each regresses either write/sequential or sparse access beyond the promotion budget. |
| Pack chunk writer | `auto`; stateful below 16 MiB, stateless at or above 16 MiB | Stateless saves about 16–18% allocation at 16/128/512 MiB, reduces flush/GC work and preserves identical JDBC shape and reopen integrity. |
| PgJDBC rewrite | Disabled by the library | Slightly slower in the local and calibrated network matrices; no RTT-slope reduction. |
| Receiver batching | 50 records or 64 MiB, two-millisecond default wait, four stripes | Allows one transaction/JDBC batch for a burst while bounding sparse-stream delay and heap. |
| Read-ahead | Honor JGit hint, one-MiB chunks, cap at 16 | Sequential access benefits from 16; sparse random and short reads strongly prefer 1. |
| Automatic repack | Disabled | Useful crossover is visible by ten small packs, but full aging/database evidence is not complete. |
| P6Spy in timed benchmarks | Not used | Hibernate session events expose statement and batch executions without JDBC-proxy/logging distortion. |

## What “theoretical maximum” means

There is no hardware-independent maximum. A database-backed Git repository performs work that a temporary filesystem fixture may not perform in the same way: network transfer, transaction coordination, WAL generation, durable commit, multi-instance locking and relational metadata maintenance.

For the existing correctness contract, an ideal operation still has to pay for:

1. JGit object creation, compression, pack/index/Reftable construction and protocol negotiation;
2. movement of the resulting payload to durable storage;
3. the required database transaction, WAL and fsync work;
4. repository-scoped compare-and-publish ordering for atomic refs and pack visibility;
5. any queryable metadata that is part of the public contract.

The optimization target is the avoidable remainder:

```text
current elapsed time
  = unavoidable JGit and durability work
  + avoidable database exchanges
  + avoidable payload copies and staging traffic
  + avoidable ORM/persistence-context work
  + avoidable lock-held work
  + measurement noise
```

This page therefore distinguishes:

- **Physical ceiling:** host, network, database and storage-device throughput. WAL/fsync and physical database bytes are not yet recorded together, so this ceiling is not known.
- **Semantic ceiling:** the fastest implementation preserving atomic publication, rollback, lifecycle, lease and multi-instance contracts.
- **Practical comparison ceiling:** JGit `FileRepository` on the same benchmark workload. This is useful but not proof of a physical maximum because the durability and caching paths differ.

## End-to-end position against `FileRepository`

The table uses the existing complete protocol point estimates. “Reference efficiency” is `filesystem time / PostgreSQL time`; values above 100% mean only that PostgreSQL had the lower point estimate in that controlled run. Single-shot confidence intervals are wide.

| Workload | Filesystem | PostgreSQL | PostgreSQL vs filesystem | Reference efficiency | Chart |
|---|---:|---:|---:|---:|---|
| Initial push, 24 commits | 133.3 ms | 220.6 ms | 1.65× slower | 60.4% | [initial push](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-initial-push-via-receive-pack) |
| Incremental push, 4 commits | 53.0 ms | 42.2 ms | 20.4% faster point estimate | 125.6% | [incremental push](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-incremental-push-via-receive-pack) |
| Initial clone-style fetch | 129.6 ms | 117.5 ms | 9.3% faster point estimate | 110.3% | [initial clone](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-initial-clone-via-upload-pack) |
| Incremental fetch, 4 commits | 18.8 ms | 23.9 ms | 1.27× slower | 78.7% | [incremental fetch](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-incremental-fetch-via-upload-pack) |

The strongest conclusion is not that one backend universally wins. It is that normal clone/fetch and incremental application workflows are already in the same performance class locally. Initial durable ingestion remains the clearest end-to-end gap.

## Network RTT and JDBC batching

A calibrated Toxiproxy experiment published one deterministic, non-compressible 16-MiB object through PostgreSQL and HikariCP. Requested RTT was split between upstream and downstream traffic and verified with repeated `SELECT 1` calls before JMH began.

### Calibrated RTT

| Requested RTT | Median measured `SELECT 1` |
|---:|---:|
| 0 ms | 0.484 ms |
| 1 ms | 1.479 ms |
| 5 ms | 5.761 ms |
| 10 ms | 10.733 ms |
| 20 ms | 20.899 ms |
| 50 ms | 51.319 ms |

### Elapsed publication time

| RTT | Batching disabled | Hibernate batch 8 | Batch 8 + PgJDBC rewrite | Stateless batch 8 |
|---:|---:|---:|---:|---:|
| 0 ms | 731.1 ms | 712.2 ms | 721.2 ms | 701.8 ms |
| 1 ms | 746.6 ms | 722.3 ms | 732.3 ms | 711.1 ms |
| 5 ms | 861.5 ms | 765.2 ms | 773.2 ms | 754.8 ms |
| 10 ms | 990.8 ms | 815.2 ms | 828.2 ms | 810.7 ms |
| 20 ms | 1,240.7 ms | 922.9 ms | 931.9 ms | 916.4 ms |
| 50 ms | 1,991.6 ms | 1,255.5 ms | 1,269.7 ms | 1,249.2 ms |

The response is almost perfectly linear (`R² > 0.9997`):

```text
batching disabled  ≈ 731.7 ms + 25.26 × RTT
stateful batch 8   ≈ 709.8 ms + 10.87 × RTT
stateless batch 8  ≈ 700.3 ms + 10.96 × RTT
```

Batching removes about 14.4 sequential RTT-equivalents, or roughly 57% of the latency-sensitive slope. At 50 ms RTT, ordinary batching saves about 736 ms, or 37%.

JDBC counters explain the slope change:

- batching disabled: 22 individual statement executions and no batch execution;
- batch 8: five individual statements plus three chunk batches for 17 chunks;
- prepared statements fall from 22 to 8;
- PgJDBC rewrite does not reduce the network slope;
- stateful and stateless have the same network shape, so StatelessSession is an ORM/heap optimization rather than another round-trip optimization.

See [Network latency and pack-chunk batching](operations/network-latency-and-chunk-batching.md).

## Selecting the production chunk batch size

The follow-up matrix publishes 48 MiB, creating 49 chunk rows, and compares 8, 16, 32 and 50 at calibrated 10, 20 and 50 ms RTT.

| RTT | Batch 8 | Batch 16 | Batch 32 | Batch 50 |
|---:|---:|---:|---:|---:|
| 10 ms | 2,192.9 ms | 2,165.1 ms | 2,140.7 ms | 2,131.2 ms |
| 20 ms | 2,335.5 ms | 2,266.6 ms | 2,234.0 ms | 2,210.1 ms |
| 50 ms | 2,783.4 ms | 2,636.2 ms | 2,535.4 ms | 2,465.3 ms |

| Window | Chunk JDBC batches | Other statements | Prepared statements | Flushes | Approx. peak retained chunk payload |
|---:|---:|---:|---:|---:|---:|
| 8 | 7 | 5 | 12 | 10 | 8 MiB |
| 16 | 4 | 5 | 9 | 7 | 16 MiB |
| 32 | 2 | 5 | 7 | 5 | 32 MiB |
| 50 | 1 | 5 | 6 | 4 | 50 MiB |

Total normalized allocation is essentially unchanged. The trade-off is peak concurrently retained payload. Moving from 8 to 16 removes three exchanges for eight additional MiB; 16 to 32 removes two for another 16 MiB; 32 to 50 removes only one for another 18 MiB. Sixteen therefore has the strongest marginal return and is the portable default.

Deployment overrides remain useful:

- local/sub-millisecond and high writer concurrency: 8 or 16;
- ordinary 5–15 ms network database: 16;
- regional 15–35 ms and sufficient heap: 32;
- 35–60 ms, low writer concurrency: 50.

## Automatic stateful/stateless threshold

The focused PostgreSQL + HikariCP matrix uses the production batch window of 16 and verifies close/reopen SHA-256 integrity.

| Payload | Stateful | Stateless | Stateless point estimate | Allocation saving | Flushes stateful/stateless |
|---:|---:|---:|---:|---:|---:|
| 16 MiB | 657.2 ms | 640.1 ms | 2.6% lower | 18.0% | 5 / 3 |
| 128 MiB | 4,893.4 ms | 4,806.9 ms | 1.8% lower | 15.9% | 12 / 3 |
| 512 MiB | 19,548.3 ms | 19,280.8 ms | 1.4% lower | 16.2% | 36 / 3 |

Latency confidence intervals overlap, so the project does not claim a statistically secure latency win. The decision is instead based on the repeatable allocation reduction, materially lower GC/flush work, unchanged JDBC execution shape, lower point estimate in every raw sample and identical payload integrity.

The lowest measured material crossover is 16 MiB. Production therefore defaults to:

```properties
jgit.storage.hibernate.pack.chunk_writer=auto
jgit.storage.hibernate.pack.stateless_min_payload_bytes=16777216
```

Explicit `stateful` and `stateless` modes remain available for troubleshooting or deployment-specific policy. See [JDBC batching and pack-chunk writers](operations/jdbc-batching.md).

## Durable receiver batching

The original benchmark queue only grouped scheduling; every command still executed its own transaction. That was insufficient for the receiver use case.

Core now contains a separate production `DurableStripedWriteQueue<C,R>` and `HibernateDurableBatchProcessor`:

- stable repository-to-stripe routing;
- no batch ever mixes repositories;
- bounded queue count and bytes;
- default maximum 50 records or 64 MiB;
- immediate release at a bound;
- otherwise release all available records after the oldest record's two-millisecond deadline;
- exactly one Hibernate transaction per collected batch;
- session JDBC batch size set to the actual collected record count;
- result-count validation before commit;
- futures completed only after commit;
- graceful drain and non-interrupting immediate shutdown.

An H2 integration test observes exactly one JDBC batch for 50 compatible entity inserts, one transaction, session batch size 50 and 50 post-commit completions. A three-record sparse burst is emitted as a three-record transaction/JDBC batch after the configured wait.

This solves ordinary receiver-record batching. It does **not** silently merge arbitrary complete JGit push/ref operations; those need a storage-specific processor that validates compare-and-set, visibility, replacement and rollback semantics for the combined operation. See [Durable striped receiver batching](operations/durable-striped-write-queue.md).

## Read-ahead policy

The focused HSQLDB smoke matrix shows opposite optima for different access patterns.

### Sequential 20-MiB stream

| Window | Elapsed | Queries | Overfetch |
|---:|---:|---:|---:|
| 1 | 26.57 ms | 21 | 0 |
| 4 | 25.43 ms | 6 | 0 |
| 16 | 14.40 ms | 2 | 0 |

### Random 32 × 4-KiB reads

| Window | Elapsed | Queries | Fetched/requested |
|---:|---:|---:|---:|
| 1 | 30.87 ms | 30 | approximately 240× |
| 4 | 53.46 ms | 26 | approximately 768× |
| 16 | 86.63 ms | 18 | approximately 1,464× |

### Short 64-KiB read

| Window | Elapsed | Fetched/requested |
|---:|---:|---:|
| 1 | 6.50 ms | 16× |
| 4 | 7.42 ms | 64× |
| 16 | 10.65 ms | 256× |

A fixed sixteen-chunk default would improve sequential streaming and severely regress sparse access. Core therefore continues to honor JGit's read-ahead hint and caps the resulting window at sixteen. See [Storage byte metrics](operations/storage-byte-metrics.md).

## Repository aging and maintenance

The deterministic smoke fixture compares 1 and 10 incremental pushes, verifies close/reopen ordering and evaluates no maintenance, compact-only and read-optimized repack.

| History | Maintenance | Active PACKs | Stored extension bytes | Maintenance time |
|---:|---|---:|---:|---:|
| 1 push | none | 1 | 18,491 | – |
| 1 push | compact-only | 2 | 19,567 | about 41 ms |
| 1 push | read-optimized | 2 | 20,955 | about 65 ms |
| 10 pushes | none | 10 | 107,269 | – |
| 10 pushes | compact-only | 2 | 98,409 | about 71 ms |
| 10 pushes | read-optimized | 2 | 100,661 | about 103 ms |

At one push, maintenance creates more packs and bytes. At ten pushes, both modes reduce ten packs to two. Reopen plus oldest-object lookup falls from 15.64 ms to 4.47 ms with compact-only and 4.60 ms with read-optimized maintenance. Clone-style traversal also improves modestly.

The smoke evidence replaces the previous arbitrary “32 packs” suggestion but does not justify automatic maintenance. The useful crossover is above one and at or below roughly ten small packs for this fixture. The 100/1,000-push and PostgreSQL/HikariCP matrix remains required before enabling a default automatic policy. See [Repack, garbage collection and read acceleration](operations/repack-and-gc.md).

## Byte amplification

For the measured 16-MiB publication:

- logical Git payload: approximately 16.78 MB;
- temporary staging write: approximately 16.78 MB;
- temporary staging reread: approximately 16.78 MB;
- database payload: approximately 16.78 MB, only about 0.04% above logical payload.

Database row payload inflation is therefore negligible. The application-level movement is roughly three payload passes because large extensions are staged, reread and then transferred to the database. The next write-amplification opportunity is staging/copy reduction, not manual SQL or smaller database rows.

These counters do not include SQL framing, WAL, page writes, replication or physical storage amplification. Database-native telemetry is still needed for the physical ceiling.

## What is complete and what remains

| Issue area | Completed | Remaining |
|---|---|---|
| Adaptive direct/pre-persisted publication | Size-based one-MiB selector, path/payload diagnostics, deterministic fallback and regression coverage | A contention-aware selector has not shown enough evidence to replace the deterministic policy. |
| Durable striped queue | Production generic 50-record atomic batch queue, Hibernate adapter, property limits, post-commit acknowledgement and JDBC-batch proof | A storage-specific processor for combining complete Git push/ref commands, plus production throughput measurements of that processor. |
| End-to-end bytes/read-ahead | Staging/database/read-ahead counters, spill metrics, sequential/random/short profiles and write-amplification report | Physical PostgreSQL WAL/network/page bytes and a production telemetry exporter. |
| Repository aging/repack | Deterministic 1/10 smoke fixture, reopen ordering, compact/read-optimized comparison and raw artifacts | Full 100/1,000-push PostgreSQL matrix and evidence for any automatic condition-based maintenance policy. |
| Stateful/stateless writer | 16/128/512 matrix, automatic 16-MiB threshold, explicit overrides and integrity tests | Networked concurrent 128/512-MiB validation may refine but is not required for the current safe fallback policy. |

## Current distance to the useful ceiling

- **Warm metadata and generation-local reads:** close to the application-level ceiling; many repeated scans/rereads are removed.
- **Sequential large reads:** bounded query fan-out is effective; the main remaining cost is database payload transfer.
- **Random/short reads:** one-chunk minimum limits damage, but the one-MiB storage chunk itself is the overfetch floor.
- **Small writes:** dominated increasingly by required transaction/JGit costs rather than ORM setup.
- **Large writes over a network:** most avoidable per-chunk round trips are removed; the remaining gap is payload transfer, staging, WAL/fsync and fixed publication exchanges.
- **Large-write heap use:** automatic StatelessSession removes a repeatable 16–18% of allocation but cannot remove the payload arrays themselves.
- **Receiver bursts:** one transaction/JDBC batch for up to 50 compatible records reaches the intended batching structure; real application entity shapes determine the final gain.
- **Long-lived repositories:** measurable repack benefit exists, but the production trigger is not yet known.

The project is therefore no longer missing basic batching or observability. The largest unresolved questions are physical database durability costs, staging copies, workload-specific Git command batching and long-horizon repository maintenance.
