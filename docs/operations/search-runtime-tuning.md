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

The run artifact includes `environment.properties` (commit, matrix, workload, CPU affinity, heap flags, Java and database versions), `cpu.json`, raw JMH results and grouped chart data. Only automatic smoke runs on `main` enter the public chart history. Manual artifacts support exploratory comparisons of larger fixtures and resource budgets. Persistent restart, interrupted indexing and production recommendations are tracked in [runtime tuning issue #196](https://github.com/carstenartur/jgit-storage-hibernate/issues/196).

The smoke and full matrices collect two and three measured shots per scenario, respectively. Treat shot-based upper percentiles as exploratory. JMH allocation and GC counters include fixture setup and teardown. Concurrent query sampling ends when the producer completes. The separate visibility measurements capture the wait for the indexed burst to become searchable.

## Run locally

With JDK 21 or newer, Maven and Docker available, run from the repository root. This example chooses the first CPU allowed by the current Linux environment for the constrained profile:

```bash
mvn -B -pl jgit-storage-hibernate-benchmarks -am install -DskipTests
cpu="$(python3 -c 'import os; print(min(os.sched_getaffinity(0)))')"
taskset --cpu-list "$cpu" mvn -B -pl jgit-storage-hibernate-benchmarks test \
  -Dtest=HibernateSearchRuntimeTuningBenchmarkIT \
  -Dsearch.runtime.workload=10000-500 \
  -Dsearch.runtime.resources=constrained \
  -Dbenchmark.commit="$(git rev-parse HEAD)" \
  -Dbenchmark.resultFile="$PWD/target/search-runtime/jmh-result.json"
```

For `normal`, supply four allowed CPUs to `taskset`. For `host`, omit `taskset`. The local runner defaults to smoke settings; use `-Dsearch.runtime.full=true` for an eligible workload.
