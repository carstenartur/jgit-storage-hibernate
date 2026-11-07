# Repository routing and Search topology

Keep one local Lucene index on one active Search node by default. Repository routing is opt-in and
can be used with one physical index; adding shards is a separate choice. Sharding divides the
index on that node. It does not distribute writes across application instances.

## Enable repository routing

For an evaluation, add this Hibernate property to the application-owned `SessionFactory`:

```properties
hibernate.search.mapping.configurer=class:io.github.carstenartur.jgit.storage.hibernate.search.RepositoryRoutingMappingConfigurer
```

With the Spring Boot starter, put the same key in `jgit.storage.hibernate.hibernate-properties`.

The configurer binds `GitCommitIndex.repositoryName` as its exact, immutable routing key and uses
an independent index named `GitCommitIndexByRepository`. The ordinary `GitCommitIndex` index and
its on-disk documents remain compatible when the configurer is absent. Repository name comparisons
still use the explicit repository filter: different repositories can hash to the same shard. Text,
path, compact projection and scrolling searches all route when enabled. Structured-only HQL search
is unaffected. Routing can change Lucene's corpus statistics, so relevance order can differ from a
fan-out query; use a stable field sort when comparing hit sets across layouts.

The configurer uses one physical index unless hash sharding is configured for this index. For
example, add the following to test four physical shards:

```properties
hibernate.search.backend.indexes.GitCommitIndexByRepository.sharding.strategy=hash
hibernate.search.backend.indexes.GitCommitIndexByRepository.sharding.number_of_shards=4
```

Repository-scoped ORM deletion continues to remove documents after the relational transaction
commits. The routing bridge targets the repository shard for those removals. A Git history rebuild
also sweeps documents left behind by an earlier failed index operation. It purges the selected
routing key after the bounded relational deletions and refreshes before replaying reachable commits.
The purge cannot remove another repository's documents, including on a shared shard. Stop writers
for that repository until the rebuild completes. A failure can leave a partial derived projection;
rerun the rebuild to clear and replay it. Do not use the Search workspace's index-only purge to
delete authoritative Git history or relational projection rows.

## Switch an existing index or change the shard layout

Do this offline, including a switch from an unrouted index to a one-shard routed index. Hibernate
Search records the routing key inside each document; the old documents do not have it. A layout
change can leave the total hit count unchanged while making documents unreachable via their keys.

1. Pause Search reads and projection writes. Keep the database and its `git_commit_index` rows.
2. Configure the new mapping and shard count with a fresh index root. Keep the previous derived
   directory separately if you need it for diagnosis. Do not start a second writer on either root.
3. Open a maintenance `SessionFactory` with Search enabled, then reindex the relational projection:

   ```java
   Search.mapping(sessionFactory)
       .scope(GitCommitIndex.class)
       .massIndexer()
       .failFast(true)
       .startAndWait();
   ```

4. Construct `GitHistorySearchService` and verify known repository hits before resuming traffic.
   With routing enabled, the service checks the total and each repository's routed document count
   against the database once per factory. A mismatch stops service construction. The check is a
   topology/readiness check and assumes writes are paused during startup; it does not compare
   document IDs or detect every kind of drift after startup.

If reindexing is interrupted, keep traffic paused and run the full MassIndexer again. If Git history
itself has not been projected correctly, rebuild that repository from Git instead of mass indexing
the incorrect rows. To switch back after a routed index received writes, rebuild the unrouted index
from current relational rows before using it; the old index directory may be stale. See the separate
[document identifier upgrade](search-index-upgrades.md) for the older numeric-ID migration.

## Local shard measurements

The [dispatchable workflow](../../.github/workflows/search-routing-performance.yml) runs with one local
Search JVM with a file-backed H2 database and a fresh file-backed Lucene index. It compares
the default unrouted baseline with 1, 2, 4 and 8 routed shards for one repository, uniform repository
sizes and an 80% hot repository. The hosted inputs support 10,000, 100,000 and 1,000,000 documents and
10, 100 or 1,000 repositories for the two multi-repository layouts. A push to a deliberate
`benchmark/search-routing-full-198-*` branch runs both Lucene versions at 100,000 and 1,000,000
projections, with one, 100 uniform or 1,000 skewed repositories. At 100,000 the workflow compares
the baseline plus 1/2/4/8 routed shards. The million-document branch run compares the baseline plus
1/8 routed shards to bound runtime and disk use. The intermediate million-document layouts and
other repository counts are available through manual dispatch. The
[retained large matrix](../evidence/search-routing-large-2026-11-07/README.md) now contains all
48 observations from the six successful 100,000/1,000,000-document jobs, for stable Lucene 9.12.3
and `lucene-next` 10.4.0. The earlier bounded 10,000-document observations remain below.

