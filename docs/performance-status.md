# Performance status and distance to the ceiling

This page records the performance work completed in `jgit-storage-hibernate`, the evidence behind it, and the remaining distance to an ideal implementation. The snapshot describes merged `main` after PR [#174](https://github.com/carstenartur/jgit-storage-hibernate/pull/174) and also records the subsequently rejected direct-JDBC experiment in PR [#176](https://github.com/carstenartur/jgit-storage-hibernate/pull/176), both on 2026-08-01.

The companion [benchmark methodology](benchmarks.md) explains the JMH fixtures, databases and interpretation limits. The [public performance history](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/) contains one chart per operation. Links on this page jump directly to the relevant chart.

## Executive assessment

The database backend is no longer uniformly slower than a filesystem repository. In the first complete protocol comparison, PostgreSQL was competitive with or faster than the filesystem point estimate for incremental push and initial clone-style fetch, while incremental fetch retained a moderate database overhead. Initial durable ingestion remained the largest visible gap.

The focused 12 MiB pack-publication benchmark has also moved from statement-count tuning to memory- and payload-path tuning. Portable batching reduces database executions substantially. Directly filling final chunk arrays removed one payload-sized heap copy and improved the controlled 12 MiB run by roughly 8%. The optional stateless ORM writer then reduced allocation by another 23.9% and Hibernate flushes by 40%, while retaining the same JDBC batch and statement structure. Its mean latency was 2.5% lower in the first run, but the confidence intervals overlap, so the default remains the stateful writer. A subsequent direct-JDBC prototype removed chunk entities entirely, yet two independent runs saved only 0.047–0.048% additional allocation and showed no repeatable latency improvement; PR #176 was therefore closed without merge.

The current position can therefore be summarized as follows:

| Area | Current position | Distance to the useful ceiling |
|---|---|---|
| Warm and generation-local reads | Metadata scans and many immediate payload rereads have been removed; bounded read-ahead serves sequential chunk access. | Close to the application-level ceiling for warm/current-generation reads. Repository aging and genuinely cold storage remain unmeasured. |
| Clone and fetch | PostgreSQL is already close to the filesystem comparison point for the measured in-process protocol workloads. | Limited room for broad latency improvements without changing pack layout, aging behavior or physical deployment. |
| Small and incremental writes | Preflight queries, redundant metadata refreshes and unnecessary temporary-file traffic have been removed or bounded. | Mostly fixed durable-transaction and JGit pack/ref costs remain. |
| Initial and large durable ingestion | Batching, direct chunk fills, memory-first staging, adaptive publication and an opt-in stateless writer are in place. | Still the clearest opportunity: large staging I/O, JDBC transfer, WAL/fsync and remaining ORM work have not yet been separated completely. |
| Same-repository concurrency | Large additive payload transfer can occur outside the short publication lock; independent repositories remain parallel. | The final compare-and-publish section is a semantic lower bound. Policy and queueing can improve utilization, but cannot remove required per-repository ordering. |
| Long-lived repositories | Full pack metadata is persisted and read-optimized DFS repack is available. | Aging breakpoints, MIDX alternatives and automatic maintenance thresholds still require measurements. |

## What “theoretical maximum” means here

There is no single hardware-independent maximum. A database-backed Git repository performs work that a temporary filesystem benchmark does not necessarily perform in the same way: JDBC transfer, database transaction coordination, WAL generation, durable commit, multi-instance locking and relational metadata maintenance.

For the current correctness contract, an ideal operation still has to pay for:

1. JGit object creation, delta selection, compression, pack/index/Reftable construction and protocol negotiation;
2. movement of the resulting payload to durable storage;
3. at least the required durable database transaction and WAL/fsync work;
4. the repository-scoped compare-and-publish section needed for atomic refs and pack visibility;
5. any queryable metadata that is part of the public storage contract.

The optimization target is the avoidable remainder around those costs:

```text
current elapsed time
  = unavoidable JGit and durability work
  + avoidable queries and round trips
  + avoidable payload copies and staging traffic
  + avoidable ORM/persistence-context work
  + avoidable lock-held work
  + measurement noise
```

This page therefore uses three different reference levels:

- **Physical maximum:** the host, network, database and storage-device limit. It is not yet known because WAL bytes, fsync latency and raw payload-transfer bandwidth are not recorded together.
- **Semantic maximum:** the fastest implementation that preserves the existing atomic publication, rollback, lifecycle, lease and multi-instance contracts.
- **Practical comparison ceiling:** JGit `FileRepository` running the same benchmark workload. This is useful, but it is not a proof of the physical or semantic maximum because the backends have different durability paths and caching behavior.

## End-to-end position against the filesystem reference

The table uses the first complete protocol point estimates already documented in [Benchmarks](benchmarks.md). “Efficiency” is `filesystem time / PostgreSQL time`; 100% means the same point estimate. Values above 100% do **not** mean that a physical maximum was exceeded. They mean only that PostgreSQL was faster in that particular controlled run. The single-shot confidence intervals are wide.

| Workload | Filesystem | PostgreSQL | PostgreSQL vs filesystem | Reference efficiency | Chart |
|---|---:|---:|---:|---:|---|
| Initial push, 24 commits | 133.3 ms | 220.6 ms | 1.65× slower | 60.4% | [initialPushViaReceivePack](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-initial-push-via-receive-pack) |
| Incremental push, 4 commits | 53.0 ms | 42.2 ms | 20.4% faster point estimate | 125.6% | [incrementalPushViaReceivePack](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-incremental-push-via-receive-pack) |
| Initial clone-style fetch | 129.6 ms | 117.5 ms | 9.3% faster point estimate | 110.3% | [initialCloneViaUploadPack](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-initial-clone-via-upload-pack) |
| Incremental fetch, 4 commits | 18.8 ms | 23.9 ms | 1.27× slower | 78.7% | [incrementalFetchViaUploadPack](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-incremental-fetch-via-upload-pack) |

The strongest conclusion is not that one backend universally wins. It is that clone/fetch and incremental application workflows are already in the same performance class in this local harness. The main end-to-end gap is initial durable ingestion.

## Focused 12 MiB PostgreSQL writer position

The first four-mode result after PR #174 publishes a deterministic non-compressible payload of approximately 12 MiB. Every mode performs the same logical publication.

| Writer | Mean time | Approx. logical throughput | Allocation | Hibernate flushes | JDBC batches | JDBC statements |
|---|---:|---:|---:|---:|---:|---:|
| Stateful, batching disabled | 480.36 ms | 25.0 MiB/s | 52.95 MB/op | 5 | 0 | 18 |
| Stateful batching | 476.10 ms | 25.2 MiB/s | 52.95 MB/op | 5 | 2 | 5 |
| Stateful batching plus PostgreSQL rewrite | 477.14 ms | 25.1 MiB/s | 52.95 MB/op | 5 | 2 | 5 |
| Optional stateless chunk writer | 463.98 ms | 25.9 MiB/s | 40.31 MB/op | 3 | 2 | 5 |

See [publishTwelveMiBPack](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-publish-twelve-mi-b-pack) and the detailed [JDBC batching and chunk-writer notes](operations/jdbc-batching.md).

### Direct JDBC experiment

PR [#176](https://github.com/carstenartur/jgit-storage-hibernate/pull/176) implemented the strongest reasonable direct-JDBC comparison: no chunk entities, one schema-aware prepared statement reused across all bounded batches, the same Hibernate-managed connection and transaction, explicit row-count validation, shared rollback and ordinary Hibernate Search indexing for the stateful projection entities.

Two independent workflow runs found no repeatable advantage over stateless ORM:

| Run | Stateless mean | JDBC mean | Stateless median | JDBC median | Additional JDBC allocation saving |
|---|---:|---:|---:|---:|---:|
| 1 | 467.12 ms | 464.63 ms | 465.91 ms | 467.23 ms | 18.6 KiB / 0.047% |
| 2 | 511.92 ms | 516.54 ms | 513.59 ms | 511.18 ms | 18.8 KiB / 0.048% |

The sign of the tiny latency difference reversed between runs and the confidence intervals overlapped widely. Direct JDBC therefore duplicates almost all gains already achieved portably by `StatelessSession` while adding Hibernate-internal SPI dependencies and manual SQL, resource and error handling. It was deliberately not merged. The stateful writer remains the default; stateless ORM remains the preferred experimental path until larger payload measurements justify a threshold.

What this establishes:

- batching has already removed most avoidable per-chunk JDBC executions;
- PostgreSQL `reWriteBatchedInserts` did not improve this local binary-payload workload;
- the stateless path removes a material amount of persistence-context allocation without changing the SQL shape;
- the remaining elapsed time is not primarily explained by statement count;
- the completed direct-JDBC prototype did not improve materially beyond the stateless path and was not merged.

What it does **not** establish:

- a physical database bandwidth ceiling;
- a statistically secure latency win for stateless ORM;
- the best result for 128 or 512 MiB packs;
- the best result on H2, HSQLDB or SQL Server;
- production behavior over a network or on storage with different WAL/fsync characteristics.

## Completed performance measures

### Measurement and comparability

| Measure | Avoidable uncertainty or work removed | Relevant evidence |
|---|---|---|
| Public JMH history with canonical `ms/op` units | Prevents timing and throughput units from being mixed in one series; all charts are smaller-is-better. | [All charts](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/), PR [#167](https://github.com/carstenartur/jgit-storage-hibernate/pull/167) |
| Real ReceivePack/UploadPack workloads | Replaces conclusions based only on tiny object probes with protocol-level push, clone and fetch evidence. | [initial push](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-initial-push-via-receive-pack), [initial clone](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-initial-clone-via-upload-pack) |
| Transaction, statement, connection, lock-acquisition and lock-held counters | Separates payload work, lock contention and fixed transaction cost from elapsed-time noise. | [same repository](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-publish-to-same-repository), [different repositories](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-publish-to-different-repositories), PRs [#150](https://github.com/carstenartur/jgit-storage-hibernate/pull/150) and [#156](https://github.com/carstenartur/jgit-storage-hibernate/pull/156) |
| End-to-end storage byte counters | Makes staging writes/rereads, database payload bytes and read-ahead overfetch observable without changing SQL boundaries. | Issue [#163](https://github.com/carstenartur/jgit-storage-hibernate/issues/163), PR [#172](https://github.com/carstenartur/jgit-storage-hibernate/pull/172) |

### Write path

| Measure | Avoidable work removed | Relevant chart or evidence |
|---|---|---|
| Remove pack-extension existence preflight | Saves one query per newly staged extension and lets the existing unique constraint arbitrate races. | [initial push](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-initial-push-via-receive-pack), PR [#139](https://github.com/carstenartur/jgit-storage-hibernate/pull/139) |
| Composite chunk identity plus JDBC batching | Makes chunk IDs known before SQL execution and groups one-MiB chunk inserts into bounded batches. | [12 MiB writer](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-publish-twelve-mi-b-pack) |
| Fill each final chunk array directly | Removes one full payload-sized heap copy from the staged large-pack path; controlled runs improved by roughly 7.7–8.3%. | PR [#169](https://github.com/carstenartur/jgit-storage-hibernate/pull/169) |
| Bounded memory-first staging | Keeps up to 256 KiB per extension and 32 MiB process-wide in memory, eliminating temporary-file traffic for small inline payloads while spilling large payloads safely. | [writeBlob](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-write-blob), [writeCommitAndUpdateRef](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-write-commit-and-update-ref), PR [#173](https://github.com/carstenartur/jgit-storage-hibernate/pull/173) |
| Adaptive direct versus two-phase publication | Small/all-inline publications retain one transaction; large additive chunked payloads are persisted invisibly before the short publication lock. | [same repository](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-publish-to-same-repository), [different repositories](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-publish-to-different-repositories), PRs [#158](https://github.com/carstenartur/jgit-storage-hibernate/pull/158) and [#171](https://github.com/carstenartur/jgit-storage-hibernate/pull/171) |
| Database-cascade replacement deletion | Removes parent-ID selection and explicit child deletion while preserving exact repository-scoped replacement semantics. | PR [#151](https://github.com/carstenartur/jgit-storage-hibernate/pull/151) |
| Optional stateless chunk insertion | Avoids persistence-context/action-queue overhead for immutable non-indexed payload rows and reduced allocation by 23.9% in the first 12 MiB result. | [12 MiB writer](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-publish-twelve-mi-b-pack), PR [#174](https://github.com/carstenartur/jgit-storage-hibernate/pull/174) |
| Direct JDBC prototype rejected | Proved that removing the remaining chunk entities and one prepared statement saves only about 19 KiB/op beyond stateless ORM and provides no repeatable latency gain; avoids adding Hibernate-internal SPI and manual SQL to production. | PR [#176](https://github.com/carstenartur/jgit-storage-hibernate/pull/176) |

### Read path

| Measure | Avoidable work removed | Relevant chart or evidence |
|---|---|---|
| Generation-local committed-extension catalog | Avoids repeated metadata transactions for already discovered chunked extensions without retaining payload arrays or connections. | [initial clone](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-initial-clone-via-upload-pack), [incremental fetch](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-incremental-fetch-via-upload-pack), PR [#141](https://github.com/carstenartur/jgit-storage-hibernate/pull/141) |
| One-shot committed pack-list handoff | Removes post-publication metadata rescans while preserving JGit cache invalidation and event order. | [initial push](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-initial-push-via-receive-pack), [incremental push](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-incremental-push-via-receive-pack), PR [#142](https://github.com/carstenartur/jgit-storage-hibernate/pull/142) |
| Bounded local inline payload handoff | Avoids immediate rereads of newly published small PACK/Reftable payloads under a hard repository-instance budget; historical data remains authoritative in the database. | PR [#149](https://github.com/carstenartur/jgit-storage-hibernate/pull/149) |
| Ordered chunk read-ahead | Converts sequential multi-chunk access into bounded ordered queries without keeping a Hibernate session or connection open. | [large sequential read](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-read-large-blob-sequentially-after-j-git-cache-reset) |
| Persist complete pack metadata and expose read-optimized repack | Preserves JGit pack ordering/maintenance semantics after restart and permits explicit bitmap, commit-graph, Bloom-filter and Reftable compaction. | [reopenAndResolveMain](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-reopen-and-resolve-main), PRs [#153](https://github.com/carstenartur/jgit-storage-hibernate/pull/153) and [#155](https://github.com/carstenartur/jgit-storage-hibernate/pull/155) |

## Position by cost layer

| Cost layer | Current state | Remaining theoretical gap |
|---|---|---|
| JGit pack/protocol CPU | Uses ordinary JGit APIs and pack formats. | Essentially unavoidable unless JGit itself changes or work is amortized through larger batches/repack. |
| Small-payload staging | Memory-first path performs no temporary-file I/O when the complete extension remains inline. | Near the semantic floor; remaining cost is JGit generation, entity/SQL work and durable commit. |
| Large-payload staging | One bounded file supplies random reads and publication streaming; redundant full-chunk copy is gone. | A physical write plus reread still occurs. Avoiding it requires a different random-readable staging design or direct streaming that remains compatible with JGit retries and rollback. |
| ORM chunk handling | Stateful batching is bounded; stateless ORM removes the persistence context from chunk rows. A complete JDBC prototype confirmed that the remaining entity allocation is negligible. | Direct JDBC is no longer a promising gap. The remaining question is the payload-size threshold at which stateless ORM is repeatably worthwhile. |
| JDBC round trips | Thirteen chunk rows fit into two batches in the focused workload; all batched/stateless modes use five JDBC executions. | Larger batches, SQL Server bulk copy or PostgreSQL-specific transfer can be tested, but portability and rollback/error semantics must remain intact. |
| Database durability | Atomic transactions, WAL and committed visibility are preserved. | This is largely unavoidable. The current gap cannot be quantified until WAL bytes, fsync time and raw JDBC transfer capacity are measured. |
| Repository lock | Large additive payload transfer occurs outside the lock; final publication remains repository-scoped. | The compare-and-publish section is required. Contention-aware selection or durable queues can improve scheduling, not eliminate ordering. |
| Warm/current-generation reads | Metadata and immediate local payload handoffs remove many redundant database reads; JGit and channel caches remain bounded. | Close to the practical ceiling in current fixtures. Cold cache, aged repositories and cross-instance refresh are separate workloads. |

## What is needed for a defensible percentage of the absolute maximum

A claim such as “the implementation reaches 85% of the theoretical maximum” would currently be false precision. A defensible number needs additional lower-bound measurements in the same environment:

1. JGit pack generation to a null or memory sink, isolating CPU/compression cost;
2. raw JDBC batch and, separately, vendor bulk/COPY transfer of the same byte arrays inside one rollback-capable transaction;
3. database WAL bytes, fsync/commit latency and effective payload bandwidth;
4. staging bytes, database bytes and logical Git bytes for each invocation;
5. 16, 128 and 512 MiB payloads to show fixed cost versus asymptotic throughput;
6. a networked PostgreSQL run so local Testcontainers results are not mistaken for deployment limits.

Until then, the most honest ranking is:

- **warm/current-generation reads:** close to the practical application ceiling;
- **clone/fetch and incremental push:** already competitive with the filesystem reference in the measured harness;
- **small synchronous writes:** dominated increasingly by unavoidable transaction and JGit fixed cost;
- **large/initial durable ingestion:** improved substantially, but still the main distance from the semantic and physical ceiling;
- **same-repository concurrent writes:** bounded by intentional serialization, with remaining opportunity in scheduling and lock avoidance rather than removal of atomic ordering.

## Remaining high-value work

The open performance issues are ordered by the evidence they can add:

1. [#166 — determine the large-pack threshold for stateless ORM](https://github.com/carstenartur/jgit-storage-hibernate/issues/166): direct JDBC has been rejected; extend stateful-versus-stateless measurements to 16/128/512 MiB, supported databases and concurrent publications before considering automatic selection.
2. [#163 — publish storage byte metrics in JMH](https://github.com/carstenartur/jgit-storage-hibernate/issues/163): quantify staging amplification and read-ahead overfetch rather than optimizing only statement counts.
3. [#165 — repository aging, MIDX and repack thresholds](https://github.com/carstenartur/jgit-storage-hibernate/issues/165): determine when fresh-repository results stop representing real lookup and clone/fetch behavior.
4. [#161 — adaptive publication selection](https://github.com/carstenartur/jgit-storage-hibernate/issues/161): evaluate diagnostics and contention-aware policy against the deterministic one-MiB selector.
5. [#162 — repository-striped durable queues and micro-batching](https://github.com/carstenartur/jgit-storage-hibernate/issues/162): improve concurrent utilization without acknowledging work before commit or globally serializing unrelated repositories.

## Direct chart index

- Fixed write cost: [writeBlob](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-write-blob), [writeCommitAndUpdateRef](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-write-commit-and-update-ref)
- Amortized writes: [writeBatchOf100Blobs](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-write-batch-of-100-blobs), [writeCommitSeries10AndUpdateMain](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-write-commit-series-10-and-update-main)
- Read paths: [warm blob](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-read-blob-from-warm-cache), [JGit-cache-reset blob](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-read-blob-after-j-git-cache-reset), [large sequential blob](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-read-large-blob-sequentially-after-j-git-cache-reset)
- Protocol writes: [initial push](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-initial-push-via-receive-pack), [incremental push](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-incremental-push-via-receive-pack)
- Protocol reads: [initial clone](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-initial-clone-via-upload-pack), [incremental fetch](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-incremental-fetch-via-upload-pack)
- Concurrent publication: [same repository](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-publish-to-same-repository), [different repositories](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-publish-to-different-repositories)
- Focused large-pack writer: [publishTwelveMiBPack](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-publish-twelve-mi-b-pack)

Every chart title exposes its own permalink. A direct URL remains stable as new benchmark commits are appended to the history.
