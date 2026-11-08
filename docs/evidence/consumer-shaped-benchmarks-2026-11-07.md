# Consumer-shaped benchmark evidence

The [normalized measurements](consumer-shaped-benchmarks-2026-11-07.csv) retain 46 single-shot
observations. These synthetic profiles use [deterministic fixtures and fixed operation names](../benchmark-consumer-profiles.md).
They form a separate evidence series from the generic performance charts.

| Comparison | Coordinates | Observed result | Operational decision |
|---|---:|---|---|
| Release and current source | 9 per revision | The two revisions matched on every structural counter across the three profiles. | Use the same harness to identify counter changes before investigating timing differences. |
| Audio, forced writers and background pressure | 16 | At 256 KiB and 2 MiB, each publication used one JDBC batch and wrote 264,544 or 2,100,111 database payload bytes respectively. Both writer modes and pressure settings matched within each size. Warm readback verified the complete payload and transferred zero database payload bytes. | Keep the current writer threshold for these sizes. Use cold and native database runs to evaluate storage costs. |
| Taxonomy, two workspaces with 50 commits each | 3 per revision | Text summaries and entities returned the same 40 Git-verified hits. Summaries prepared zero ORM statements and entities prepared two. The combined author, time and exact-path query returned 10 hits. | Use summary projection for result lists that need commit metadata. |
| Sandbox, one body edit and complete head snapshot | 4, three samples each | In a 1 GiB heap, the body edit analyzed one file per sample. Full snapshots analyzed 251 or 1,001 files. | Consider source-scoped analysis for a known dependency-free edit. Cross-file changes still need invalidation and result equivalence evidence. |

The release checkout was [v0.12.0](https://github.com/carstenartur/jgit-storage-hibernate/tree/ba6aeecc338968df4279fb9c7c72b15bbb3577fb)
and the current library source was [3bfa6af](https://github.com/carstenartur/jgit-storage-hibernate/tree/3bfa6aff12014b88424c48cf349e33c794e80765).
The current library source at that measurement passed all three [real-consumer contracts](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42410329161).
The nine-coordinate comparison and audio pressure run used the benchmark harness from
local revision `25ce2ffda12263fb986a47a95bb1dc5e3985f88c`.
The release reactor received byte-identical copies of the four fixture and runner sources.
The large sandbox series used the harness from
local revision `a1a26e35253c300d4876824d897f6467974eea18`.
The local Maven 3.9.16 reactor ran on JDK 25 and forked JMH 1.37 on Java 21.
Audio used Core with Search disabled and one optional worker retaining 32 MiB. Taxonomy used
embedded HSQLDB and in-memory Lucene. Sandbox analyzed a filesystem Git snapshot with binding-aware JDT.

The CSV keeps per-iteration counts. Blank fields identify metrics absent from that harness revision.
The original [raw JMH JSON and logs](consumer-shaped-2026-11-07/manifest.json)
are retained as gzip files, together with both historical harness source snapshots.
The manifest records SHA-256 hashes of the uncompressed originals. Verify all 46
normalized observations, allocation/GC data, and source hashes with:

```bash
python3 .github/scripts/verify-consumer-benchmark-evidence.py \
  docs/evidence/consumer-shaped-2026-11-07/manifest.json
```

These runs establish operation and counter behavior. They do not establish comparative latency,
production database log or network costs, or application compatibility. Compare exact-revision
[real-consumer contract](../../.github/workflows/consumer-compatibility.yml) evidence before promoting
a run into application performance history.

The SHA-256 of the four concatenated harness sources was `6a762c0ca3772fc00148cdb2e79b8f1047157e4d858c4f88a19145c905dceca1` for smoke and pressure, and `72c3eac895ccf8a27671a141784159dbb89fb781e1c1f96543c98b13564c7a30` for large sandbox. Concatenate the generator, fixture test, runner test and benchmark class in that order.

## Separately retained follow-up experiments

The dashboard anchor still leads to this evidence index. Later experiments keep
their own immutable source, cache conditions and raw data; they do not overwrite
the original release/main comparison above.

| Experiment | Retained evidence | Supported operational consequence |
|---|---|---|
| [Native Taxonomy PostgreSQL](consumer-taxonomy-postgres-2026-11-07/README.md) | Five JMH/native observations at each of calibrated 0/20 ms RTT, plus partial projection, reopen and recovery checks. | Use summary hits when hydration is unnecessary; retain bounded rebuild batches and a Git-authoritative recovery path. |
| [Concurrent audio](consumer-audio-concurrency-2026-11-07/README.md) | 24 coordinates, 72 observations, one/four repositories, two writers and three chunk batches. | At a 32 MiB aggregate chunk budget across four repositories, batch 8 trades twelve JDBC batch executions for that explicit memory bound. Cache residency differs across repository counts. |
| [Persisted sandbox projection](consumer-sandbox-persistence-2026-11-07/README.md) | Eight coordinates, 24 observations, 251/1,001-file head snapshots in a 1 GiB heap. | For a known dependency-free body edit, one-file replacement produces equivalent semantic facts with one transaction instead of 251/1,001. |
| [Qualified integrated smoke and recovery](consumer-publication-2026-11-08/previous-integrated/consumer-211-final-ci-review.md) | Thirteen all-profile smoke observations, including both sandbox variants, plus ten PostgreSQL history/recovery observations and all three consumer contracts on exact PR merge `a528d951`. | Preserve the earlier operational decisions with an explicit same-source compatibility gate; single-shot times remain coverage evidence. |
| [Native concurrent publication and Audio WAL](consumer-publication-2026-11-08/README.md) | Fifty-four observations across pinned release/Main/Proposed and 0/20 ms RTT, with verified reopen, complete Git/ref/projection equality and all three consumer contracts on exact source `5ed8aad0`. | Use bounded parallelism for independent workspaces when RTT matters; retain Audio writer defaults where the native pair shows no structural advantage. Main and Proposed production module trees are identical. |

These comparisons do not establish a universal production default or a release
timing ranking for every follow-up profile. Exact-source consumer compatibility
is a separate publication gate, now explicitly retained for the integrated
smoke/recovery series and the native publication study. Concurrent Taxonomy
publication and Audio-specific native WAL are covered by the latter study.
The larger historical Audio and sandbox data keep their original sources; the
new evidence does not relabel them or provide a matched release baseline for
every historical extension. Further operation-by-operation comparisons belong
with any future cross-release claim. The [original-criteria assessment](consumer-publication-2026-11-08/README.md#original-acceptance-criteria)
distinguishes this future research from the completed issue #211 scope.