The fixture stores the actual `GitCommitIndex` mapping with deterministic synthetic text and path
fields. It starts with the configured projection count and adds 50 and 500 projection bursts, using synchronous
index commit/refresh and at most 250 rows per transaction. Each query is warmed, selects the first
20 IDs with a stable sort, and checks that routing does not change the returned IDs. A routed search
addresses one hash shard; the same repository filter without routing addresses all configured
shards. The harness checks actual index directories and that all documents of each repository are
in one shard. The cross-repository administrative query has no repository restriction; its latency
is recorded separately in the CSV. Purge timing includes a refresh of the index after the targeted
deletion and excludes SQL deletion. The CSV's restart timing includes reopening the factory/H2
database in the same JVM, the readiness check and the first hit; it is not a process or cold-cache
restart. The full SQL-to-Lucene MassIndexer recovery is timed separately.

### Hosted 100,000 and 1,000,000 projection observations

The six hosted jobs used Temurin 21.0.13, four JVM processors, a 1 GiB heap, and 80 warmup plus
200 timed samples per query mode. Stable and `lucene-next` ran sequentially within each job;
the six jobs used different Intel/AMD hosts. Compare routed and same-filter fan-out within a
configuration. Cross-size and cross-layout differences also include hardware and run variation.

The stable backend's text p95 values were:

| Initial projections | Repository layout | Unrouted baseline | Routed 1 shard | Routed 8 shards | Same-filter fan-out at 8 |
| ---: | --- | ---: | ---: | ---: | ---: |
| 100,000 | One repository | 9.194 ms | 9.720 ms | 9.411 ms | 8.900 ms |
| 100,000 | 100 uniform | 1.444 ms | 1.009 ms | 0.687 ms | 1.193 ms |
| 100,000 | 1,000, 80% in one | 7.087 ms | 5.920 ms | 5.998 ms | 6.256 ms |
| 1,000,000 | One repository | 83.946 ms | 84.590 ms | 85.323 ms | 85.209 ms |
| 1,000,000 | 100 uniform | 6.952 ms | 5.970 ms | 4.418 ms | 5.206 ms |
| 1,000,000 | 1,000, 80% in one | 90.118 ms | 97.193 ms | 95.252 ms | 95.910 ms |

For the uniform million-document fixture, increasing from one routed shard to eight lowered
initial indexing from 4,176 to 2,852 projections/s, increased committed Lucene files from 89 to
883, and increased the full MassIndexer rebuild from 19.32 to 23.67 seconds. The text-query saving
therefore has an observable operating cost. A single large repository remained in one shard;
the skewed million-document layout still placed 825,481 of 1,000,550 documents in its busiest
shard. Routing cannot divide that hot repository.

The other backend was not consistently faster. With one million uniform projections and eight
shards, `lucene-next` routed text p95 was 6.426 ms versus 4.753 ms for same-filter fan-out;
at one shard it was 17.788 versus 6.263 ms. All configurations passed the correctness checks,
but these contrary performance observations do not support a backend or routing default change.

[All 48 observations](../evidence/search-routing-large-2026-11-07/derived/observations.csv) ·
[Both backend tables and operating costs](../evidence/search-routing-large-2026-11-07/derived/tables.md) ·
[Charts, provenance and measurement limits](../evidence/search-routing-large-2026-11-07/README.md)

### Earlier bounded 10,000 projection observations

The local run used Temurin 21, four JVM processors, a 512 MiB heap and 80 warmup plus 250 timed
samples per query mode. Heap is the sampled whole-JVM peak during indexing and querying; disk is
committed Lucene files plus the H2 file measured separately. These are single-run point estimates
from a synthetic workload. Indexing, purge, startup and heap figures are sensitive to JIT warmup,
background merging, filesystem cache and runner load. They do not establish a production crossover.

| Repository layout | Routed text p95 at 1 / 8 shards | Same-filter fan-out p95 at 8 | Files at 1 / 8 shards | Documents in busiest shard at 8 |
| --- | ---: | ---: | ---: | ---: |
| One repository | 1.88 / 1.79 ms | 1.72 ms | 44 / 55 | 10,550 of 10,550 |
| 100 uniform repositories | 0.50 / 0.38 ms | 0.79 ms | 32 / 259 | 1,478 of 10,550 |
| 100 repositories, 80% in one | 1.12 / 2.09 ms | 2.40 ms | 38 / 271 | 8,697 of 10,550 |

The stable Lucene run shows why repository count and skew matter. The uniform fixture benefited
from avoiding query fan-out at eight shards, while the one-repository fixture still populated only
one shard. With skew, routing could not split the hot repository and indexing generated many more
files. Keep the one-shard baseline until a representative workload shows that query savings pay for
index, file, restart and administrative query costs.

[Stable raw measurements](../evidence/search-routing-2026-11-07/stable-shards.csv) ·
[Stable environment](../evidence/search-routing-2026-11-07/stable-environment.properties)

![Routed and fan-out repository text p95 at 1, 2, 4 and 8 shards](../evidence/search-routing-2026-11-07/query-latency.svg)

![Initial indexing throughput by repository distribution and shard count](../evidence/search-routing-2026-11-07/indexing-throughput.svg)

To reproduce the local shape without GitHub Actions:

