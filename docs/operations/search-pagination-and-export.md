# Hibernate Search paging and export scrolling

`GitHistorySearchService` supports two deliberately different result-consumption modes:

- **offset/limit pages** for shallow, interactive UI navigation;
- **closeable scrolling** for exports and other deep, sequential result traversal.

Deep offset pagination is not an export API. Every increasing offset asks the backend to locate and skip a larger result prefix, while a scroll keeps backend state and advances through bounded chunks.

## UI pagination

Existing list-returning APIs remain source-compatible:

```java
CommitHistoryQuery query =
    CommitHistoryQuery.forRepository("payment-platform")
        .matchingText("fraud")
        .offset(200)
        .limit(100)
        .build();

List<CommitSearchHit> page = search.findChangeSummaries(query);
```

By default, offsets up to `10_000` are accepted. Larger offsets fail explicitly instead of silently encouraging an increasingly expensive access pattern. Deployments may change the bound:

```properties
jgit.storage.hibernate.search.max_offset=20000
```

A value of `0` allows only the first offset page. The property must be a non-negative integer.

## Sequential export

For a complete export, use a zero-offset query and opt in to an unbounded total result limit:

```java
CommitHistoryQuery query =
    CommitHistoryQuery.forRepository("payment-platform")
        .matchingText("fraud")
        .committedBetween(from, to)
        .unbounded()
        .build();

try (CommitSearchCursor cursor = search.scrollChangeSummaries(query, 500)) {
  for (List<CommitSearchHit> chunk = cursor.nextChunk();
      !chunk.isEmpty();
      chunk = cursor.nextChunk()) {
    export(chunk);
  }
}
```

The cursor returns the lightweight `CommitSearchHit` projection rather than hydrating the complete `GitCommitIndex` entity. A chunk size must be between `1` and `1_000`; the default overload uses `100`.

`limit(...)` still has meaning with a cursor: it caps the total number of hits exposed by that cursor. `unbounded()` is therefore explicit rather than making every scrolling call unlimited by accident.

## Resource and cancellation contract

`CommitSearchCursor` is `AutoCloseable`. Always use try-with-resources when the caller may stop before exhaustion. Reaching the end, an exception, or thread interruption closes the underlying cursor automatically. Thread interruption is reported as `CancellationException`, and the interrupted status is preserved.

The Search-backed path uses Hibernate Search's stateful scroll API. Structured-only queries use one forward-only Hibernate ORM result cursor. Each call returns at most one configured chunk. Hibernate Search's Lucene backend can grow an internal ranked-hit buffer during traversal. ORM buffering depends on the JDBC driver. Consume each returned chunk before requesting the next so the caller retains only the current chunk.

## Ordering and consistency

Structured scrolling uses the same deterministic ordering as structured pages: selected timestamp descending, then object ID ascending.

Search-backed scrolling also has an explicit stable sort. Chronological searches use timestamp plus object ID. Full-text searches use relevance score followed by timestamp and object ID as deterministic tie-breakers.

A scrolling cursor represents one sequential traversal. Changes indexed after that traversal starts are not a reason to reuse the cursor as a live subscription; close it and start a new query when the application needs a fresh view.

## Choosing the API

Use offset/limit when a person moves among a small number of UI pages and random access to a nearby page is useful. Use scrolling when the application consumes many results in order, writes an export, feeds a batch processor, or would otherwise request progressively larger offsets.

The scrolling API intentionally rejects a non-zero query offset. Combining a deep initial offset with a cursor would retain the expensive part of offset pagination while obscuring it behind an export-oriented API.

## Benchmark evidence

The History Scroll and Export Benchmark runs PostgreSQL 17.10 and SQL Server
2022 CU20 Testcontainers jobs with the normal schema migrations. Pull requests
that touch the runner or cursor run the 10,000-commit fixture with one warm
trial per coordinate. To measure 10,000, 100,000 and 1,000,000 commits on both
engines, dispatch the workflow with `documents=all` or push an exact benchmark
commit to a branch named `benchmark/history-scroll-full-*`. A manual run can
also select one exact size. The full matrix uses three warm trials per
coordinate and separate artifacts for each database and size.
[Benchmark workflow](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/history-scroll-export.yml)

Each fixture uses tied timestamps, a matching row in another repository, and
the following filters. The runner checks the complete projected row and order
against a deterministic oracle for every export and sampled page. The oracle
walks the fixture one row at a time.

