# Pack chunk size, inline threshold and versioned layout compatibility

The current production format stores payloads up to 256 KiB in `git_packs.data`. Larger payloads use one-MiB rows in `git_pack_chunks`. The completed PostgreSQL and SQL Server evidence retains these values as the production decision: no alternative chunk size provides a net benefit across sequential and sparse access.

Issue [#188](https://github.com/carstenartur/jgit-storage-hibernate/issues/188) evaluates whether fewer, larger chunk rows can reduce JDBC, index and protocol overhead without making short or random reads materially worse. The benchmark also measures whether a different inline threshold reduces small-payload round trips without retaining too much binary data in pack metadata rows.

## Current production contract

```text
inline threshold: 256 KiB
chunk size:       1 MiB
writer budget:    16 chunks / approximately 16 MiB by default
read-ahead:       requested by JGit, capped at 16 one-MiB chunks
```

Existing rows carry no explicit per-pack chunk-size field. A reader locates a byte position by dividing it by one MiB. Consequently, changing the production chunk size without persisted layout metadata would make old and new rows ambiguous. The benchmark never does that.

## Benchmark-only candidate matrix

`PackStorageLayoutBenchmark` uses the real Core Hibernate entities in a disposable schema and varies:

| Parameter | Values |
|---|---|
| chunk size | 256 KiB, 1 MiB, 2 MiB, 4 MiB |
| inline threshold | 64 KiB, 256 KiB, 1 MiB |
| retained writer budget | approximately 8, 16, 32 MiB |
| byte-based read-ahead | 256 KiB, 1 MiB, 4 MiB, 16 MiB |
| payload | 64 KiB, 256 KiB, 1 MiB, 16 MiB, 128 MiB, 512 MiB |
| access | write, sequential read, 64-KiB short read, 32 deterministic random 4-KiB reads |

The number of chunks retained per writer batch is derived from bytes:

```text
chunks_per_batch = floor(retained_payload_budget_bytes / chunk_bytes)
```

This keeps comparisons fair. A four-MiB candidate with a sixteen-MiB budget retains four chunks; a 256-KiB candidate retains 64. The benchmark rejects a result if the actual retained payload exceeds its configured budget or differs by one complete chunk or more.

Read-ahead is also represented in bytes. The evidence records both the byte request and the resulting number of chunk rows. This prevents a nominal “16 chunks” window from silently changing from four MiB to 64 MiB when chunk size changes.

## Profiles and retained evidence

The `Pack Storage Layout` workflow provides three scopes:

- `smoke`: bounded HSQLDB and SQL Server matrices used by pull requests;
- `full`: inline-boundary, write, sequential, short and random-read evidence through 128 MiB;
- `capacity`: explicit 512-MiB write, sequential, short and deterministic random-read evidence; sparse reads use one representative one-MiB read-ahead window to keep the profile bounded.

PostgreSQL and SQL Server full/capacity runs use Testcontainers and are manual or scheduled. When both production-database jobs succeed, a separate aggregate job downloads the two raw JMH artifacts, verifies that both are non-empty, merges them and invokes the decision converter once. A single-database result cannot promote a candidate. The decision also requires sparse-read evidence for every candidate on both production databases; missing sparse evidence fails closed rather than being treated as zero regression.

The `Pack Storage Layout Network RTT` workflow adds a calibrated PostgreSQL/Toxiproxy slice:

- pull-request smoke at 5 ms requested round-trip time;
- full manual/scheduled measurements at 5, 20 and 50 ms;
- a retained `SELECT 1` calibration CSV proving that the requested latency was applied;
- the same current-layout baseline and byte-bounded candidate rules as the local-database matrix.

The network profile uses a representative 16-MiB payload for the full run and compares write, sequential, short and random-read behavior. It supplements rather than replaces the local payload-size and capacity matrices.

The `Pack Storage Layout Concurrency` workflow measures 1, 4 and 16 active workers against one shared PostgreSQL schema and one shared `SessionFactory`:

- every worker owns an independent repository and pack identity;
- readers use immutable per-worker fixtures;
- writers create and remove one unique pack per invocation;
- the connection pool is bounded to the measured worker count plus a small control margin;
- no worker creates or drops schemas, so the measurement cannot be distorted by concurrent Hibernate bootstrap races;
- the pull-request smoke profile compares one-MiB and four-MiB chunks with one-MiB payloads;
- the full profile compares all four chunk sizes with 16-MiB payloads and write, sequential, short and random-read operations.

The concurrency converter requires the current layout at every worker level and a one-worker scaling baseline for every candidate. JMH `EVENTS` auxiliary counters are summed across active worker threads, so the converter first verifies the top-level JMH thread count and normalizes structural, JDBC and byte counters per worker. It then reports p50/p95/p99, a latency-derived concurrent capacity estimate, scaling efficiency, per-writer retained bytes, JDBC activity and overfetch. A result is only an observational candidate when it improves writes and sequential reads at all 1/4/16-worker levels without a sparse-read regression beyond five percent. It cannot change the production decision by itself.

Raw JMH JSON, console output, Surefire reports, converted comparison JSON and machine-readable decision evidence are retained together.

The converter records:

- p50/p95/p99 and score uncertainty;
- pack and chunk row counts;
- JDBC batches/statements, prepared statements, Hibernate queries and flushes;
- configured versus actual retained bytes;
- read-ahead chunks;
- fetched, consumed and overfetched bytes;
- allocation and GC evidence when JMH exposes it;
- comparison with the current one-MiB/256-KiB layout under the same backend, deployment, payload, retained budget and read-ahead condition.

A write-only improvement is insufficient. A candidate is eligible for later format design only when both PostgreSQL and SQL Server contain write, sequential-read, short-read and random-read comparisons, show write and sequential-read gains, and both sparse access patterns regress by no more than five percent. Machine-readable evidence distinguishes three states:

```text
retain-current-layout-pending-postgresql-and-sqlserver-evidence
retain-current-layout-no-cross-database-net-benefit
candidate-layout-ready-for-versioned-format-design
```

The pending state is reserved for missing backends or required operations. A complete matrix with no eligible candidate records the no-net-benefit decision explicitly.

The converter never edits production settings.

## Retained observations and final capacity decision

The bounded [SQL Server smoke run](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/32389361783) confirms that candidate layouts can be measured through the same real Hibernate entities and evidence converter as HSQLDB and PostgreSQL. In that small fixture, 256-KiB chunks improved some sequential reads but materially increased write cost and sparse-read overfetch/latency. This is evidence against changing the default from a smoke result, not a final production conclusion.

The calibrated [PostgreSQL RTT smoke run](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/32391072877) requested 5 ms and measured a median `SELECT 1` latency of 6.062 ms. The four-MiB candidate improved the measured sequential-read point estimate by about 16.9% and the random-read point estimate by about 0.5%, but made the write point estimate about 16.1% slower. It therefore also fails the net-benefit rule.

The bounded [PostgreSQL concurrency smoke run](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/32394385287) completed the 1/4/16-worker matrix against a shared schema and pool. The four-MiB candidate did not provide a uniform gain: its worst point-estimate deltas across the measured worker levels were about -4.9% for writes, -24.3% for sequential reads and -14.4% for random reads. At some individual higher-concurrency points it improved latency, but the fail-closed rule correctly rejects a layout that regresses another worker level or access pattern. The current one-MiB layout remains the reference.

The retained full matrix and the final [complete 512-MiB capacity run](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/32505334226) are now complete. The capacity aggregate contains 64 validated PostgreSQL and SQL Server rows: write, sequential read, 64-KiB short read and deterministic random 4-KiB reads for every candidate chunk size. Both sparse operations are present on both production databases, and the converted decision artifacts are strict JSON.

### Final 512-MiB cross-database comparison

Positive values are improvements relative to the current one-MiB layout. “Worst sparse” is the worse result across short and random reads; the promotion budget permits no regression below -5%.

| Chunk size | PostgreSQL write | PostgreSQL sequential | PostgreSQL worst sparse | SQL Server write | SQL Server sequential | SQL Server worst sparse | Decision |
|---:|---:|---:|---:|---:|---:|---:|---|
| 256 KiB | -8.25% | -14.18% | +21.31% | -7.67% | -5.68% | +16.53% | reject: write and sequential regress |
| 1 MiB | baseline | baseline | baseline | baseline | baseline | baseline | retain current layout |
| 2 MiB | +5.60% | +0.62% | -36.99% | +32.32% | +1.95% | -170.91% | reject: sparse regression |
| 4 MiB | +16.63% | +12.28% | -95.05% | +22.43% | +5.43% | -105.31% | reject: sparse regression |

The result explains the trade-off rather than merely selecting a winner. Smaller chunks reduce sparse overfetch but increase row, statement and sequential-transfer cost. Larger chunks reduce row/JDBC overhead and improve large writes and sequential reads, but sparse reads fetch far more payload than requested. The regression is large and consistent enough that neither a global larger default nor a special large-PACK layout is justified by this evidence.

The final machine decision is `retain-current-layout-no-cross-database-net-benefit`. The pending state is no longer overloaded for a completed rejection: it is emitted only when a production backend or required access pattern is missing.

## Required compatibility design before any production change

A future variable-size format needs explicit durable metadata. The recommended additive shape is:

```text
git_packs.layout_version

git_packs.chunk_size_bytes
```

Compatibility semantics must be:

1. `layout_version IS NULL` and `chunk_size_bytes IS NULL` mean the legacy one-MiB layout.
2. New variable-size chunked rows store both fields before becoming visible.
3. Inline rows remain identifiable through non-null `data`; their threshold does not need to be reconstructed during reads.
4. Repositories may contain old and new logical packs simultaneously.
5. Pack-list catalog entries carry the effective chunk size into every readable channel.
6. Position mapping, block-size reporting, corruption checks and read-ahead window calculation use that effective size.
7. Existing rows are not rewritten merely to adopt the new software version.
8. Rollback, abandoned-write cleanup, repack replacement and repository deletion remain layout-independent.

The existing per-chunk `chunk_size` column is not sufficient by itself. A reader must know the nominal chunk size before it can map a byte offset to a `chunk_index`; querying preceding rows to rediscover the layout would add avoidable round trips and complicate corruption detection.

## PostgreSQL and SQL Server implications

The proposed metadata columns are small scalar values and can be added without rewriting binary payloads. Both database migrations must:

- leave legacy values null;
- validate positive bounded chunk sizes for new rows at the application boundary;
- preserve the existing unique key on `(pack_id, chunk_index)`;
- preserve cascading deletion from pack rows to chunk rows;
- keep inline and chunked payloads mutually exclusive;
- pass `hbm2ddl.auto=validate`, upgrade, restart and mixed-layout tests.

SQL Server execution, calibrated PostgreSQL RTT evidence and the bounded 1/4/16-worker concurrency contract are part of the benchmark workflow, and the retained full and complete sparse-aware capacity executions have succeeded. The cross-database aggregate rejects every alternative layout, so no schema migration or mixed-layout implementation is required for the current production decision.

## Production decision

The authoritative production values remain **one-MiB chunks** and a **256-KiB inline threshold**. The completed local, RTT, concurrency, full and 512-MiB capacity evidence does not justify a global, extension-specific or payload-class adaptive persisted layout. No migration is introduced, and legacy repositories remain readable without rewrite.

A future format change requires new evidence that passes the same complete PostgreSQL and SQL Server write, sequential, short-read and random-read contract. Such a change must then introduce the additive versioned layout metadata above together with legacy/new mixed-row, restart, rollback and corruption tests.
