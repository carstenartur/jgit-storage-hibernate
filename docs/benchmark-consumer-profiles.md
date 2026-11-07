# Consumer-shaped benchmark fixtures

The benchmark module contains three deterministic Git fixture generators for
[issue #211](https://github.com/carstenartur/jgit-storage-hibernate/issues/211).
They use synthetic data and the same public JGit operations on filesystem and
Hibernate repositories. Use a fresh, disposable bare repository for each
workspace and a fixed set of input arguments to reproduce its object IDs.
Each generator initializes the default branch to `main`.

| Shape | Generated Git data | What to hold constant |
|---|---|---|
| audio-analyzer | Each publication appends a pseudo-random binary blob under `refs/heads/artifacts` and an artifact ID and size manifest under `refs/heads/main`. One atomic ref update advances both branches, which retain earlier publications. | Choose a seed per repository, replay increasing publication numbers, and keep artifact sizes equal across compared implementations. Use Core alone for the persistence baseline. When enabling Search, choose `metadata-v1` so Search can index commit metadata without reading artifact payloads. |
| Taxonomy | Each workspace has exactly the requested number of reachable commits, with structured requirement and term edits, different authors, proposal branches, two-parent merges and ref updates. | Split the total commit count over separately named repositories and use stable workspace numbers. Commit times, reflog transitions and reflog messages repeat; storage backends timestamp reflog writes when they happen. |
| sandbox | A source tree includes stable background classes and a caller of the changing class. Revisions cycle through a method rename, class rename, package move and one small method-body edit. | Hold class and commit counts constant. Compare a one-file body edit against a fresh Java Analysis rebuild from the same history. The tree needs no downstream application dependencies. |

The Audio generator uses a fixed 64 KiB input buffer irrespective of payload
size; its Git/ORM/database path must still be measured separately. The Taxonomy
and sandbox generators flush objects before publishing at most 64 queued ref
updates at a time. Benchmarks can prepare histories outside timed operations
when comparing query or index costs.

## Running the consumer-shaped series

[Consumer-shaped Benchmarks](../.github/workflows/consumer-profile-benchmarks.yml)
runs the smoke matrix on pull requests that change the fixtures or measured
modules. Choose **large** in a manual workflow run for the larger coordinates.
Each job uploads its raw JMH JSON, GC metrics and log with a `fixture.json`
manifest. The manifest distinguishes this series from the generic dashboard
and records the actual checkout SHA, workload, database, cache and operation
names. The jobs do not append consumer samples to generic benchmark history.

| Shape | Pull request smoke | Larger manual comparison | Timed operations |
|---|---|---|---|
| audio-analyzer | One repository, 256 KiB and 2 MiB artifacts | One repository, 16 and 128 MiB artifacts, forced stateful/stateless writers with idle and bounded analysis pressure | `audioPublish` appends an artifact and its metadata. `audioReadRecent` resolves both refs and streams the payload through Git object hashing. |
| Taxonomy | Two workspaces, 50 commits each | 1k, 10k and 100k total commits over ten workspaces | `taxonomyExactPathSummary` combines author, time and exact path. `taxonomyTextSummary` and `taxonomyTextEntity` use the same full-text term and limit, with one Git object verification per result. Setup checks the two text result lists match. |
| sandbox | Ten generated classes plus the caller across five commits | 250 or 1,000 classes plus the caller across five commits | `sandboxAnalyzeBodyEdit` uses binding-aware JDT on the one file changed in the last commit. `sandboxRebuildSnapshot` rebuilds the binding-aware analysis of the same complete head snapshot. |

### PostgreSQL history, recovery and network conditions

[Taxonomy-shaped PostgreSQL Benchmarks](../.github/workflows/consumer-taxonomy-postgres.yml)
uses a disposable PostgreSQL 17.10 database and a filesystem Lucene index. Each
trial owns a randomly named schema. Closing the measured provider preserves both
stores; final cleanup drops only that trial's schema and index directory.

| Matrix | Total commits over ten workspaces | Database path | Operations |
|---|---:|---|---|
| Pull request smoke | 1,000 | Calibrated 0 and 20 ms RTT | Exact-path Lucene summary, literal-path SQL summary, text summary, hydrated text entities, bounded purge and full projection rebuild |
| Manual standard | 1,000 and 10,000 | Calibrated 0 and 20 ms RTT | The same five operations |
| Manual large | 1,000, 10,000 and 100,000 | Calibrated 0 and 20 ms RTT | The same five operations, with a 3 GiB heap |

The SQL and Lucene path operations use the same full fixture path, author and
time constraints. Setup compares their complete result membership, since SQL
literal-substring and Lucene exact-path semantics are not generally identical.
Every measured hit is then resolved through the authoritative Git repository.
The generic smoke operations and their chart anchors remain unchanged.

History construction uses a direct connection outside measurement. The measured
provider reopens through Toxiproxy, and a retained 20-sample `SELECT 1` calibration
must demonstrate the requested delay before JMH starts. `reopened` clears the
JGit block cache after reopening; `warm` walks the prepared Git history and reads
the text-query results first. Both conditions retain warm operating-system and
database caches. These labels do not claim a cold host or a restarted database
server. Select the cache condition in the manual workflow.

The lifecycle contract interrupts indexing after the first committed 50-item
batch, closes the provider, and verifies that the partial projection survives
reopening. It then purges that partial projection, rebuilds the 100-commit
workspace, reopens again, and compares all original text hits and the Git ref.
The neighboring workspace must retain all 100 projections. The same lifecycle
tests run locally with HSQLDB and filesystem Lucene; only the PostgreSQL workflow
establishes native PostgreSQL behavior.

Each artifact retains JMH JSON and logs, allocation/GC and ORM/JDBC counters,
database-native telemetry for each measured operation, filesystem index bytes,
RTT samples, the recovery record, and a `fixture.json` with the exact checkout
SHA and file hashes. Native counters describe the dedicated database/instance;
unsupported capabilities are recorded explicitly. Single-shot smoke values
establish complete operation and evidence coverage, not stable latency rankings.

To run against a disposable PostgreSQL schema locally, put the URL, user and
password properties in an owner-readable connection file as described by the
workflow, then use `-Dconsumer.profiles.matrix=postgres-taxonomy-smoke` (or
`postgres-taxonomy` / `postgres-taxonomy-large`) with
`-Djgit.storage.benchmark.connection-properties-file=/absolute/path/to/connection.properties`.
`jgit.storage.benchmark.postgresql.measurement-url` may route the measured provider
through a proxy while setup uses the direct URL. Set
`-Dconsumer.profiles.taxonomy.rttMillis=20` only when that proxy is configured;
calibration rejects an unapplied delay. The indexing and purge batch controls are
`consumer.profiles.taxonomy.indexBatch` and `consumer.profiles.taxonomy.purgeBatch`,
defaulting to 50 and 250. Raw JMH parameters retain both values.

For a workspace containing `C` commits, projection replacement requires at least
`ceil(C / purgeBatch) + ceil(C / indexBatch)` modifying transactions. Additional
existence, final-page and verification queries increase that floor. SQL summary
queries require at least one database request per queried workspace, while
Lucene summaries can avoid ORM hydration; authoritative Git verification may
still read the database when its object cache is empty. A networked transaction
also has to reach the server to commit. These lower bounds explain why batch and
cache conditions belong with each measurement.

To run the smoke suite locally from the repository root with Java 21 or newer:

```bash
mvn -B -ntp -pl jgit-storage-hibernate-benchmarks -am test \
  -Dtest=ConsumerBenchmarkFixturesTest,ConsumerProfileBenchmarkTest \
  -Dsurefire.failIfNoSpecifiedTests=false -Dconsumer.profiles.run=true \
  -Dconsumer.profiles.output="$PWD/target/consumer-profiles"
```

Use `-Dconsumer.profiles.shape=audio`, `taxonomy`, or `sandbox` to select
one shape. `-Dconsumer.profiles.matrix=large` selects the larger coordinates
with one warmup and three samples per coordinate. The smoke profile runs one
single shot per coordinate in a fresh JVM. Sandbox uses a 1 GiB maximum heap
at both sizes. Audio and Taxonomy use 1 GiB for smoke and 3 GiB for the large
matrix. Audio pressure runs one background CPU worker that repeatedly touches
a fixed 32 MiB working set. It starts before measurement and stops before
the repository closes. To exercise individual controls on the smoke sizes,
set `-Dconsumer.profiles.audio.writer=stateful,stateless`,
`-Dconsumer.profiles.audio.pressure=idle,bounded` or
`-Dconsumer.profiles.audio.chunkBatch=4,16`. The batch control changes how
many chunks the writer retains per batch. The one MiB production chunk size
stays fixed. The raw JSON records each chosen setting, retained analysis
bytes, JDBC batch executions and database payload reads and writes.
Fixture setup happens before the timed operation. The GC profiler
reports whole-iteration allocation, which can include JVM, Hibernate, and
fixture initialization. Use that output to spot capacity pressure, and collect
an allocation profile scoped to the operation for per-operation claims.

## What the evidence supports

| Shape | Smoke observation | Operational use |
|---|---|---|
| audio-analyzer | The 256 KiB and 2 MiB publications consumed all payload bytes and advanced the two refs. Their warm readback verified the entire payload with zero database payload reads. Across both forced writers and both pressure settings, the publications used one JDBC batch and wrote 264,544 or 2,100,111 database payload bytes. | Keep the current writer threshold for these sizes. They show no batch-count or payload reduction from forcing either writer. Use cold and native database runs to compare storage and log costs. |
| Taxonomy | Both text projections returned the same 40 commit IDs. The summary path prepared zero ORM statements while entity hydration prepared two, one per workspace. | Prefer summary hits for result lists that only need commit metadata. Hydrate entities when a caller needs entity fields. |
| sandbox | The method-body edit required analysis of one source file. The complete head snapshot analyzed 11 files. The larger 1 GiB-heap runs analyzed 251 and 1,001 files per complete snapshot and still just one file per body edit. Both analysis paths resolved the synthetic sources. | Evaluate source-scoped reanalysis for dependency-free body edits. Retain project-wide analysis for cross-file impact, renames and moves until invalidation and result equivalence have been verified. |

These were local observations using Java 21 JMH forks. The pressure matrix
and the larger sandbox runs built the corresponding Core, Search and Java
Analysis modules from source in the same reactor. They establish operation and counter
behavior for these fixtures, without providing reproducible timing rankings
across releases or environments. Audio runs with Search disabled. Taxonomy
uses embedded HSQLDB and in-memory Lucene; its history and index are prepared
before queries, and verification reads through the existing repository.
Sandbox uses a temporary filesystem Git fixture, reads source snapshots
before measurement and analyzes them without starting a database or server.
The sandbox rebuild is a whole **head snapshot**, with no historical scan
or persisted semantic index. The one-file operation measures the cost of
analyzing an already identified edit; it does not implement or time a general
incremental indexer.

A binary publication must consume at least the payload bytes and advance both
refs. A verified query must resolve every returned commit in its own repository.
A complete sandbox head rebuild must read each source at least once. The smoke
series does not establish JDBC batch or network round-trip lower bounds.
Embedded HSQLDB has no separately sampled database log or measured network RTT.
For PostgreSQL and SQL Server transaction-log, batching and pack throughput
evidence see [database-native telemetry](operations/database-native-telemetry.md).
Concurrent audio repositories, alternate physical chunk sizes, and sandbox
persisted index size remain separate experiments. The PostgreSQL lifecycle and
RTT workflow above covers the Taxonomy provider/index restart and purge path;
it does not claim a full database-server restart or production concurrency.

Use `fixture.json` to match a run to [Real Consumer
Compatibility](../.github/workflows/consumer-compatibility.yml) for the same
checkout SHA before promoting a timing result. For release, main and candidate
comparisons, launch the workflow from one fixed harness revision with the same
profile. Set **library-ref** to the approved release tag `v0.12.0` or a full
commit SHA reachable from canonical `main`. Run candidate code from its
workflow branch or pull request with **library-ref** blank. Comparison
jobs disable the Maven cache. The comparison checkout receives only the
fixture generators and benchmark sources. Its
Maven reactor still builds the library and dependencies from the selected
revision. The artifact records both `harnessSha` and `checkoutSha`; leave
**library-ref** blank to measure the workflow's own revision. Join real
consumer contract evidence by `checkoutSha` before promoting a result. The
[consumer relevance tags](benchmark-consumer-relevance.md) describe pinned
real-consumer contracts independently of synthetic fixture selection. A
Taxonomy-shaped Search result does not establish that the Taxonomy contract
uses Search. Keep existing dashboard series and anchors and validate a
production default across relevant workload shapes.
