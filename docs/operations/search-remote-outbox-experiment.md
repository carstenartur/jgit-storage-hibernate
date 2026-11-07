# Shared Search backend and outbox experiment

The `search-remote-outbox` Maven profile runs an opt-in topology experiment with Hibernate Search
8.4, a disposable PostgreSQL 17.10 database, and one shared OpenSearch 2.19.5 server. The test
starts 1, 2, or 4 independent application JVMs against the same database and index. Each JVM writes
the Search module's `GitCommitIndex` mapping through stateful Hibernate ORM transactions. An
explicit test-only schema setup creates the projection and outbox tables.

Both modes run the same fixture and use the same server. Direct mode uses `write-sync` with
OpenSearch's ordinary refresh schedule. A direct transaction acknowledgement waits for the
backend write. Outbox mode commits the projection and its durable indexing event together.
Separate processors read those events, and queries see them after backend refresh. The
acknowledgements have different guarantees, so compare acknowledgement and visibility as separate
measurements. [Hibernate Search architecture and compatibility](https://docs.hibernate.org/search/8.4/reference/en-US/html_single/)

For each topology, the application JVMs concurrently submit three 50-document and three
500-document bursts in total. The runner measures how long it takes all applications to acknowledge
the burst, then polls every 50 ms until the full burst is visible. PostgreSQL relation counters and
allocated outbox bytes show the database work. Network columns contain the change in each
container's ingress plus egress during the measurement window, including polling and visibility
probes. The row counters are cumulative PostgreSQL statistics and can lag the exact queue count.

In outbox mode, the runner then stops the processors and starts one producer with event processing
disabled. A rolled-back transaction must leave zero new projection or outbox rows. The producer
commits 50 new projections, and the runner checks that exactly 50 events are durable while Search
still shows the earlier projection. It kills the producer and starts a fresh cohort of processors.
Recovery succeeds after the new JVMs make all documents visible, drain the queue, and return all 50
replayed commits through `GitHistorySearchService`. The direct mode restart checks retention of its
already visible documents. The restart timer starts before the replacement JVMs boot.

The [retained 1/2/4-JVM observations](../evidence/search-outbox-2026-11-07/README.md)
include all 42 original burst/restart rows, verified artifact provenance and reproducible charts.
All three hosted jobs passed. Outbox acknowledgement was lower in these small bursts, while
visibility still followed the ordinary refresh schedule. Every topology replayed the 50 durable
events after producer termination and drained its queue. The fixture does not justify changing
the production topology or equating direct and eventual acknowledgement guarantees.

Run one topology on a machine with Docker and Java 21 or later.

```bash
mvn -B -pl jgit-storage-hibernate-search -am test -Psearch-remote-outbox \
  -Dtest=SearchRemoteOutboxIT -Dsurefire.failIfNoSpecifiedTests=false \
  -Dsearch.outbox.run=true -Dsearch.outbox.instances=4 \
  "-Dsearch.outbox.output=$PWD/target/search-remote-outbox"
```

A push to `benchmark/search-outbox-198-*` runs all three topologies on separate GitHub-hosted
runners. The workflow publishes `outbox.csv`, environment metadata, and per-JVM logs. Each job
reports the medians of its three observations per burst size. Compare timings within the same job
when assessing coordination overhead. [Hosted experiment](../../.github/workflows/search-remote-outbox.yml)

This fixture measures a small shared-backend workload and a producer crash before outbox
consumption. Mid-batch consumer failure, backend outages, multi-node search clusters, production
network conditions, and 100,000 or 1,000,000 projected documents still need dedicated runs.
