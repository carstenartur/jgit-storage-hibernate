# Concurrent audio publication experiment

This local run used the immutable harness `30f569e97cd1dd703d707a7376dbb8060ec9d8fe`,
Java 21 JMH forks, a 1 GiB heap, embedded HSQLDB and Search disabled. One background
CPU worker touched a fixed 32 MiB working set. No other project Java verification
or rendering task ran during the 24-coordinate, 72-observation experiment.
Each coordinate had one warmup and three single-shot measurements.

The manifest retains the exact harness sources, library module tree hashes and
SHA-256 hashes of the uncompressed JMH JSON and log. This is a separate candidate
experiment from the older release/main evidence; those older measurements have
not been relabelled. Consumer compatibility on the published source remains a
hosted gate.

This source predates the subsequent multi-worker readiness and interrupted
cleanup fixes. The retained run exercised one analysis worker and successful
normal shutdown only. It is evidence for that path; the later focused regression
tests establish the corrected cancellation behavior, and these samples have not
been presented as a performance rerun of those fixes.

## Supported structural conclusion

All complete 16 MiB payload readbacks and both published refs passed verification.
Stateful and stateless writers wrote identical database payload byte counts at
every matched publication. Pack overhead makes each 16 MiB publication occupy
17 fixed-size production chunks.

| Chunk batch | JDBC batches per publication | JDBC batches for four repositories | Configured chunk budget for four repositories |
|---:|---:|---:|---:|
| 8 | 3 | 12 | 32 MiB |
| 16 | 2 | 8 | 64 MiB |
| 32 | 1 | 4 | 128 MiB |

For a four-repository worker constrained to a 32 MiB aggregate chunk budget,
select batch 8 and account for twelve JDBC batch executions per publication
round. Raising it to 32 saves eight batch executions but increases the configured
chunk budget by 96 MiB. This is an explicit workload-level budget decision; the
experiment gives no reason to change the production writer or chunk defaults.
The budget is a configuration bound, not a measured live-heap maximum.

The raw timing and GC samples are retained for inspection. Three samples on one
local host do not establish a stable throughput ranking. GC allocation covers
the whole iteration, including lifecycle work; it is not allocation scoped to
the timed publication. Recent-payload reads can use the in-process JGit cache.
All 18 one-repository read observations fetched zero database payload bytes;
all 18 four-repository reads fetched 67,131,618 database payload bytes each.
Thus fixture preparation did not produce equal cache residency across repository
counts. Comparing one versus four read timings mixes concurrency with database
storage reads and cannot isolate a concurrency speedup or slowdown.
There is no measured network RTT or separately sampled database-log counter in
this embedded run. Use the native database series for those conditions.

Run the structural and integrity check from the repository root:

```bash
python3 .github/scripts/verify-consumer-audio-concurrency-evidence.py \
  docs/evidence/consumer-audio-concurrency-2026-11-07
```
