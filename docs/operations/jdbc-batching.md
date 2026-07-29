# JDBC batching and pack-chunk keys

## Default behavior

`HibernateSessionFactoryProvider` applies the following defaults only when the consuming application has not supplied its own values:

```properties
hibernate.jdbc.batch_size=8
hibernate.order_inserts=true
hibernate.order_updates=true
```

The batch size matches the storage writer's bounded eight-chunk `flush()`/`clear()` window. With one MiB chunks, the persistence context retains roughly eight MiB of chunk payload before flushing. Applications can override every setting explicitly.

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

## Driver-specific options

Portable Hibernate batching works without JDBC-URL extensions. Deployments may benchmark additional driver features separately:

### PostgreSQL

```text
reWriteBatchedInserts=true
```

The PostgreSQL JDBC driver may rewrite compatible batches into multi-value inserts. Validate this with the real binary chunk workload and the deployed driver version before enabling it globally.

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

The JMH suite also compares a non-compressible twelve-MiB PostgreSQL pack with batching disabled and enabled. It reports elapsed time, real JDBC batch executions, statement executions, prepared statements, flushes, pack-row inserts and chunk-row inserts.

Run the complete comparison through the existing benchmark profile or workflow so PostgreSQL is provided by Testcontainers and the result is published with the normal dashboard data.
