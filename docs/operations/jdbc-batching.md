# JDBC batching and pack-chunk writers

## Production defaults

`HibernateSessionFactoryProvider` applies the following values only when the consuming application has not supplied its own settings:

```properties
hibernate.jdbc.batch_size=16
hibernate.order_inserts=true
```

The pack writer uses the same bounded window by default. It can be configured explicitly:

```properties
jgit.storage.hibernate.pack.chunk_batch_size=32
```

When the bundled provider receives an explicit pack-chunk window but no explicit Hibernate JDBC batch size, it applies the same value to `hibernate.jdbc.batch_size`. Framework-managed applications that construct their own `SessionFactory` must configure both values consistently.

Accepted pack windows are 1 through 64. Each chunk is at most one MiB, so the value is also the approximate maximum MiB of chunk arrays retained by one active writer before `flush()`/`clear()` or `insertMultiple()`. A generic Hibernate batch size above 64 remains valid, but the pack writer caps its payload-retaining window at 64.

The default changed from 8 to 16 after a calibrated Toxiproxy matrix showed that 16 has the best saved-network-exchanges-per-additional-MiB ratio. See [Network latency and pack-chunk batching](network-latency-and-chunk-batching.md) for the complete measurements and deployment guidance for 8, 16, 32 and 50.

The provider deliberately does not change `hibernate.order_updates`, because update ordering would also affect unrelated application entities registered in the same persistence context.

## What batching changes

`GitPackChunkEntity` uses the logical key

```text
(pack_id, chunk_index)
```

as its Hibernate identity. Every chunk identifier is therefore known before SQL execution and Hibernate can collect the inserts into real JDBC batches.

The pack parent keeps its narrow generated `Long` key. Hibernate must insert and flush that one parent row before chunk insertion begins. The large multiplicative cost was removed from the chunks, where one pack can create many rows.

The writer retains chunks across the staging reader's smaller delivery groups until the configured window is full. Thus a configured value of 32 produces a real 32-row ORM/JDBC batch rather than four unrelated eight-row scheduling groups. A final partial group is flushed when the writer closes.

## Calibrated network evidence

The first Toxiproxy experiment published a non-compressible 16-MiB object through PostgreSQL and HikariCP at calibrated RTTs from local to 50 ms. Batching disabled executed 22 individual JDBC statements. Batch size 8 reduced that shape to five individual statements plus three chunk batches.

| RTT | Batching disabled | Batch 8 | Saving |
|---:|---:|---:|---:|
| 5 ms | 861.5 ms | 765.2 ms | 96.3 ms / 11.2% |
| 10 ms | 990.8 ms | 815.2 ms | 175.6 ms / 17.7% |
| 20 ms | 1,240.7 ms | 922.9 ms | 317.9 ms / 25.6% |
| 50 ms | 1,991.6 ms | 1,255.5 ms | 736.1 ms / 37.0% |

Linear fits showed that batching removed roughly 57% of the latency-sensitive network-exchange slope.

The follow-up selection matrix used 49 chunks and compared 8, 16, 32 and 50 at calibrated 10, 20 and 50 ms RTT:

| RTT | Batch 8 | Batch 16 | Batch 32 | Batch 50 |
|---:|---:|---:|---:|---:|
| 10 ms | 2,192.9 ms | 2,165.1 ms | 2,140.7 ms | 2,131.2 ms |
| 20 ms | 2,335.5 ms | 2,266.6 ms | 2,234.0 ms | 2,210.1 ms |
| 50 ms | 2,783.4 ms | 2,636.2 ms | 2,535.4 ms | 2,465.3 ms |

Chunk batch executions fell from 7 to 4 to 2 to 1. Other JDBC statements stayed at five. Total normalized allocation remained essentially unchanged; the production trade-off is peak retained payload and concurrent writers. Sixteen removes three exchanges for only eight additional MiB compared with the old default. Values 32 and 50 remain useful explicit choices for higher RTT and controlled concurrency.

## Stateful and stateless writers

The default stateful path persists chunk entities in the normal Hibernate session, then flushes and clears at the configured bounded window.

Large immutable pack payloads may use the opt-in stateless ORM path:

```properties
jgit.storage.hibernate.pack.chunk_writer=stateless
```

The child `StatelessSession` shares the active parent's JDBC connection and resource-local transaction. Parent and chunk inserts therefore commit or roll back together. `CacheMode.IGNORE` prevents raw one-MiB payload arrays from entering the second-level cache, and `insertMultiple()` submits the same bounded groups.

Only non-indexed `GitPackChunkEntity` rows use the stateless session. Pack parents, publication metadata, refs, reflogs and Hibernate Search projections remain stateful.

At 16 MiB, stateless ORM reduced allocation by about 18% and Hibernate flushes from six to three, while elapsed time and the network-latency slope remained nearly identical to stateful batching. It therefore remains an explicit heap/ORM optimization rather than a separate network batching mechanism. `stateful` remains the safe default until the 128/512-MiB and concurrent-publication matrices establish a robust automatic threshold.

## Driver-specific options

Portable Hibernate batching requires no JDBC URL extension.

### PostgreSQL

```text
reWriteBatchedInserts=true
```

Both the local and calibrated network matrices found no advantage for the binary chunk path. Rewrite did not reduce the RTT slope and was slightly slower at each measured point. The library does not enable it.

### Microsoft SQL Server

```text
useBulkCopyForBatchInsert=true
```

The Microsoft driver may use bulk-copy execution for compatible parameterized batches. Deployments must test the actual `varbinary(max)` mapping, rollback behavior and error reporting before enabling it. The library does not force the option because the application owns the `DataSource`, driver version and operational policy.

## P6Spy and diagnostics

The timed benchmark path does not use P6Spy. Proxying and SQL logging would distort the result and still would not prove the exact PostgreSQL protocol round-trip count.

A lightweight Hibernate `SessionEventListener` records `jdbcExecuteBatchStart()` and `jdbcExecuteStatementStart()`. Hibernate statistics add prepared statements, entity inserts, flushes and connections; Core metrics add transactions, repository locks, payload bytes and staging traffic. Together these counters explain the measured RTT response without inserting a JDBC logging proxy.

## Schema compatibility

Flyway-managed databases created by earlier versions may retain the generated `git_pack_chunks.id` column and its primary key. That column remains mapped read-only for compatibility, while `(pack_id, chunk_index)` protects the logical storage invariant. Upgrades do not rewrite published payloads.

Disposable `create-drop` schemas use the composite ORM key directly. Migration-backed H2, HSQLDB, PostgreSQL and SQL Server schemas are covered by the integration suite.

## Verification

The normal Core tests verify that:

- the evidence-based default JDBC batch size is active;
- explicit consumer JDBC settings remain authoritative;
- an explicit pack-chunk window configures JDBC batching when the bundled provider owns the default;
- malformed or unbounded pack windows are rejected;
- real JDBC batch events occur for chunk inserts;
- stateful and stateless paths flush the final partial batch;
- chunks remain addressable by `(pack_id, chunk_index)`;
- parent and chunks share rollback and remain byte-identical after reopen;
- Hibernate Search continues to use ordinary stateful projection persistence.

The workflows retain the local writer matrix, the calibrated RTT matrix and the 8/16/32/50 selection matrix as raw JMH artifacts.
