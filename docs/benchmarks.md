# Benchmarks

`jgit-storage-hibernate` uses JMH for performance measurements.

The benchmark module is:

```text
jgit-storage-hibernate-benchmarks
```

It is not intended as a runtime dependency for consumers. It exists for CI, maintainers and release review.

## Build the benchmark JAR

```bash
mvn -B -pl jgit-storage-hibernate-benchmarks -am package -DskipTests
```

The shaded JMH executable is produced as:

```text
jgit-storage-hibernate-benchmarks/target/benchmarks.jar
```

## Run locally

Short local run:

```bash
java -jar jgit-storage-hibernate-benchmarks/target/benchmarks.jar \
  -wi 1 -i 3 -f 1 -r 1s -w 1s \
  -rf json -rff target/benchmarks/jmh-result.json \
  '.*HibernateRepositoryBenchmark.*'
```

Longer runs should increase warmup, measurement iterations and forks.

## CI workflow

`.github/workflows/performance.yml` builds the benchmark JAR, runs JMH, uploads `jmh-result.json` and writes `docs/badges/performance.json` for the README badge.

The performance badge is based on the first JMH result in the generated JSON. The full JSON artifact should be used for detailed review.

## Initial benchmark scope

The initial benchmark class measures core H2-backed repository operations:

```text
writeBlob
readBlob
writeCommitAndUpdateRef
reopenAndResolveMain
```

These benchmarks are intended to detect regressions and track broad performance trends, not to make absolute claims about all database backends or production workloads.
