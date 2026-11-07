# Durable reflog appends and chunked pack publication in one Hikari pool

These measurements use the production storage code at
`3bfa6aff12014b88424c48cf349e33c794e80765`. The accompanying JUnit fixture
uses one application-owned Hikari data source and one Hibernate
`SessionFactory` for both `DurableReflogWriter` and JGit's pack publisher.

Each trial starts from fresh mapped tables and warms all pool slots. Four
producers each submit the next reflog append after receiving the previous
committed acknowledgement. Two writer stripes share a pool of either two
or four connections. In the independent layout each reflog producer has a
separate repository and the pack goes to a fifth repository. In the shared
layout all four producers and the pack publisher use one repository. Each
layout, pool size and network path has three shuffled repeats of a reflog-only
control and a trial with one chunked publication.

The publisher stages one deterministic incompressible blob of 8 MiB plus
257 bytes before the measurement and confirms there are no PACK chunks in
the database. It starts the normal JGit inserter flush on the same barrier
as the reflog producers. After the timed phase the fixture verifies the
committed PACK chunk count, clears the DFS cache, reopens the blob through
a fresh repository handle, confirms that the bytes came from the database,
and compares the complete SHA-256 digest. It also checks every persisted
reflog delivery ID and old/new object ID in per-ref database order,
including the warmup records.

Every pack trial requires at least one reflog request interval to intersect
an actual pack JDBC hold. The summary compares the p95 of the exposed
requests with the p95 of requests from the same producer and ordinal in the
same-repeat control. Checkout p95 uses the requests with paired checkout
records. The summary reports that sample count and leaves checkout p95
blank if the count is zero. It also reports full-phase reflog throughput and
p50/p95/p99, measured checkout and queue times, the pack flush interval,
WAL and sampled lock, pool and heap counters. The exposed requests can
include commands that started before a pack checkout or completed after it.

The host, driver, proxy and durability settings follow the [sizing
study](../reflog-pool-sizing-2026-11-07/README.md). This run uses PostgreSQL
17.10, HikariCP 7.0.2, PgJDBC 42.7.14, Toxiproxy 2.12.0, Temurin 25.0.5+8,
and a 64 MiB WAL size target. The pack and reflog share the same shaped
data source at the requested 20 ms RTT. Control and publication phases
each retain 20 measured `SELECT 1` round trips.

| Evidence | Contents |
| --- | --- |
| [plan.csv](plan.csv) | Trial order and the final `control` or `pack` workload field |
| [per-trial.csv](per-trial.csv) | Verified throughput, latency, overlap and native-counter summary |
| [acknowledgements.csv.gz](acknowledgements.csv.gz), [pool-borrows.csv.gz](pool-borrows.csv.gz) | Reflog commands and owning Hikari transactions |
| [mixed-ack-timeline.csv.gz](mixed-ack-timeline.csv.gz) | Per-command submission and completion offsets from the start barrier |
| [pack-publications.csv.gz](pack-publications.csv.gz), [pack-borrows.csv.gz](pack-borrows.csv.gz) | Pack flush interval, verification sizes, and each actual pack Hikari checkout |
| [repository-lock-waits.csv](repository-lock-waits.csv) | Server-reported waits over 50 ms on the repository lock, joined to trial windows by UTC timestamp |
| [occupancy.csv.gz](occupancy.csv.gz), [rtt-calibration.csv.gz](rtt-calibration.csv.gz), [native.ndjson.gz](native.ndjson.gz) | Sampled pool, heap, queue and database lock occupancy, measured RTT, and PostgreSQL counters |

Use the [same Maven and Toxiproxy commands](../reflog-pool-sizing-2026-11-07/README.md#reproduce)
with `evidence="$PWD/docs/evidence/reflog-pack-pool-2026-11-07"`. Copy the
plan beside the generated raw files and run the analyzer without
`--charts`. The trial uses one pre-staged pack flush, a fixed payload
and a closed-loop reflog stream. Throughput and tail latencies will depend
on staging, arrival rates, the number of concurrent publishers, history
depth and the host. The lock sampler runs about every 20 ms, so it can
miss shorter waits. The WAL statistics can lag across snapshot boundaries. The small WAL size
target also triggered checkpoints during the series.
