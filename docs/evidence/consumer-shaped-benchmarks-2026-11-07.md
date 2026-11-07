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
The current library source passed all three [real-consumer contracts](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42410329161).
The nine-coordinate comparison and audio pressure run used the benchmark harness from
local revision `25ce2ffda12263fb986a47a95bb1dc5e3985f88c`.
The release reactor received byte-identical copies of the four fixture and runner sources.
The large sandbox series used the harness from
local revision `a1a26e35253c300d4876824d897f6467974eea18`.
The local Maven 3.9.16 reactor ran on JDK 25 and forked JMH 1.37 on Java 21.
Audio used Core with Search disabled and one optional worker retaining 32 MiB. Taxonomy used
embedded HSQLDB and in-memory Lucene. Sandbox analyzed a filesystem Git snapshot with binding-aware JDT.

The CSV keeps per-iteration counts. Blank fields identify metrics absent from that harness revision.
These runs establish operation and counter behavior. They do not establish comparative latency,
production database log or network costs, or application compatibility. Compare exact-revision
[real-consumer contract](../../.github/workflows/consumer-compatibility.yml) evidence before promoting
a run into application performance history.

The SHA-256 of the four concatenated harness sources was `6a762c0ca3772fc00148cdb2e79b8f1047157e4d858c4f88a19145c905dceca1` for smoke and pressure, and `72c3eac895ccf8a27671a141784159dbb89fb781e1c1f96543c98b13564c7a30` for large sandbox. Concatenate the generator, fixture test, runner test and benchmark class in that order.
