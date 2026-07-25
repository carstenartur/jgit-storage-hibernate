# Benchmarks

`jgit-storage-hibernate` uses JMH for repeatable performance measurements and publishes the results as time-series charts.

The benchmark module is:

```text
jgit-storage-hibernate-benchmarks
```

It is not a runtime dependency for consumers. It exists for CI, maintainers and release review.

## Repository backend comparison

The storage benchmark runs the same JGit workload against three repository backends:

| Backend label | Implementation used by the benchmark | Environment |
|---|---|---|
| `JGit + filesystem` | JGit `FileRepository` | Fresh temporary bare repository |
| `JGit + HSQLDB (in-memory)` | `HibernateRepository` | HSQLDB 2.7 in-memory database |
| `JGit + PostgreSQL` | `HibernateRepository` | PostgreSQL 17.10 container managed by JUnit Testcontainers |

The measured methods use the common public JGit `Repository` API. Backend-specific construction, schema creation and cleanup happen outside measured invocations.

The comparison currently covers:

```text
writeBlob
readBlobFromWarmCache
writeCommitAndUpdateRef
reopenAndResolveMain
```

`readBlobFromWarmCache` measures repeated application-level retrieval of one already accessed object through JGit's normal caches. It is deliberately not described as a physical filesystem or database read. The write and reopen operations exercise the respective storage implementation.

All results use JMH average time in `ms/op`, so lower values are better.

## Maven, JUnit and Testcontainers architecture

The canonical entry point is the Maven profile `benchmark-comparison`.

Maven Failsafe runs `RepositoryBackendBenchmarkIT`. That JUnit integration test:

1. starts PostgreSQL with the repository's established Testcontainers/JUnit extension pattern;
2. obtains the dynamically mapped JDBC URL and credentials;
3. launches the JMH forks for filesystem, HSQLDB and PostgreSQL;
4. passes the Testcontainers connection properties to each PostgreSQL JMH fork;
5. asserts that all four operations were recorded for all three backends;
6. writes the raw JMH JSON and text output.

GitHub Actions does not declare or manage a PostgreSQL service. The workflow only sets up Java and invokes Maven. The same Maven profile is therefore executable from a normal checkout on any machine with Java 21, Maven and Docker.

The profile is not active during ordinary `mvn verify`, so routine builds do not unexpectedly run a performance suite or start a container.

## Public performance history

The benchmark workflow records successful `main` results with `github-action-benchmark` and publishes the chart dashboard at:

```text
https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/
```

Pull requests execute the benchmark and upload the raw artifacts, but do not modify the public history. This prevents temporary pull-request revisions from becoming chart data. The first successful run on `main` initializes the `gh-pages` history branch.

Each operation/backend pair is stored as a separate time series, for example:

```text
writeBlob — JGit + filesystem
writeBlob — JGit + HSQLDB (in-memory)
writeBlob — JGit + PostgreSQL
```

The raw JMH output remains available as a workflow artifact beside the converted comparison JSON and Maven logs.

## Run locally

Docker must be available for Testcontainers. No database URL, fixed port, password or manually managed `docker run` command is required.

First build and install the benchmark module and its reactor dependencies without executing tests:

```bash
mvn -B -pl jgit-storage-hibernate-benchmarks -am install -DskipTests
```

Then run the dedicated JUnit/Testcontainers comparison through Maven:

```bash
mkdir -p target/benchmarks
mvn -B -pl jgit-storage-hibernate-benchmarks verify \
  -Pbenchmark-comparison \
  -Dbenchmark.resultFile="$(pwd)/target/benchmarks/jmh-result.json"
```

The important outputs are:

```text
target/benchmarks/jmh-result.json
target/benchmarks/jmh-output.txt
jgit-storage-hibernate-benchmarks/target/failsafe-reports/
```

The JUnit integration test fails if JMH does not return exactly four operations for each of the three configured backends.

### Convert local JMH output to chart input

```bash
python3 .github/scripts/convert-jmh-backend-comparison.py \
  target/benchmarks/jmh-result.json \
  target/benchmarks/backend-comparison.json
```

## CI workflow

`.github/workflows/performance.yml` performs only repository-independent orchestration:

```text
checkout
setup Java
mvn install -DskipTests
mvn verify -Pbenchmark-comparison
convert and publish results
```

PostgreSQL creation, readiness, mapped-port selection and cleanup remain inside JUnit/Testcontainers. This keeps the behavior locally reproducible and avoids encoding integration-test infrastructure in GitHub Actions.

## Interpretation limits

This comparison answers whether the same small JGit operations became faster or slower over project revisions in the controlled environment. It is not a production sizing benchmark:

- HSQLDB is deliberately in-memory, while filesystem and PostgreSQL perform operating-system or database I/O;
- `readBlobFromWarmCache` is dominated by JGit's cache path and must not be read as backend round-trip latency;
- PostgreSQL runs locally in a Testcontainers container rather than over a production network;
- host and container performance vary, especially for I/O-heavy measurements;
- the workloads are intentionally small regression probes, not large clone, fetch or multi-user throughput tests.

The workflow therefore uses a conservative 150% regression alert threshold and keeps the raw JMH JSON for deeper investigation.

## Semantic history benchmark direction

The Java analysis module exposes semantic-history queries based on `JavaProjectAnalyzer`, `JavaSemanticDiff` and `SemanticHistoryQuery`. A practical next benchmark slice is comparing two analyzed commit snapshots and measuring:

- symbol extraction throughput;
- semantic diff throughput;
- query latency for moved symbols and impacted callers.

That work should remain separate from the storage-backend comparison so storage and semantic-analysis regressions are not conflated.