| Filter | API path | Fixture selectivity |
| --- | --- | --- |
| Repository | ORM | All rows |
| Repository and common exact path | Lucene | All rows |
| Repository and selected exact path | Lucene | About half |
| Repository and author | ORM | About one third |
| Repository and recent time range | ORM | About one quarter |
| Repository, exact path, author and time range | Lucene | About one twenty-fourth |
| Repository and rare exact path | Lucene | About one in 997 |
| Repository and unmatched exact basename | Lucene | Empty |

For each shape, warm runs exercise chunks of 25, 100 and 500. Before warming,
each full export path also gets a first-query trial on a freshly opened
Hibernate session factory. This reopens the persisted index and leaves the
database process and OS caches in place.

The artifact contains:

- `exports.csv` with time until the first row is available in the first
  returned chunk, cursor API time, total wall time, rows/second, approximate
  per-chunk p50/p95/p99 and GC deltas. The percentiles are upper bounds from a
  fixed-memory histogram, and zero denotes that an empty result had no
  nonempty chunk.
- Resource columns in `exports.csv` show the observed heap peak and process
  heap before and after requesting explicit GC outside the timed window.
  They also show open Hibernate sessions and borrowed JDBC connections before,
  during and after traversal, along with cumulative completed-transaction
  counters. The harness enforces a one-chunk result limit, one cursor session,
  one JDBC borrow for ORM only, and release on exhaustion, early close and
  interruption. The 2 GiB JVM cap is a process budget for these jobs. Process
  heap samples cannot establish a constant memory bound for the Lucene
  backend's ranked hits or distinguish cursor memory from other live objects.
- `offsets.csv` with validated pages at shallow and deep positions, including
  just below, at and beyond the configured default guard when those windows
  exist. The benchmark verifies rejection at 10,001 on a factory with the
  default guard and raises the guard on the disposable comparison factory.
- `comparisons.csv` and `comparison.md` with median latency for the offset
  page, the corresponding chunk on an already open cursor, and cursor API time
  to reach that chunk from the start. The report identifies the first sampled
  position where each comparison favored the cursor. Each threshold applies to
  the sampled windows and may vary at later offsets. The 10,000-commit export
  ends before any nonempty page beyond the guard.
- `environment.properties` and `cpu.txt` with database and JDBC versions,
  JVM, heap, CPU and runner, fixture version, guard values and source commit.

Use a single offset page for random access if the cost to traverse the prefix
exceeds the measured page cost. For an ongoing sequential export, compare
the page with the already-open chunk. Start with the API's current
100-row chunk and compare the 25- and 500-row results against your latency and
heap targets before changing it. A database-specific chunk recommendation
needs the repeated results from both engines. The SQL Server driver uses
adaptive buffering by default, while PostgreSQL uses the positive JDBC fetch
size in the ORM cursor transaction.
[SQL Server adaptive buffering](https://learn.microsoft.com/en-us/sql/connect/jdbc/using-adaptive-buffering)

With Java 21+, Maven and Docker, run one cell locally:

```bash
evidence="$PWD/target/history-scroll"
mvn -B -pl jgit-storage-hibernate-search -am test \
  -Dtest=HistoryScrollExportBenchmarkIT -Dsurefire.failIfNoSpecifiedTests=false \
  -Djacoco.skip=true -DargLine="-Xms1g -Xmx2g" \
  -Dbenchmark.scroll.backend=postgresql -Dbenchmark.scroll.documents=10000 \
  -Dbenchmark.scroll.output="$evidence" \
  -Dbenchmark.commit="$(git rev-parse HEAD)"
python3 .github/scripts/summarize-history-scroll-export.py "$evidence" \
  --documents 10000 --repetitions 3
```

The benchmark compares a single offset page with two cursor costs and leaves
repeated deep offset scans, native statement-plan and wait measurements,
database restart trials and dashboard publication tracked in #230. Hibernate
Search exposes a snapshot-consistent scroll and separate offset queries.
Replacing the scroll with multiple public query calls would change the
consistency contract as the index changes.
[Hibernate Search scrolling contract](https://docs.hibernate.org/search/8.4/reference/en-US/html_single/#search-dsl-query-scroll)
[Scalability issue #230](https://github.com/carstenartur/jgit-storage-hibernate/issues/230)
