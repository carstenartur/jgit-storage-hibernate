# Persisted sandbox semantic projection experiment

The complete local run used immutable source
`92c4f9972ee2534276045b23199a897b353dc86c`, Java 21 JMH forks, a 1 GiB heap,
filesystem HSQLDB and filesystem Lucene. Eight coordinates retain three
single-shot measurements each, following one warmup. No other project Java
verification or rendering task ran during these measurements.

The manifest retains exact runtime sources, library module tree hashes and the
uncompressed SHA-256 hashes of the original JMH JSON and log. This is separate
from the older analysis-only release/main observations. The successful run
predates a subsequent live-directory gauge tolerance fix; these samples have
not been relabelled as a rerun of that follow-up.

## Supported decision

For the known, dependency-free method-body edit, replace only its current-file
projection. Each of the six edit observations analyzed one file and used one
transaction. All persisted semantic facts, including their owner paths and
multiplicities, matched the independent full-head analysis. The full alternative
reanalyzed and replaced 251 or 1,001 files. The difference in work is explicit:

| Operation | Files analyzed per observation | ORM transactions | Prepared statements | JDBC batch executions |
|---|---:|---:|---:|---:|
| Known body edit at either size | 1 | 1 | 8 | 2 |
| Full 251-file head replacement | 251 | 251 | 2,010 | 503 |
| Full 1,001-file head replacement | 1,001 | 1,001 | 8,010 | 2,003 |
| Prepared path or method-reference query | 0 | 0 | 1 | 0 |

Transaction counts come from Hibernate ORM statistics; a zero count for a query
does not claim that the database performs no implicit transaction work.

Every path query returned two symbols, and every method-reference query returned
one call. Each hit was checked against both its original Git commit and current
path/blob membership. Unchanged files retained their actual original commits;
the experiment did not relabel old facts with the current head commit.

The three focused lifecycle tests also proved provider/database-engine reopening,
rollback of a pre-commit SQL constraint failure, and rejection of a stale blob
hit. That SQL rollback test does not establish rollback of a post-commit Lucene
I/O failure. There is no distributed SQL/Lucene transaction, supported general
projection writer, repository deletion integration or production migration in
this benchmark-owned experiment.

## Measurement limits

Change discovery and dependency invalidation are outside the timed body-edit
boundary. This decision therefore applies to edits whose lack of cross-file
semantic impact is already established. Renames, moves, signatures and
dependency changes still require broader analysis and invalidation. Complete
semantic equivalence verification runs after each timed operation.

The raw timing samples are retained, but three samples in one fork do not
establish stable application latency ratios. The GC profiler includes complete
iteration preparation and verification. For example, a query's fixture can
allocate several GiB cumulatively while remaining within a 1 GiB Java heap;
that is not the allocation of the query itself or a peak-resident-memory claim.
No Eclipse process or unrelated application server was running in the fixture.

The live filesystem index samples ranged from 75,454 to 141,924 bytes for the
251-file fixture and from 228,313 to 284,963 bytes for the 1,001-file fixture.
These non-atomic gauges include retained segments and deletions awaiting merge.
They are neither compacted minimum sizes nor peak memory measurements. The
gauge covers Lucene files only and excludes the HSQLDB database and Git files.
The
synthetic source files are small and regular; real projects can have a different
mix of symbols, references and dependencies. Database and OS caches were warm;
there was no network or native transaction-log sampling.

Use the hosted consumer contracts on the integrated publication commit before
promoting this experiment into consumer performance history. The exact source
and conditions here remain attached to these original observations.

```bash
python3 .github/scripts/verify-consumer-sandbox-persistence-evidence.py \
  docs/evidence/consumer-sandbox-persistence-2026-11-07
```
