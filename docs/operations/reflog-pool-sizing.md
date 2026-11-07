# Size the PostgreSQL pool for durable reflog writes

This guidance applies to the [durable queryable reflog projection](git-aware-reflog-batching.md).
Its worker holds one connection while one repository batch commits. Each
repository always routes to one stripe; each stripe can process only one
transaction at a time. The writer uses the application-managed pool.
Use the following measurements to choose an explicit limit when a deployment
needs one.

For one busy repository, size for one active writer. With traffic spread
across independent repositories, test two stripes if the writer can use two
pool slots, or four stripes if it can use four. Raise the stripe count only
when additional repositories use those stripes and both the pool and
PostgreSQL have capacity. Retain space in the pool for other callers.

At roughly 20 ms measured RTT with 16 independent producers, pool
acquisition dominates the tail when eight stripes compete for two slots.
With a single stripe, most of the tail is spent in the queue instead. The
values below are medians of the individual trial measurements.

| Writer stripes | Hikari slots | Acknowledged records/s | Acknowledgement p95 | Hikari acquisition p95 |
| ---: | ---: | ---: | ---: | ---: |
| 1 | 4 | 9.4 | 1,733 ms | <0.01 ms |
| 4 | 4 | 37.0 | 439 ms | <0.01 ms |
| 8 | 2 | 18.2 | 2,021 ms | 1,264 ms |
| 8 | 8 | 73.8 | 221 ms | <0.01 ms |

With one shared repository and four pool slots at the same RTT, one and
four configured stripes both used one worker, and measured
132.9 and 131.5 acknowledgements/s respectively. Shared bursts can combine records in one
transaction. A single producer, and these independent producers with one
chain per repository, produced one record per transaction. Local shared
throughput varied with the connection hold time; the
[history-depth diagnostic](../evidence/reflog-pool-sizing-2026-11-07/README.md#shared-history-diagnostic)
shows one source of work that extra pool slots cannot remove.

With independent repositories on loopback, eight stripes reached
3,473–3,708 records/s with an eight-slot pool and
3,137–3,395 records/s with 16 slots. The extra
connections did not let eight stripes execute more than eight transactions
at once. At 20 ms, growing the inline message from 13 to 2,000 characters
changed median WAL movement from about 693 to 2,924 bytes per acknowledged
independent record (four stripes, four slots).

## Read the measurements in your deployment

| Observation | First action |
| --- | --- |
| One busy repository and idle stripe workers | Measure this repository's batch sizes, transaction time and history depth. Extra stripes cannot split one repository. |
| Independent repositories, idle database, low pool wait and a growing queue | Try one step more stripes; compare acknowledged throughput and p95/p99 under the same offered workload. Confirm that repository hashes actually use the added stripes. |
| All pool slots active and sustained pool wait | Budget connections for the other users of the pool. Reduce writer concurrency or test added pool capacity only if database CPU, I/O, and WAL latency have headroom. |
| Pool wait is low but connection hold and database CPU or I/O rise | Investigate the transaction, the PostgreSQL plan and storage latency before raising concurrency. An oversized pool can shift the queue to PostgreSQL. |
| Batches are mostly singletons under sparse traffic | This is expected with one in-flight command or one producer per independent repository. Raising the stripe count does not fill a batch. Compare queue delay and batch size before changing the collection window. |
| A large publication or maintenance operation coincides with reflog tail spikes | Check lock wait and other pool users. Pack publication and reflog appends can contend for the same repository lock; they use separate operation paths. |

Size writer concurrency against **available** connections, after allowing for
repository reads, normal pushes, and maintenance. Use the number of available
connections as a starting bound when testing stripe counts. Hikari allocates
the shared pool across all callers, so measure headroom with those callers
active.

## What was measured

The [retained trials](../evidence/reflog-pool-sizing-2026-11-07/README.md) ran the
production `DurableReflogWriter` against a disposable PostgreSQL 17.10 database
and a warmed, fixed-size Hikari pool. Each producer appended to one ref and
waited for committed acknowledgement before issuing the next command. In
one layout the producers shared a repository; in the other layout each had
its own. Each trial created fresh mapped tables and repository state.
Network delay was applied with Toxiproxy 2.12.0 during calibration, warmup and
measurement, leaving provisioning and teardown outside the shaped window.

- [Throughput versus stripes and pool slots](../evidence/reflog-pool-sizing-2026-11-07/throughput.svg)
- [The p95 acknowledgement split into queue, pool acquisition and connection hold](../evidence/reflog-pool-sizing-2026-11-07/latency.svg)
- [Achieved batch-size distribution](../evidence/reflog-pool-sizing-2026-11-07/batch-sizes.svg)
- [Shared versus independent repository scaling](../evidence/reflog-pool-sizing-2026-11-07/repository-scaling.svg)

The latency chart uses the measured phases for one actual p95 command in the
trial with the middle observed p95 (the upper middle with an even number of
trials). The analyzer selects p95 from all acknowledgements and requires a
matching transaction for that command. Connection hold covers the
owning transaction, including client work, database queries, network delay and
commit. A small remainder accounts for work outside the other three phases.

The closed loop caps the number of in-flight commands at the number of
producers. Sustained overload and production SLOs need separate measurements.
Process-level heap samples include the harness and normal garbage collection.
The local and delayed paths share one host and storage stack, with fixed link
delay in the shaped runs. The largest tested message is one reflog record of
2,000 characters, at the field limit.

Separate studies measure chunked publication under different layout, network
and staging conditions. The combined workload of chunked publication and
reflog projection needs its own shared-pool measurement. [Pack layout and
concurrency](pack-storage-layout.md) · [Network latency and chunk
batching](network-latency-and-chunk-batching.md) · [Staging and JDBC
evidence](staging-jdbc-evidence.md)

## Whole pack publication comparison

A [hosted PostgreSQL JMH run](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42384374593/artifacts/13579187635) measured four producers publishing deterministic 64-KiB and 384-KiB payloads, on opposite sides of the 256-KiB inline threshold. These commands include complete object and ref publication. Each producer owned a separate two-slot Hikari pool in this harness. The queue scheduled operations by repository, and each operation retained its own transaction boundaries.

| Repository layout | Payload | Direct p95 | One stripe p95 | Four stripes p95 | Eight stripes p95 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Independent | 64 KiB | 46.2 ms | 68.5 ms | 52.7 ms | 41.5 ms |
| Independent | 384 KiB | 86.5 ms | 146.1 ms | 86.0 ms | 82.2 ms |
| Shared | 64 KiB | 75.1 ms | 84.4 ms | 79.8 ms | 79.6 ms |
| Shared | 384 KiB | 113.9 ms | 170.8 ms | 158.0 ms | 166.7 ms |

Four stripes lowered the independent 384-KiB publication p95 from 146.1 to 86.0 ms in this run. The shared repository stayed on one worker in every queued case and showed a smaller difference across stripe counts. This is scheduling evidence for a separate local workload at revision `f8a4e91a0c043eadf0c0ac46bb42a39b00e2c032`. The [large-pack artifact](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42384374593/artifacts/13579490914) retains additional 16- and 128-MiB throughput and allocation measurements. Sizing a pool shared by those publications and reflog writers requires a combined workload.
