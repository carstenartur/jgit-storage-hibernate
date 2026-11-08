# Hibernate Search runtime tuning benchmark

Use the [Hibernate Search Runtime Tuning workflow](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/search-runtime-performance.yml) to compare indexing coordination, Lucene writer settings, and projection batch sizes on PostgreSQL and local filesystem Lucene. Every run uses the same deterministic Git history generator and the `content-v1` Search profile. It records burst submission, time until the burst is searchable, query latency during the burst, and time until a rebuild is searchable.

Select one workload per manual run:

| Workload | Total commits | Burst commits | Settings matrices |
| --- | ---: | ---: | --- |
| `100-50` | 100 | 50 | Smoke, full |
| `1000-50` | 1,000 | 50 | Smoke, full |
| `1000-500` | 1,000 | 500 | Smoke, full |
| `10000-50` | 10,000 | 50 | Smoke |
| `10000-500` | 10,000 | 500 | Smoke |

The commits preceding the burst are indexed during setup for the burst and concurrent query measurements. Rebuild measurements start with an empty projection and index the entire history. At 10,000 commits the smoke settings matrix limits the number of times the fixture must be rebuilt. Pull requests and pushes still run `100-50` with smoke settings; the schedule still runs `100-50` with full settings. Both use the host resource profile.

| Resource profile | Benchmark JVM CPU affinity | Initial heap | Maximum heap |
| --- | --- | ---: | ---: |
| `host` | Inherited from the runner | 768 MiB | 1 GiB |
| `constrained` | One logical CPU | 256 MiB | 512 MiB |
| `normal` | Four logical CPUs | 768 MiB | 1 GiB |

The two explicit CPU profiles pin the Maven test launcher and its JMH forks to allowed Linux CPUs and set the fork's JVM processor count to match. The PostgreSQL container uses the Docker host's normal allocation. Compare runs on the same source commit and runner type. Hold the workload and matrix fixed when comparing CPU and heap budgets, or hold resources fixed when comparing workloads.

The run artifact includes `environment.properties` (commit, matrix, workload, CPU affinity, heap flags, Java, Hibernate Search, Lucene and database versions), `cpu.json`, raw JMH results, grouped chart data and the recovery test report. Only automatic smoke runs on `main` enter the [public chart history](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-hibernate-search-runtime-burst-submission-p-50). Manual artifacts support comparisons of larger fixtures and resource budgets.

The smoke and full matrices collect two and three measured shots per scenario, respectively. Treat shot-based upper percentiles as exploratory. JMH allocation and GC counters include fixture setup and teardown. Concurrent query sampling ends after the producer completes and at least five queries have run. The query operation's primary timer also includes its later visibility wait and executor cleanup; it is not the sampling-loop duration. The separate visibility measurements capture the wait for the indexed burst to become searchable.

## What a successful write acknowledges

These tests use the pinned Hibernate Search 8.4.0.Final/Lucene 9.12.3 with the default, no-coordination indexing plan. The database transaction commits before the Lucene work; it is possible for projection rows to exist even after an index commit error. The following modes are values for `hibernate.search.indexing.plan.synchronization.strategy`:

| Strategy | What the caller waits for | Can a successful return precede Lucene durability? | Does it wait for immediate search visibility? |
| --- | --- | --- | --- |
| `write-sync` (reference) | Apply changes and commit the index | No | No; on local Lucene with `refresh_interval=0`, the next query refreshes a changed reader |
| `sync` | Apply, commit and refresh | No | Yes |
| `read-sync` | Apply and refresh | Yes | Yes |
| `async` | Submit work | Yes | No; queueing and refresh contribute to visibility lag |

