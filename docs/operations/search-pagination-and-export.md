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

The Search-backed path uses Hibernate Search's stateful scroll API. Structured-only queries use one forward-only Hibernate ORM result cursor. Both paths retain only a bounded result chunk in application memory instead of accumulating the complete export.

## Ordering and consistency

Structured scrolling uses the same deterministic ordering as structured pages: selected timestamp descending, then object ID ascending.

Search-backed scrolling also has an explicit stable sort. Chronological searches use timestamp plus object ID. Full-text searches use relevance score followed by timestamp and object ID as deterministic tie-breakers.

A scrolling cursor represents one sequential traversal. Changes indexed after that traversal starts are not a reason to reuse the cursor as a live subscription; close it and start a new query when the application needs a fresh view.

## Choosing the API

Use offset/limit when a person moves among a small number of UI pages and random access to a nearby page is useful. Use scrolling when the application consumes many results in order, writes an export, feeds a batch processor, or would otherwise request progressively larger offsets.

The scrolling API intentionally rejects a non-zero query offset. Combining a deep initial offset with a cursor would retain the expensive part of offset pagination while obscuring it behind an export-oriented API.

## Benchmark evidence

The manual [History Scroll and Export Benchmark](https://github.com/carstenartur/jgit-storage-hibernate/actions/workflows/history-scroll-export.yml) runs separate PostgreSQL 17 and SQL Server 2022 Testcontainers jobs. Select 10,000, 100,000, or 1,000,000 projected commits per run. It applies the normal schema migrations and seeds deterministic projections in bounded batches. Both repository-only ORM queries and exact-path Lucene queries traverse the same full export, with tied timestamps and a matching row in a second repository to check ordering and isolation.

Each job reopens the filesystem Lucene index with a new Hibernate session factory and warms the query shapes. It repeats exports with chunks of 25, 100 and 500 and compares them with nonempty offset pages at the start, first continuation, midpoint, end and near the default guard, when those windows exist. It checks the default guard before raising it on the disposable comparison factory. The 10,000-row fixture has no nonempty offset beyond the guard; use the larger fixtures to collect those observations.

The artifact includes:

- `exports.csv`: time until the first chunk is available, summed cursor API time, wall time and rows per second for the validated export, GC deltas, sampled used heap, and Hibernate session, JDBC connection and completed-transaction counts.
- `offsets.csv`: one offset page's latency, latency of the corresponding cursor chunk (later chunks continue an already open cursor), and summed cursor API time to reach that chunk from the start. Each compared window checks every projected hit and records the first and last object IDs.
- `environment.properties` and `cpu.txt`: database and driver versions, JVM, heap, CPU and runner, fixture version, offset bounds and source commit.

With Java 21+, Maven and Docker, run the same benchmark locally, replacing `postgresql` with `sqlserver` for the other database:

```bash
mvn -B -pl jgit-storage-hibernate-search -am test \
  -Dtest=HistoryScrollExportBenchmarkIT -Dsurefire.failIfNoSpecifiedTests=false \
  -Djacoco.skip=true -DargLine="-Xms1g -Xmx2g" \
  -Dbenchmark.scroll.backend=postgresql -Dbenchmark.scroll.documents=10000 \
  -Dbenchmark.scroll.output="$PWD/target/history-scroll" \
  -Dbenchmark.commit="$(git rev-parse HEAD)"
```

The harness checks cleanup on exhaustion, early close and interruption. These runs warm the application after reopening its session factory; they do not restart the database or clear filesystem caches. Heap figures are observations before, during and immediately after the export, without forcing GC, so they cannot establish retained heap or a memory budget. A sampled offset page is useful for random access; compare it with cursor prefix time for that use case, and with cursor chunk time when the cursor is already open. The H2 fixture exercises the harness as a correctness test. The broader selectivity matrix, chunk latency percentiles, native database plans, retained heap measurements and crossover conclusions remain tracked in [#230](https://github.com/carstenartur/jgit-storage-hibernate/issues/230).
