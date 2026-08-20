# Pack chunk size, inline threshold and versioned layout compatibility

The current production format stores payloads up to 256 KiB in `git_packs.data`. Larger payloads use one-MiB rows in `git_pack_chunks`. These defaults are stable and remain unchanged while alternative layouts are measured.

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
- `capacity`: explicit 512-MiB write and sequential-read evidence.

PostgreSQL and SQL Server full/capacity runs use Testcontainers and are manual or scheduled. When both production-database jobs succeed, a separate aggregate job downloads the two raw JMH artifacts, verifies that both are non-empty, merges them and invokes the decision converter once. A single-database result cannot promote a candidate.

The `Pack Storage Layout Network RTT` workflow adds a calibrated PostgreSQL/Toxiproxy slice:

- pull-request smoke at 5 ms requested round-trip time;
- full manual/scheduled measurements at 5, 20 and 50 ms;
- a retained `SELECT 1` calibration CSV proving that the requested latency was applied;
- the same current-layout baseline and byte-bounded candidate rules as the local-database matrix.

The network profile uses a representative 16-MiB payload for the full run and compares write, sequential, short and random-read behavior. It supplements rather than replaces the local payload-size and capacity matrices.

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

A write-only improvement is insufficient. A candidate is eligible for later format design only when both PostgreSQL and SQL Server show write and sequential-read gains and sparse reads regress by no more than five percent. Until that cross-database condition is met, the generated decision remains:

```text
retain-current-layout-pending-postgresql-and-sqlserver-evidence
```

The converter never edits production settings.

## Current preliminary observation

The bounded SQL Server smoke run confirms that candidate layouts can be measured through the same real Hibernate entities and evidence converter as HSQLDB and PostgreSQL. In that small fixture, 256-KiB chunks improved some sequential reads but materially increased write cost and sparse-read overfetch/latency. This is evidence against changing the default from a smoke result, not a final production conclusion.

The retained full, capacity, network and concurrency matrices remain authoritative for the final decision.

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

SQL Server execution is now part of the benchmark workflow, and a fail-closed cross-database aggregate exists. The remaining evidence work is the retained full/capacity execution, calibrated network matrix and 1/4/16-reader/writer concurrency slice before issue #188 can close.

## Production decision

No production default changes in this benchmark slice. The authoritative values remain one-MiB chunks and a 256-KiB inline threshold. A later implementation PR is justified only by retained net-benefit evidence and must introduce the additive versioned layout contract above together with old/new mixed-row tests.
