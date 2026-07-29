# JDBC batching and pack-chunk keys

## Default behavior

`HibernateSessionFactoryProvider` applies the following defaults only when the consuming application has not supplied its own values:

```properties
hibernate.jdbc.batch_size=8
hibernate.order_inserts=true
```

The batch size matches the storage writer's bounded eight-chunk `flush()`/`clear()` window. With one MiB chunks, the persistence context retains roughly eight MiB of chunk payload before flushing. Applications can override both settings explicitly. The provider deliberately does not change `hibernate.order_updates`, because update ordering would also affect unrelated application entities registered in the same persistence context.

Framework-managed applications that construct their own `SessionFactory` do not pass through `HibernateSessionFactoryProvider` and must configure JDBC batching themselves.

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

## Measured behavior

The focused JMH test publishes one non-compressible twelve-MiB blob to PostgreSQL. Every invocation creates exactly 13 chunk rows and 2 pack rows, uses 2 connections and performs 5 Hibernate flushes.

| Mode | Time | JDBC batch executions | JDBC statement executions | Prepared statements |
|---|---:|---:|---:|---:|
| Batching disabled | 476.6 ms | 0 | 19 | 19 |
| Portable Hibernate batching | 474.9 ms | 2 | 6 | 8 |
| Batching plus PostgreSQL rewrite | 480.3 ms | 2 | 6 | 8 |

Portable batching therefore reduces JDBC statement executions from 19 to 6 and prepared statements from 19 to 8 while persisting identical data. End-to-end latency is neutral in this local Testcontainers workload because pack creation, compression and transfer of the twelve-MiB payload dominate the remaining time.

The standard protocol suite also remains stable: PostgreSQL incremental push measured 44.10 ms before and 44.07 ms after the batching change. Initial push improved by roughly three percent in that run, clone was unchanged and incremental fetch was slightly faster. These variations are consistent with normal run noise and show no small-workload regression.

## Driver-specific options

Portable Hibernate batching works without JDBC-URL extensions. Deployments may benchmark additional driver features separately.

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
- create-drop and migration-backed schemas remain valid.

The performance workflow runs the established backend/protocol matrix and a separate focused PostgreSQL job for batching disabled, portable batching and batching plus driver rewrite. On `main`, both raw JMH artifacts are combined into the published dashboard history without changing the standard performance badge contract.
