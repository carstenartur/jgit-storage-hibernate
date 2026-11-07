# PostgreSQL pack publication policy comparison

The [contention policy](../../operations/adaptive-pack-publication.md) is opt-in. In this
fixture, it improved shared-repository throughput by **39.9%** (paired 95% interval
**+32.4% to +47.9%**) over the 1 MiB size selector. It made no pre-persistence selections
for independent repositories. The independent throughput interval is wide enough to
include a material regression, so these results do not support changing the default.

## Results

Eight writers used separate Hibernate SessionFactories to insert a unique, incompressible
768 KiB + 257 byte Git blob, flush its pack, and publish a new ref. Shared writers used one
logical repository and row lock. Independent writers used separate repositories on the same
database. The 768 KiB payload isolates the range in which the contention policy can choose a
different path. Both the forced direct control and the 1 MiB rule select direct here.

For PostgreSQL/Hikari, throughput and the two transaction phase columns use means of the
included forks. The p95 columns use the median of each fork's JMH p95. Lock acquisition
includes the database round trip. “Unlocked write” is the complete payload transaction,
including the payload work moved out of the lock; it does not isolate commit overhead.

| Layout | Policy | Ops/s | Pack + ref p95 | Pack lock acquisition p95 | Pack lock held / op | Unlocked write / op | Pre-persisted packs |
|---|---|---:|---:|---:|---:|---:|---:|
| Shared | Forced direct | 12.46 | 717.8 ms | 297.8 ms | 41.87 ms | 0 ms | 0 / 69 |
| Shared | 1 MiB size | 12.76 | 709.9 ms | 302.8 ms | 42.34 ms | 0 ms | 0 / 345 |
| Shared | Contention-aware | 17.86 | 529.6 ms | 225.4 ms | 14.04 ms | 58.20 ms | 400 / 400 |
| Independent | Forced direct | 56.64 | 185.4 ms | 15.7 ms | 50.88 ms | 0 ms | 0 / 173 |
| Independent | 1 MiB size | 55.27 | 194.1 ms | 14.4 ms | 53.13 ms | 0 ms | 0 / 834 |
| Independent | Contention-aware | 53.48 | 227.0 ms | 14.7 ms | 54.37 ms | 0 ms | 0 / 819 |

The shared selector added one unlocked pack transaction per operation and moved about
28 ms per operation out of the final pack lock. The paired 95% interval for the change in
pack lock acquisition p95 was **−31.5% to −18.7%**. The independent throughput difference
was **−3.2%** (paired 95% interval **−21.6% to +19.7%**); the independent pack + ref p95
point estimate also rose. The interval uses a Student t interval on the five paired log
ratios of fresh JVM fork results, transformed back to percent change. Individual
measurement iterations are not treated as independent replicates.

The internal Hibernate pool check used one fork per layout and policy. The 1 MiB size and
contention-aware policies measured 12.03 and 16.78 ops/s on the shared repository and
48.80 and 60.96 ops/s on independent repositories. All 75 shared adaptive packs
pre-persisted; none of the 183 independent adaptive packs did. These are exploratory
point estimates.

## Charts and raw results

| Chart | Raw data |
|---|---|
| [Shared and independent throughput](throughput.svg) | [JMH JSON ZIP](raw-jmh.zip) |
| [Path selections](selected-path.svg) | [JMH JSON ZIP](raw-jmh.zip) |
| [p95 pack lock acquisition](lock-acquisition.svg) | [JMH JSON ZIP](raw-jmh.zip) |
| [Time under the lock and in the unlocked payload transaction](transaction-tradeoff.svg) | [JMH JSON ZIP](raw-jmh.zip) |

The archive contains all 38 original JMH JSON files under `raw/`.

[Per-fork CSV](per-fork.csv) includes pack + ref p50/p95/p99, pack lock p95, transaction
counts, allocation per operation, and observed heap at operation boundaries. A heap sample
can miss shorter peaks. This synchronous fixture has no application queue; the lock
measurement does not separate database wait from round-trip time. All retained forks
completed with JMH failure-on-error enabled. An unsuccessful or raced ref publication
would have failed its fork.

