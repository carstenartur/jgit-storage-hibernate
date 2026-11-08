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

### Audio concurrency and bounded pressure

The manual `audio-concurrent-smoke` profile runs the two audio operations at
256 KiB and 2 MiB with one and four repositories. `audio-concurrent` uses
16 MiB payloads, both forced writers, one and four repositories, chunk batches
of 8, 16 and 32, one warmup and three measured publications or reads. Both use a
1 GiB heap and bounded analysis pressure. The existing generic smoke and large
matrices keep their established operation names and sizes.

One benchmark invocation releases a worker for each repository from a common
barrier. The workers share one connection pool and independently publish their
artifact/metadata refs or verify their complete recent payload. The counters
sum bytes, repositories and JDBC batch executions across all workers. A
four-repository observation represents four operations, so divide its time or
aggregate bytes by four only when discussing amortized work, not the latency
of an individual repository.

`consumer.profiles.audio.repositories` selects 1–16 independent repositories;
`consumer.profiles.audio.analysisMiB` selects an 8–128 MiB total working set;
`consumer.profiles.audio.analysisWorkers` distributes that working set over
1–8 CPU workers. The defaults are 32 MiB and one worker. These controls describe
synthetic CPU/memory contention, not an audio algorithm or a measured percentage
of the host's capacity. Background workers and repository executors must stop
when the trial closes.

`configuredRetainedChunkBytes` is the configured upper budget
`repositories × chunkBatch × 1 MiB`, not an observed heap high-water mark.
An uncompressed artifact requires at least its payload bytes to be consumed;
its pack adds Git headers and metadata. At least
`ceil(packChunks / chunkBatch)` chunk batches are needed for a chunked pack.
Use the raw GC, byte and batch counters to check this structure before comparing
timing. The production physical chunk size remains 1 MiB. Alternate physical
layouts belong to the independently versioned
[pack-layout experiment](operations/pack-storage-layout.md), which does not
reinterpret existing production packs.

The [retained 24-coordinate experiment](evidence/consumer-audio-concurrency-2026-11-07/README.md)
records 72 observations on an exact source revision. It demonstrates the
3/2/1 JDBC-batch tradeoff for chunk batches of 8/16/32 at 16 MiB, including
four concurrent repositories. Its source predates cancellation fixes and its
claim is limited to the measured one-analysis-worker, successful execution path.

### Sandbox semantic projection experiment

`sandbox-index-smoke` adds four operations over ten classes and the caller.
Pull requests run this separately from the original analysis-only series.
`sandbox-index` is the larger manual comparison: 250 and 1,000 classes plus the
caller, one warmup and three measurements, always with a 1 GiB heap.

| Operation | Measured work |
|---|---|
| `sandboxPersistBodyEdit` | Analyze the one known dependency-free body edit and atomically replace its run, symbols and references in one database transaction. |
| `sandboxRebuildSemanticIndex` | Analyze the full head snapshot and replace every file's projection, one bounded transaction per fixture file. |
| `sandboxPathSymbols` | Query symbols at the changed path and verify each hit's current blob and original commit through Git. |
| `sandboxMethodReferences` | Query calls to the renamed method and perform the same Git provenance verification. |

The experiment owns a disposable filesystem HSQLDB database and filesystem
Lucene index. It uses the existing incubating Java Analysis entity mappings;
the library does not acquire a supported projection writer, migrations or
repository-deletion integration from this fixture. No application server or
downstream application starts. The lifecycle tests close the provider, stop the
embedded database engine, reopen both persistent stores and verify all facts
and queries again. The tested pre-commit SQL constraint failure preserves the
previous SQL rows and Lucene documents, including after reopening. Synchronous
Search acknowledgment does not make SQL and Lucene one distributed transaction;
post-commit index-I/O failure recovery is outside this experiment.

This is a current-file view. Unchanged rows retain their actual original commit
IDs; they are accepted only if their path/blob still belongs to the current
snapshot. Querying only `commitId = head` would wrongly omit those reused rows.
After each measured operation, canonical sorted semantic facts, including their
path ownership and multiplicities, must match an independent full-snapshot
analysis. A single path is replaced atomically. Replacement of a whole project
uses multiple transactions and has no atomic project-wide publication contract.

The measured body edit is already identified by Git blob changes and has no
cross-file semantic impact. These results do not validate a general incremental
indexer for renames, moves, changed signatures or dependency changes. The full
operation rebuilds the current head, not every historical revision. Whole-file
transactions remain bounded because this fixture has small, fixed-shape source
files; arbitrary generated or exceptionally large files need separate controls.

Each observation retains ORM statements and transaction counts, JDBC batches, GC metrics
and filesystem `indexBytes`. Timing includes index-size accounting and query hit
verification. Complete semantic equivalence checks occur after the timer. The
GC profiler still covers the whole iteration, including snapshot preparation,
analysis and persistence outside the timer. `indexBytes` is a non-atomic live
on-disk gauge, not the memory consumed by the analysis engine or an Eclipse process. All
retained segments, including deletions awaiting Lucene merge, contribute to that
gauge; it is not a compacted minimum index size. All
index-byte gauges cover Lucene files, excluding database and Git files. All
queries use a prepared index in the same process; these are warm local-storage
conditions with no network RTT or database-native log sampling.

The [retained semantic projection experiment](evidence/consumer-sandbox-persistence-2026-11-07/README.md)
contains all eight larger coordinates and 24 observations on one exact source
revision. It supports one file/transaction for the proven body edit versus
251 or 1,001 files/transactions for a complete replacement, with equivalent
semantic facts. Its timing, GC and live-index gauges retain the limits above.

### Bounded native publication follow-up