```bash
mvn -B -pl jgit-storage-hibernate-search -am test \
  -Dtest=SearchRoutingPerformanceIT -Dsurefire.failIfNoSpecifiedTests=false \
  -Djacoco.skip=true '-DargLine=-Xmx512m -XX:ActiveProcessorCount=4' \
  -Dsearch.routing.run=true -Dsearch.routing.documents=10000 \
  -Dsearch.routing.repositories=100 -Dsearch.routing.warmup=80 \
  -Dsearch.routing.samples=250 "-Dsearch.routing.output=$PWD/target/search-routing"
python3 .github/scripts/plot-search-routing.py target/search-routing/shards.csv target/search-routing
```

For the A/B run, repeat the Maven command with a different output directory and
`-Dhibernate-search.lucene-backend-artifact=hibernate-search-backend-lucene-next`. Only build the
Search reactor subset for the experiment; do not load both Lucene versions in one application.
Hibernate Search 8.4's `lucene-next` is incubating and requires Java 21 or later. Each trial uses a
fresh index; the comparison does not establish index format compatibility in either direction.

The matching 15-cell run with Lucene 10.4.0 also verified routed hit equality and recovery. With
100 uniform repositories and eight shards, routed versus fan-out text p95 was 0.57 versus 1.16 ms
(0.38 versus 0.79 ms with Lucene 9.12.3). Initial indexing in that cell was about 3,364 versus 2,548
rows/s, but other cells favored 9.12.3. The two variants were run sequentially, once each on the same
host and JVM budget. There is no evidence for a backend default change.

[`lucene-next` raw measurements](../evidence/search-routing-2026-11-07/next-shards.csv) ·
[`lucene-next` environment](../evidence/search-routing-2026-11-07/next-environment.properties) ·
[`lucene-next` latency chart](../evidence/search-routing-2026-11-07/next-query-latency.svg) ·
[`lucene-next` indexing chart](../evidence/search-routing-2026-11-07/next-indexing-throughput.svg)

## Choose a deployment topology

| Deployment | Recommendation and consistency boundary |
| --- | --- |
| One Search node, including an application with many repositories | Keep local filesystem Lucene and one shard. Search updates follow the database commit; `sync` also waits for visibility, but a process or backend failure can still leave the derived index behind the database. Repair from the SQL projection or Git. |
| Many Git application nodes, one Search owner | Use one active Search owner on a local volume. Other instances can publish Git data; hand off projection work through a durable source if it must survive application failure. Stop the old owner and verify committed data or replay before starting a replacement. |
| Many Search instances that need a shared view | Evaluate the [opt-in remote/outbox experiment](search-remote-outbox-experiment.md) against the actual workload. Its 1/2/4-JVM runs verify mapping, transactional event persistence, rollback and producer-crash replay through PostgreSQL/OpenSearch. Visibility is eventual. Large remote indexes, production migrations, deletion/rebuild and backend/processor failures still need qualification. |
| Many repositories on one Search owner with a proven query bottleneck | Compare 1/2/4/8 physical shards in the representative layout. One very large repository still maps to one shard. Keep cross-repository latency and operating cost in the decision. |

A `local-filesystem` Lucene directory belongs to one Search writer. Do not mount the directory
for concurrent use by multiple application instances, even if they share a database or a network
volume. The [process test](../../jgit-storage-hibernate-search/src/test/java/io/github/carstenartur/jgit/storage/hibernate/search/LuceneSingleWriterProcessH2Test.java)
uses separate databases to prove that the second JVM fails on Lucene's native file lock. It
terminates the original writer after a synchronous commit, reopens the committed document and
successfully indexes a second document. The repository routing integration test separately
interrupts a Git replay after one committed batch and verifies that a restart and retry clear the
partial projection without deleting any other repository. This evidence applies to the local filesystem;
file locking is not a distributed lease or a failover protocol.

The [Hibernate Search 8.4 reference](https://docs.hibernate.org/search/8.4/reference/en-US/html_single/)
describes routing, the single-node Lucene architecture, the remote architecture with outbox polling,
and `lucene-next`. The local large matrix does not measure networked databases or multiple Search
instances. The separate [shared-backend evidence](../evidence/search-outbox-2026-11-07/README.md)
retains 42 observations from one, two and four application JVMs against PostgreSQL/OpenSearch.
It measures fixed total 50/500-document bursts, acknowledgement and visibility separately, native
database/network overhead and replay of 50 durable events after a producer crash. The smaller
acknowledgement does not offer the same guarantee as a direct `write-sync` acknowledgement.

The opt-in `search-remote-outbox` Maven profile adds the pinned
`hibernate-search-backend-elasticsearch` and `hibernate-search-mapper-orm-outbox-polling` artifacts.
They remain absent from the default classpath. The fixture creates a disposable schema; production
Flyway migrations do not create outbox event or agent tables. Representative 100,000/1,000,000-row
remote workloads, processor/backend failures, production network conditions and capacity/cost
still need dedicated evidence. There is no measured general scale-out crossover or default change.