[Trial windows](trials.csv) show the UTC log open and last write times, which bracket each
fork. Another unpinned benchmark ran at 21:08:42–21:09:45 UTC and from about 21:10:00 to
21:11:17.652 UTC, overlapping the first two Hikari repetitions. Both members
of an affected pair and the two original direct controls are retained but excluded from
comparisons and charts. Repetitions 3–7 and the replacement direct controls are included.
The last two repetitions and replacement controls ran after hardening the key for JDBC
pool overrides; neither the thresholds nor the fixture's connection settings changed.

## Fixture and reproduction

PostgreSQL 17.10, PgJDBC 42.7.14, HikariCP 7.0.2, Toxiproxy 2.12.0 and Eclipse Temurin
21.0.13+9 ran on one Linux host (AMD EPYC 9V74, unpinned CPU set 0–8, quota of 8 CPUs).
PostgreSQL used an isolated tmpfs database, `fsync=on`, `synchronous_commit=on`, 128 MiB
shared buffers, and a 256 MiB WAL size target. The sandbox binary only bypassed the
startup root check. The JVM staging files were on tmpfs. No durable device latency was
measured.

Toxiproxy added 2 ms upstream and 3 ms downstream with no jitter. A persistent JDBC
connection ran five discarded and twenty recorded `SELECT 1` round trips at the start of
each sequence. The three medians were 0.063–0.093 ms on direct loopback and 5.709–5.860 ms
on the shaped route ([initial](rtt-retained.csv), [replacement](rtt-replacement.csv),
[final pair](rtt-rep7.csv)). Only the shaped route was timed under the Git workload.

Use a dedicated PostgreSQL database: the benchmark creates and drops its mapped tables.
Configure a private properties file with `jgit.storage.benchmark.postgresql.url`,
`jgit.storage.benchmark.postgresql.user` and `jgit.storage.benchmark.postgresql.password`.
This example reproduces one fork on a configured endpoint:

```sh
mvn -pl jgit-storage-hibernate-benchmarks -am install -DskipTests
mvn -pl jgit-storage-hibernate-benchmarks dependency:build-classpath \
  -DincludeScope=runtime -Dmdep.outputFile="$PWD/target/publication-classpath.txt"

classpath="jgit-storage-hibernate-benchmarks/target/classes:$(cat target/publication-classpath.txt)"
package=io.github.carstenartur.jgit.storage.hibernate.benchmark
java -cp "$classpath" org.openjdk.jmh.Main \
  "$package.ConcurrentPublicationBenchmark.publishToSameRepository" \
  -t 8 -f 1 -wi 1 -w 1500ms -i 3 -r 800ms -bm thrpt -foe true \
  -p backend=postgresql-hikari -p payloadKiB=768 \
  -p publicationPolicy=contention-aware -p deployment=native-routed \
  -prof gc -prof "$package.PackPublicationProfiler" \
  -jvmArgs '-Xms256m -Xmx640m -XX:ActiveProcessorCount=8 -Djgit.storage.benchmark.connection-properties-file=/path/to/private.properties' \
  -rf json -rff contention-aware-shared.json
```

Use `publishToDifferentRepositories` for the independent layout, `postgresql` for the
internal pool, and `direct` or `size` for the comparison policies. Use the default payload
parameters for the approximately 256 KiB chunked fixture, or set `payloadMiB=1,16,128`
without a KiB override. Size parameters include 257 extra bytes to keep the smallest pack
above the inline threshold. Alternate paired policies in separate JVMs, retaining each
JSON result and any overlapping workload interval. Extract the retained results to a
temporary directory and regenerate the CSV and charts with Python 3.10+ and Matplotlib.

```sh
evidence=docs/evidence/pack-publication-2026-11-07
publication_scratch=$(mktemp -d)
unzip -q "$evidence/raw-jmh.zip" -d "$publication_scratch"
cp "$evidence/trials.csv" "$publication_scratch/"
python .github/scripts/summarize-pack-publication.py "$publication_scratch" --charts
```

A rollout decision still needs adequately powered independent-repository comparisons,
the remaining payload/writer/RTT combinations, and results on representative database
storage. The production default remains the 1 MiB size rule.