`hibernate.search.backend.io.refresh_interval` is in milliseconds. A positive value lets queries reuse a reader refreshed within that interval. It is not a bound on end-to-end lag, because indexing may still be queued and the reader refresh is driven by queries. `sync` and `read-sync` can force a refresh. The matrix leaves `hibernate.search.backend.io.commit_interval` at its 1,000 ms default for commits that are not forced by the synchronization strategy. See the synchronization and Lucene I/O sections of the [Hibernate Search reference](https://docs.hibernate.org/search/8.4/reference/en-US/html_single/).

`hibernate.search.backend.io.writer.ram_buffer_size` sets the writer's document buffer in MiB. It is not a heap cap. `hibernate.search.backend.thread_pool.size` sets the backend worker pool size. Match a deployment's worker count and heap budget to its CPU quota and concurrent indexes. The larger worker and buffer combinations in the full matrix are stress cases. The `batch-*` scenarios set both `jgit.storage.hibernate.search.index_batch_size` and `hibernate.jdbc.batch_size`; interpret them as combined projection transaction/JDBC batch experiments.

## Failure and restart behavior

`SearchRuntimeRecoveryTest` uses a disposable file-backed HSQLDB database and a persistent Lucene directory. It force-terminates the writer JVM with the SessionFactory open, then reopens the same database and index. The tests check the exact projection and search hit IDs and confirm that the Git ref survives:

- Successful `write-sync` and `sync` writes survive the process kill. The first query with each tested `refresh_interval=0` configuration sees all three fixture commits before termination.
- `async` returns successfully before Lucene commits. With the commit paused at filesystem sync, the crash leaves three projection rows but only the one previously committed search hit. An incremental retry skips the two existing rows. Rebuilding from Git restores all three hits.
- An interrupted rebuild reports its one committed batch, which survives restart. Rebuilding again replaces the partial projection and restores the entire history.
- An injected Lucene commit I/O error is visible to the `write-sync` caller after the database rows commit. With `async`, the submitter returns and the failure arrives through the configured background handler. The benchmark fails on that handler's first error, including errors reported during teardown.

Use `async` only with a tested `hibernate.search.background_failure_handler` that alerts on failures and an operational plan to reconcile the index with Git. A successful submit is not a durable or immediately searchable write. If drift is suspected, stop competing writers, rebuild the affected repository's projection and verify the expected search results before relying on it. A process kill can drop queued changes without invoking a failure handler; alerting does not replace restart reconciliation.

Keep the existing `write-sync` reference and raw Hibernate Search settings. For applications that require a durable write to be searchable immediately, `sync` is an explicit setting to validate with their workload. These measurements do not support adding named production profiles or weakening the default acknowledgment contract.

The [earlier local 1,000-commit comparison](../evidence/search-runtime-2026-11-07/README.md) uses a 500-commit burst and both explicit resource profiles. It includes raw JMH shots, a throughput versus visibility chart, concurrent query percentiles, and the committed index and heap observations. Async acknowledged work faster under both budgets, with a much longer visibility wait after acknowledgment.

The [completed hosted matrix](../evidence/search-runtime-hosted-2026-11-07/README.md) adds all four
cells below: 248 scenario/operation records and 660 measured shots, with unchanged raw inputs,
environments, provenance and reproducible charts/tables. The normal 10,000/500 reference submitted
1,222.9 commits/s with a 9.49 ms mean post-submission visibility wait; `async`/500 ms submitted
1,908.7 commits/s with a 406.87 ms wait. In the constrained cell, `batch-250` lowered submission
throughput and raised query tails relative to its reference. Equivalent full-matrix controls also
varied materially. These few-shot results support keeping the current acknowledgment contract,
not selecting a universal faster profile. The hosts differ between cells; see the report's exact
resource, percentile and GC interpretation limits.

## Hosted evidence branch

A push to `benchmark/search-runtime-full-196-*` can run the following hosted comparison. Include a change to `.github/benchmark-triggers/issue196-runtime-evidence.json` in the push if the branch has no changes to the workflow or other paths that already trigger it. Use the marker to record the source revision and purpose of the run.

| Workload | Resource profile | Settings |
| --- | --- | --- |
| `1000-500` | `normal` | Full |
| `10000-50` | `normal` | Smoke |
| `10000-500` | `normal` | Smoke |
| `10000-500` | `constrained` | Smoke |

The 1,000-commit run covers all synchronization and refresh values, writer buffers and thread counts, and transaction batch sizes in the full settings matrix. The three 10,000-commit jobs use the same seven smoke scenarios so that burst size and resource profile can be compared separately. The workflow runs at most two jobs at once and stores each result as `hibernate-search-runtime-results-<workload>-<resources>-<settings>`.

## Reading the measurements

Burst submission and ready time are in `ms/op` per burst. To express throughput as commits per second, divide the number of commits in a burst or rebuild by its elapsed time in seconds. Compare submission throughput with the visibility wait and concurrent query p50/p95/p99, since submission alone can move work off the application thread. One `GitCommitIndex` document represents one fixture commit. Rebuild ready checks all expected hits, so newer results are stricter than the older runs that checked only the burst suffix.

The commit generation advance is the difference from fixture setup to graceful close. Segment count, document count and referenced bytes describe the final committed Lucene index after close. An async result reaching this state does not make its earlier acknowledgment durable. The heap measurement sums peak usage from individual JVM heap pools from the end of setup until just before close; because the pool peaks may occur at different times, it is an upper bound, not a time-aligned sample. These measurements do not count individual refreshes or merges. Lucene writer tracing changes timing and should be collected separately when exact activity counts are needed. Refresh/merge activity and a continuous or overload workload remain [explicit requested evidence](../evidence/search-runtime-hosted-2026-11-07/README.md#remaining-requested-evidence); the final segment snapshots and finite bursts do not satisfy those parts of issue #196.

## Bounded sustained and overload diagnostic

The same registered workflow also has an explicit manual **`mode=sustained`**.
That mode runs `reference` and `sync-async-r500` under both `normal` and
`constrained` resources. Its four dedicated jobs do not run the larger JMH
settings matrix or publish into the existing JMH chart series. The other manual
workload/matrix/resource inputs apply only to `mode=matrix`; normal PR, push and
scheduled behavior is unchanged.

Each job executes two separately labelled passes, `timing` and then `trace`,
against fresh projection tables and a fresh filesystem index. Both use 1,000
seed commits, 100 transactions of ten commits paced at 100 ms intervals, and
50 further ten-commit transactions without pacing in the overload phase. The
resulting 2,500-commit history is synthetic and deterministic. One worker advances
the Git ref and indexes each batch while one worker repeatedly queries stable
seed hits and observes total index visibility. The owner samples progress, heap,
GC and producer stacks; there is no unbounded application executor or sample
collection.

This diagnostic deliberately fixes one backend worker, one indexing queue with
eight slots, a 16 MiB writer buffer and `max_buffered_docs=20`. Those small, recorded
limits make the I/O/queue interaction observable. They are not recommended
production defaults and are not the unchanged settings of the earlier JMH
reference. The normal JVM has four processors and a 1 GiB maximum heap; the
constrained JVM has one processor and 512 MiB. CPU affinity and effective JVM
limits are recorded and checked. PostgreSQL retains its normal container-host
allocation.

The new PostgreSQL container explicitly enables `fsync`, `synchronous_commit` and
`full_page_writes`. The runner records the actual `SHOW` values before the workload;
both the runner and exporter reject missing or disabled settings. This overrides
Testcontainers' `fsync=off` constructor default for these new observations. It
does not change the conditions or interpretation of any earlier measurement.

After the sustained producer finishes and visibility drains, the harness arms a
gate on actual Lucene output creation. It proves that the gate is reached, holds
it for at least 1.5 seconds, and observes an incomplete publication. In the async
mode it additionally requires a producer stack blocked in `BlockingQueue.put` or
`offer` beneath Hibernate Search's `BatchingExecutor.submit`, while the gate is
held. Merely configuring a small queue is not evidence that it filled. The
write-sync producer normally waits for completion of its index work instead.
The raw samples distinguish these states. The gate is always released before
workers are joined or storage is closed, including error and interruption paths.

After release, the remaining producer work and visibility must drain within a
bounded timeout. Exact SQL and Lucene object IDs must equal the Git-reachable
history, with no omissions or duplicates. The provider is then closed and the
persistent database and index are reopened for the same checks. This graceful
reopen does **not** make an earlier async acknowledgement durable; the separate
crash tests above retain that distinction. Worker failure, capture failure,
timeout, a missing activity stream or incomplete cleanup rejects the run.

### Raw records and measurement boundaries

| File | Scope |
| --- | --- |
| `metadata.properties` | Exact checkout/tree, versions, resource limits and diagnostic settings. |
| `transactions.csv` | Git-ref advance plus `CommitIndexer` call, not pure Search or complete object-ingestion latency. The phase labels identify the originating sustained/overload workload; overload transactions may finish during drain. |
| `queries.csv` | Stable top-ten query latency, labelled by the phase in which the query starts. The following total-visible count has its own observation timestamp and is outside that latency. A query can span a phase boundary. |
| `samples.csv` | Sampled live JVM heap, cumulative JVM GC counters, publication progress, last completed visibility observation and its age, active workers and producer wait state. Last-observed visibility lag is not internal queue depth. |
| `trace.csv` | Phase markers plus pinned Lucene InfoStream records in the trace pass. Only phase markers exist in the timing pass. |
| `membership.csv` | Every checked synthetic object ID and SQL/Lucene/Git/reopen membership. |
| `completion.properties` | Gate timestamps, drain durations, worker/handler status and verified counts. Written only after successful close/reopen and trace completion. |
| `sha256.properties` | Hashes of all seven raw files, checked before deriving results. |

The sampled heap maximum is not the instantaneous peak or a constant-space proof.
GC counters include the whole diagnostic JVM, including sampling and concurrent
queries. Visibility samples can become stale while a query is blocked at the
gate; the age is retained instead of inventing intermediate observations.
Percentiles are nearest-rank individual observations without confidence
intervals. The two passes run sequentially in the same JVM and database container;
neither database/OS cache is forcibly cleared. Trace timings include logging
overhead and must not be ranked against the timing pass or the original JMH data.

The trace pass enables `hibernate.search.backend.io.writer.infostream=true` and
captures `org.hibernate.search.lucene.infostream` at TRACE. Event definitions
follow the pinned [Lucene 9.12.3 IndexWriter source](https://github.com/apache/lucene/blob/releases/lucene/9.12.3/lucene/core/src/java/org/apache/lucene/index/IndexWriter.java):

- **Commit publication:** the segments-file publication event after `finishCommit`.
  It is distinct from a commit attempt and from a caller acknowledgement.
- **NRT reader-open work:** completion of `getReader` work. It does not prove that
  Hibernate Search replaced its reader with a different version.
- **Merge installation:** the segment-state update after successful `commitMerge`.
  Earlier merge-start/duration messages and aborted merges do not count. This is
  not a durable commit. An installation is attributed to its event phase; no merge
  duration is inferred when the merge starts in an earlier phase.

Setup, sustained, overload, drain, close and reopen activity remain separate in
the generated report. Activity counters are `null` in the untraced pass because
activity is unobserved. The validator rejects unexpected versions or missing real
activity in the instrumented pass.

For a downloaded coordinate artifact, reproduce each pass without changing raw
inputs:

```bash
python3 .github/scripts/test_summarize_search_runtime_diagnostic.py
python3 .github/scripts/summarize-search-runtime-diagnostic.py \
  target/search-runtime-sustained/raw/timing --output /tmp/runtime-diagnostic-timing
python3 .github/scripts/summarize-search-runtime-diagnostic.py \
  target/search-runtime-sustained/raw/trace --output /tmp/runtime-diagnostic-trace
```

The validator checks input hashes, contiguous transaction and Git identity sets,
phase boundaries, observed queue backpressure, resource budgets, drained counts,
close/reopen and handler/worker evidence before generating `summary.json`,
`activity-events.csv` and `summary.md`. The hosted workflow also requires the
exact checked-out commit and a non-skipped native test report. The small local
HSQLDB fixtures validate this machinery only; hosted diagnostic evidence must be
retained before treating the remaining issue #196 workload/activity requirements
as completed.

## Run locally

With JDK 21 or newer, Maven and Docker available, run from the repository root. This example chooses the first CPU allowed by the current Linux environment for the constrained profile:

```bash
mvn -B -pl jgit-storage-hibernate-benchmarks -am install -DskipTests
cpu="$(python3 -c 'import os; print(min(os.sched_getaffinity(0)))')"
taskset --cpu-list "$cpu" mvn -B -pl jgit-storage-hibernate-benchmarks test \
  -Dtest=SearchRuntimeRecoveryTest,HibernateSearchRuntimeTuningBenchmarkIT \
  -Dsearch.runtime.workload=10000-500 \
  -Dsearch.runtime.resources=constrained \
  -Dbenchmark.commit="$(git rev-parse HEAD)" \
  -Dbenchmark.resultFile="$PWD/target/search-runtime/jmh-result.json"
```

For `normal`, supply four allowed CPUs to `taskset`. For `host`, omit `taskset`. The local runner defaults to smoke settings; use `-Dsearch.runtime.full=true` for an eligible workload.

To run only the persistent recovery tests without Docker:

```bash
mvn -B -pl jgit-storage-hibernate-benchmarks -am test \
  -Dtest=SearchRuntimeRecoveryTest -Dsurefire.failIfNoSpecifiedTests=false
```
