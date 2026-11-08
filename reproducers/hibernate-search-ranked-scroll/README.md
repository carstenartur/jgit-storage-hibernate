# Ranked Lucene scroll: retained-hit reproducer

This independent Maven project reproduces the remaining memory issue behind
[jgit-storage-hibernate #230](https://github.com/carstenartur/jgit-storage-hibernate/issues/230).
It uses Hibernate Search's existing public `SearchScroll` API. It does not provide
a replacement exporter or add an application paging API.

The project is opt-in and is not a module of the application reactor. Its
deliberate budget failure is therefore not part of normal application CI.

The fixture explicitly indexes assigned-ID objects through the public
[`SearchIndexingPlan`](https://docs.hibernate.org/stable/search/api/org/hibernate/search/mapper/orm/work/SearchIndexingPlan.html).
ORM JDBC metadata access and relational schema generation are disabled. No
database, database driver, container, or application module is required. The
configured PostgreSQL dialect supplies ORM mapping defaults; the program never
opens a PostgreSQL connection.

## Run

Use a HotSpot JDK 21 or newer and Maven. Run the program in its own JVM, outside
Maven's process. These commands use a Unix classpath separator:

```sh
cd reproducers/hibernate-search-ranked-scroll
mvn -B compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
java -Xms256m -Xmx512m -cp "target/classes:$(cat target/classpath.txt)" \
  io.github.carstenartur.reproducer.RankedScrollMemoryProbe \
  65536 128 target/retained-65536 1024
```

Arguments are the document count, chunk size, output directory, and an optional
budget for live ranked hits above the pre-scroll baseline. The example deliberately
sets a budget of eight chunks. With the measured Hibernate Search 8.4.0.Final
implementation, it finishes traversing and closing the scroll, writes evidence,
then exits unsuccessfully because its sampled retained-hit count exceeds 1,024.
Omit the last argument to collect the measurements without imposing that budget.
Use a fresh output directory for each run so older sample files cannot be mistaken
for new observations.

To test a candidate supported Hibernate Search release or locally installed
upstream build, rebuild using its version and run exactly the same fixture:

```sh
mvn -B -Dhibernate-search.version=8.4.1.Final clean compile \
  dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
```

That command is a reproduction recipe, not a claim that 8.4.1 fixes the issue.
The existing [source review](../../docs/evidence/search-scroll-upstream-review-2026-11-08.md)
found the relevant expanding-page implementation unchanged in 8.4.1.

## What is measured

The query uses an identical full-text term in every document, then sorts by score,
committer timestamp descending, and a 40-character object ID ascending. Four
documents share each timestamp, so the ID tie-breaker crosses chunk boundaries.
Every result is checked against the expected traversal order and field projection,
including a null author and an `Instant` with nanosecond precision. Hibernate
statistics must report no entity loads or prepared SQL statements, and every
opened ORM session must be closed.

Before opening the cursor, after selected chunks, and after releasing the closed
scroll, the probe requests HotSpot's live `GC.class_histogram` diagnostic. It uses
the documented platform
[`DiagnosticCommandMBean`](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.management/com/sun/management/DiagnosticCommandMBean.html)
entry point rather than attaching `jcmd` to another process. This exposes the same
diagnostic command and avoids attach restrictions in container environments.
The `-all` option is omitted, so unreachable objects are excluded, as documented
for [`GC.class_histogram`](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jcmd.html).
The code does not access Hibernate Search implementation classes or inspect their
private fields.

Each checkpoint writes its complete native histogram. `retained-hits.csv` extracts
the live `org.apache.lucene.search.FieldDoc` count, that class's shallow bytes,
and the shallow bytes of `ScoreDoc[]` arrays. `environment.txt` records versions,
fixture size, heap limit, and ORM lifecycle counters. These are reachable-object
measurements at specific traversal positions. They are not total retained graph
sizes or peak heap measurements: sort values and other objects referenced by a
`FieldDoc` consume additional memory. Unchanged index structures are excluded
from the ranked-hit count.

Histogram collection perturbs the JVM and triggers garbage collection. There are
no throughput or latency claims. The raw `SearchScroll` and its last result go
out of scope before the final histogram; merely calling `close()` while retaining
the raw scroll object could still retain its last page. The application's
`CommitSearchCursor` releases its source reference on close.

The classifier is intentionally tied to the measured Lucene field-sort result
representation. If the first active scroll sample contains no live `FieldDoc`
instances, the program fails with an unsupported-classification message instead
of silently reporting a successful memory bound.

## What a candidate fix still must prove

A lower retained-hit count in this fixture is only one qualification step. A
production replacement must also preserve snapshot membership and ordering while
the live index changes, configured custom Similarity, routing and implicit tenant
isolation, the complete compact projection contract, total query limits, and
resource cleanup after early close, interruption, or injected failures. This
minimal fixture uses one tenant, one shard, and equal relevance scores. It does
not establish those broader properties or replace the application's native CI
and PostgreSQL/SQL Server acceptance matrix.

The [retained 8.4.0 observations](../../docs/evidence/search-ranked-scroll-retained-2026-11-08/README.md)
show the same 128-row chunks retaining 4,096 live ranked hits in an 8,192-document
run and 32,768 in a 65,536-document run at the sampled midpoints.