The `publication-followup` manual mode in
[Taxonomy-shaped PostgreSQL Benchmarks](../.github/workflows/consumer-taxonomy-postgres.yml)
adds two operations. It leaves the existing smoke, lifecycle and larger manual
matrices intact. Pull-request consumer jobs also run the new publication
functional tests; their runtime is not retained as performance evidence.

| Operation | Fixed work per invocation | Native matrix |
|---|---|---|
| `taxonomyPublishDelta` | Append the same deterministic terminology change to each of four independent workspaces, advance each main ref by compare-and-set, and index the four new commits through `CommitIndexer` | 1,000 initial reachable commits, equally distributed over four workspaces; one or four publication workers; 0 and 20 ms configured RTT |
| `audioPublishWithWal` | Append one incompressible artifact and its metadata, atomically advance their two refs, with Search disabled | One repository; 2 and 16 MiB artifacts; current automatic writer and chunk-batch defaults; a bounded 32 MiB synthetic analysis working set with one CPU worker; native PostgreSQL at 0 ms configured RTT |

Each coordinate uses a 1 GiB heap, one fork, one warmup and three measured
publications. Histories grow by four Taxonomy commits or two Audio commits per
invocation. Measured publication numbers are therefore 2, 3 and 4, with matching
content and object IDs in every worker/library/RTT variant. The release and Main
comparison uses the identical harness copied onto immutable library sources:

| Library role | Source |
|---|---|
| Release | `v0.12.0`, commit `ba6aeecc338968df4279fb9c7c72b15bbb3577fb` |
| Main after PR #463 | `1c33640bc75206e81a9bc5722768860a2a2321f5` |
| Proposed | The exact commit selected by the manual dispatch |

This follow-up changes benchmark code, tests and documentation. It records the
Core, Search and Java Analysis production module trees for every source. When
pinned Main and Proposed have the same production trees, they do not represent
different production implementations; timing variation between them cannot
substantiate a new library optimization. A combined integration branch may also
contain separately reviewed production changes from other issues. Its actual
module hashes, and whether they differ from pinned Main, remain explicit.

The Taxonomy worker pool is created before measurement. A readiness gate releases
the active workers together; the one-worker variant processes the same four
tasks in sequence. JDBC batches are collected on each worker and then summed.
Failures cancel and join the workers before storage can close. Successful
workspaces can remain committed if another workspace fails: there is no
cross-workspace atomicity, nor an atomic Git-plus-Search publication contract.
An incomplete invocation cannot produce successful evidence.

After the timer and the native telemetry capture, every Taxonomy workspace must
have the expected refs, symbolic HEAD, complete reflog transitions, delta blob,
and complete reachable Git commit set. Sorted SQL and Lucene object-ID lists must
match that set, including cardinality. The provider and filesystem index are
then reopened and the complete verification repeated. Every newly published
projection also has its message, author/committer identities and times, changed
path and content checked against Git. Compound Lucene message/content, exact-path,
identity and time predicates must return all the new publications. Audio similarly verifies
both complete branch histories, metadata bytes, payload length and Git content
hash, plus the reftable reflogs written by its atomic `BatchRefUpdate`. That
reftable log is read through `RefDatabase`; Taxonomy's single-ref update is also
checked through the repository's durable SQL reflog API.

Native telemetry covers publication, before the verification and restart work.
The direct PostgreSQL Docker service explicitly sets `fsync`, `synchronous_commit`
and `full_page_writes` to `on`. Actual `SHOW` results are retained in the manifest;
the measured Hibernate provider also checks all three values before each
iteration, outside the timer and telemetry window. Missing or disabled values
reject the observation. This is the database engine's reported configuration,
not proof of a particular host storage device's durability guarantees.
Each observation needs a positive PostgreSQL WAL insert-LSN delta. Other
`pg_stat_wal` counters are retained with their availability and statistics-lag
limits; a zero lagged counter is not proof that no WAL was written. Native
counters are cumulative observations on a dedicated disposable database host,
not transaction-attributed physical write amplification. The irreducible logical
Audio input is its payload size; WAL includes storage, ref and database overhead
and has no claimed universal multiplier.

JMH GC profiling covers the entire iteration, including full validation and
provider reopening. Its allocation count is not publication-only allocation.
Verification may warm Git, database and operating-system caches; reopening the
provider is not a cold-storage experiment. Lucene bytes are a live, non-atomic
filesystem gauge after reopening, excluding database and Git files. Three
observations in one fork do not establish a stable production latency ranking.

The six native jobs retain raw JMH/native JSON, RTT calibration, functional-test
reports, an exact source snapshot and SHA-256 manifest. Their final comparison
job requires the same verified Git identities across library, worker and RTT
variants. The gate validates individual iterations, not accumulated JMH event
counter scores. Publication artifacts are uploaded only after a separate
connection-metadata check passes for the complete raw output (including Maven
logs and source snapshots) and both possible JUnit report locations. This gate
also runs after a failed measurement or provenance check; a failed, skipped or
unscannable privacy check prevents the follow-up upload.

To qualify the new measurements, dispatch this mode and
`consumer-compatibility.yml` separately on the same immutable branch commit.
Record the run IDs and candidate results before claiming same-source consumer
compatibility. Historical Audio, sandbox and PostgreSQL measurements retain
their original source identities. This section defines the follow-up harness;
it does not assert a successful native run before artifacts have been reviewed.

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
database-native telemetry for each measured operation, non-atomic live filesystem index bytes,
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
The original sandbox analysis-only series uses a temporary filesystem Git fixture, reads source snapshots
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
Alternate physical chunk sizes remain a separate layout experiment. The
sandbox semantic projection experiment above adds persisted index-size evidence
without changing the original analysis-only observations. The PostgreSQL lifecycle and
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
