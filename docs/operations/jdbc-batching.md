# JDBC batching and pack-chunk writers

## Default behavior

`HibernateSessionFactoryProvider` applies the following defaults only when the consuming application has not supplied its own values:

```properties
hibernate.jdbc.batch_size=8
hibernate.order_inserts=true
```

The batch size matches the default stateful writer's bounded eight-chunk `flush()`/`clear()` window. With one MiB chunks, the persistence context retains roughly eight MiB of chunk payload before flushing. Applications can override both settings explicitly. The provider deliberately does not change `hibernate.order_updates`, because update ordering would also affect unrelated application entities registered in the same persistence context.

Framework-managed applications that construct their own `SessionFactory` do not pass through `HibernateSessionFactoryProvider` and must configure JDBC batching themselves.

## Experimental writer modes

The established `stateful` writer remains the default. Two internal alternatives are available for controlled comparison:

```properties
jgit.storage.hibernate.pack.chunk_writer=stateless
jgit.storage.hibernate.pack.chunk_writer=jdbc
```

Both alternatives are deliberately limited to `GitPackChunkEntity`, which is raw non-indexed payload data. Pack parents, publication metadata, reflogs, repository locks and all Hibernate Search projections continue to use ordinary stateful sessions.

Neither alternative is selected automatically. A different default requires repeatable allocation, GC and elapsed-time evidence across representative payload sizes and the supported database matrix.

### Stateless ORM writer

The child `StatelessSession` is opened from the active parent session with `connection()`, so it shares the same JDBC connection and resource-local transaction. Parent and chunk inserts therefore roll back together. `CacheMode.IGNORE` prevents one-MiB payload arrays from entering the second-level cache, and explicit `insertMultiple()` batches preserve synchronous failure reporting without relying on stateless write-behind.

The first focused twelve-MiB PostgreSQL result reduced allocation by 23.9% and Hibernate flushes by 40% compared with portable stateful batching. Mean elapsed time was 2.5% lower, but the confidence intervals overlapped. That is a clear memory-management improvement, not yet a statistically secure latency result.

### Direct JDBC writer

The direct JDBC mode clears the parent persistence context after the pack parent has been inserted and flushed. It then prepares one portable `INSERT` statement through the active Hibernate session's JDBC coordinator and executes the same bounded eight-row batches on the Hibernate-managed connection and resource-local transaction.

The SQL does not hard-code a catalog, schema, table or physical column names. It is assembled from Hibernate's resolved `GitPackChunkEntity` mapping, so configured naming and schema qualification remain in force. Every batch result is checked: each command must report one affected row or standard JDBC `SUCCESS_NO_INFO`; failed or incomplete batches abort the surrounding transaction.

This path intentionally bypasses entity creation and ORM mutation coordination only for the immutable chunk rows. It does not bypass repository lifecycle, pack visibility, leases, rollback or final publication. Direct JDBC is justified only if it produces a material improvement beyond the stateless ORM writer, not merely beyond the older stateful baseline.

The focused PostgreSQL benchmark now compares stateful insertion with batching disabled, portable stateful batching, PostgreSQL batch rewriting, stateless ORM and direct JDBC. Its GC profiler records allocation and collection metrics in addition to elapsed time and JDBC counters.

## Chunk key strategy

`GitPackChunkEntity` uses the existing logical key

```text
(pack_id, chunk_index)
```

as its Hibernate identity. This lets Hibernate know every chunk identifier before issuing SQL and therefore collect inserts into JDBC batches.

Flyway-managed databases created by earlier versions may still contain the generated `git_pack_chunks.id` column and its primary key. That column remains mapped read-only for compatibility, while the existing unique constraint on `(pack_id, chunk_index)` continues to protect the storage invariant. Upgrades do not rewrite published pack payloads or chunk rows.

Disposable `create-drop` schemas use the composite ORM key directly. Migration-backed H2, HSQLDB, PostgreSQL and SQL Server schemas retain the legacy surrogate column and are validated by the integration suite.

## Pack key strategy

`GitPackEntity` deliberately keeps its narrow generated `Long` key. Chunk rows reference that compact value, avoiding wide foreign keys containing repository name, pack name and extension.

The pack key still uses `GenerationType.IDENTITY`, so Hibernate must insert and flush the parent pack row before it can persist chunk rows. The expensive repeated generated-key lookup was removed from the chunk rows, where one large pack can create many inserts. A cross-database pooled sequence for the parent key should be considered only if benchmark evidence shows that the remaining single parent-key round trip is material.

## Existing stateful baseline

The measurements below predate the stateless and direct JDBC writers and remain the stateful baseline. The focused JMH test publishes one non-compressible twelve-MiB blob to PostgreSQL. Every invocation creates exactly 13 chunk rows and 2 pack rows, uses 2 connections and performs 5 Hibernate flushes.

| Mode | Time | JDBC batch executions | JDBC statement executions | Prepared statements |
|---|---:|---:|---:|---:|
| Batching disabled | 476.6 ms | 0 | 19 | 19 |
| Portable Hibernate batching | 474.9 ms | 2 | 6 | 8 |
| Batching plus PostgreSQL rewrite | 480.3 ms | 2 | 6 | 8 |

Portable batching therefore reduces JDBC statement executions from 19 to 6 and prepared statements from 19 to 8 while persisting identical data. End-to-end latency is neutral in this local Testcontainers workload because pack creation, compression and transfer of the twelve-MiB payload dominate the remaining time.

The standard protocol suite also remains stable: PostgreSQL incremental push measured 44.10 ms before and 44.07 ms after the batching change. Initial push improved by roughly three percent in that run, clone was unchanged and incremental fetch was slightly faster. These variations are consistent with normal run noise and show no small-workload regression.

## Driver-specific options

Portable Hibernate batching and the direct JDBC writer work without JDBC-URL extensions. Deployments may benchmark additional driver features separately.

### PostgreSQL

```text
reWriteBatchedInserts=true
```

The focused binary-chunk benchmark found no benefit from this option: 480.3 ms with rewrite versus 474.9 ms with portable batching alone, with identical Hibernate/JDBC counters. The library therefore does not enable it. A deployment with different network latency, pack size or database placement may still test it explicitly.

### Microsoft SQL Server

```text
useBulkCopyForBatchInsert=true
```

The Microsoft JDBC driver may use bulk-copy execution for compatible parameterized batch inserts. Test the actual `varbinary(max)` chunk mapping, transaction rollback and error reporting before production use.

The library does not force either vendor option because the application owns the `DataSource`, JDBC URL, driver version and operational rollback policy.

## Verification

The normal Core tests verify that:

- the default batch size is active;
- an explicit consumer batch size is preserved;
- real `jdbcExecuteBatchStart()` events occur for chunk inserts;
- chunks remain addressable by `(pack_id, chunk_index)`;
- create-drop and migration-backed schemas remain valid;
- stateless and direct JDBC chunk inserts share the outer transaction and roll back with their parent pack;
- multi-MiB packs written through either experimental mode remain byte-identical after repository reopen;
- the direct JDBC writer follows Hibernate's resolved schema-qualified table mapping;
- stateless and direct JDBC raw chunk persistence coexist with ordinary Hibernate Search indexing and querying.

The performance workflow runs the established backend/protocol matrix and a separate focused PostgreSQL job for stateful batching disabled, portable stateful batching, batching plus driver rewrite, stateless ORM and direct JDBC. On `main`, both raw JMH artifacts are combined into the published dashboard history without changing the standard performance badge contract.
