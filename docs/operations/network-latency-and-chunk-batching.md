# Network latency and pack-chunk batching

Database round-trip latency changes the optimal way to publish a large pack. This page records the calibrated Toxiproxy evidence used by the production defaults and explains the supported overrides.

## Production configuration

The bundled `HibernateSessionFactoryProvider` now uses the following non-overriding defaults:

```properties
hibernate.jdbc.batch_size=16
hibernate.order_inserts=true
```

The pack writer derives its bounded one-MiB chunk window from the Hibernate batch size. It can also be configured explicitly:

```properties
jgit.storage.hibernate.pack.chunk_batch_size=32
```

When the bundled provider receives an explicit pack-chunk size but no explicit Hibernate JDBC batch size, it applies the same value to `hibernate.jdbc.batch_size`. When an application constructs its own framework-managed `SessionFactory`, configure both values consistently.

Accepted pack-chunk values are 1 through 64. The ceiling prevents one active writer from retaining an unbounded number of one-MiB arrays. A generic Hibernate batch size above 64 remains valid, but the payload-retaining pack window is capped at 64 unless the explicit pack property requests a valid smaller value.

## Why the default is 16

The calibrated selection matrix publishes a deterministic, non-compressible 48-MiB object. That creates 49 one-MiB chunk rows. PostgreSQL 17.10 and Toxiproxy run on one Testcontainers network; HikariCP connects through the proxy. Each requested RTT is split between upstream and downstream delay and verified with repeated `SELECT 1` calls before JMH begins.

| Requested RTT | Median calibrated `SELECT 1` |
|---:|---:|
| 10 ms | 10.790 ms |
| 20 ms | 20.888 ms |
| 50 ms | 51.053 ms |

Each point contains one warmup and three measured single-shot publications.

| RTT | Batch 8 | Batch 16 | Batch 32 | Batch 50 |
|---:|---:|---:|---:|---:|
| 10 ms | 2,192.9 ms | 2,165.1 ms | 2,140.7 ms | 2,131.2 ms |
| 20 ms | 2,335.5 ms | 2,266.6 ms | 2,234.0 ms | 2,210.1 ms |
| 50 ms | 2,783.4 ms | 2,636.2 ms | 2,535.4 ms | 2,465.3 ms |

The three raw values at every matrix point preserve the same monotone ordering. The JDBC counters show why:

| Configured chunk batch | Chunk JDBC batches per publication | Other JDBC statements | Prepared statements | Hibernate flushes | Maximum retained chunk payload |
|---:|---:|---:|---:|---:|---:|
| 8 | 7 | 5 | 12 | 10 | about 8 MiB |
| 16 | 4 | 5 | 9 | 7 | about 16 MiB |
| 32 | 2 | 5 | 7 | 5 | about 32 MiB |
| 50 | 1 | 5 | 6 | 4 | about 50 MiB |

Total normalized allocation remains essentially unchanged at about 379.6 MB/op. The relevant trade-off is peak retained payload per active writer, not total allocation.

Moving from 8 to 16 removes three sequential database batch exchanges for only eight additional MiB of bounded retained payload. Moving from 16 to 32 removes two more exchanges for another 16 MiB, and moving from 32 to 50 removes only one final exchange for another 18 MiB. Sixteen therefore has the best saved-round-trips-per-additional-MiB ratio and is the portable default.

## Deployment guidance

| Deployment characteristic | Recommended value | Reason |
|---|---:|---|
| Local or sub-millisecond database; many concurrent large writers | 8 or 16 | Limit simultaneous retained payload; RTT savings are small. |
| Ordinary networked database around 5–15 ms RTT | 16 | Removes most of the high-value sequential exchanges with a 16-MiB bound. |
| Regional database around 15–35 ms RTT and sufficient heap | 32 | Two chunk batches for a 48-MiB payload; meaningful additional RTT savings. |
| High-latency link around 35–60 ms RTT, low writer concurrency | 50 | One batch for the measured 49 chunks; maximum measured latency reduction. |

These are deployment recommendations, not an automatic RTT detector. Explicit configuration is preferable because the application knows its maximum concurrent writers, heap budget and database placement.

## Effect size

Compared with batch 8, batch 16 saved:

- 27.8 ms at 10 ms RTT;
- 68.9 ms at 20 ms RTT;
- 147.2 ms at 50 ms RTT.

Batch 32 saved 52.2, 101.5 and 248.0 ms respectively. Batch 50 saved 61.7, 125.4 and 318.1 ms respectively.

The preceding 16-MiB matrix also compared batching disabled, batch 8, PgJDBC rewrite and stateless ORM at 0, 1, 5, 10, 20 and 50 ms RTT. It established that ordinary batching removes roughly 57% of the latency-sensitive slope, while `reWriteBatchedInserts=true` does not improve this binary-payload path. Stateless ORM has nearly the same RTT slope as stateful batching and remains primarily a heap/ORM-overhead option.

## Reproduction

Run the calibrated batch-size matrix with Docker available:

```bash
mvn -pl jgit-storage-hibernate-benchmarks verify \
  -Pnetwork-batch-size-selection
```

The `Network Latency Benchmarks` workflow retains combined JMH JSON, per-RTT JSON and text output, a calibration CSV, Maven logs and Failsafe reports. The evidence above comes from workflow run `31212270865`, artifact `network-chunk-batch-size-results`.

The timed path does not use P6Spy. Hibernate `SessionEventListener` counters report real JDBC statement and batch executions without proxy or SQL-logging distortion.
