# Consumer publication evidence and issue #211 acceptance

**The bounded native follow-up passed all 54 observations and all three real-consumer candidate contracts on the same immutable source. Recommend closing [#211](https://github.com/carstenartur/jgit-storage-hibernate/issues/211) after this evidence and its documentation are integrated.** The two remaining workload gaps—concurrent Taxonomy workspace publication and native Audio WAL evidence—now have retained measurements. No production API, schema or default was changed for this study.

The [original issue text](original-issue.json), [capture manifest](manifest.json), [detailed audit](qualified-evidence.json) and [normalized observations](observations.csv) retain the exact scope. All raw benchmark JSON, native telemetry, Maven logs, JUnit reports and 21 Java harness source files per native cell remain inside the original ZIP archives. Historical experiments keep their original source and measurement conditions.

## Immutable source and completed runs

| Role | Library commit | Harness commit |
| --- | --- | --- |
| Release, v0.12.0 | `ba6aeecc338968df4279fb9c7c72b15bbb3577fb` | `5ed8aad0f0691bcecb414b319235b67dc0520581` |
| Main after PR #463 | `1c33640bc75206e81a9bc5722768860a2a2321f5` | `5ed8aad0f0691bcecb414b319235b67dc0520581` |
| Proposed | `5ed8aad0f0691bcecb414b319235b67dc0520581` | The same commit |

The published source tree is `8a6035b7563d2901bf8d34f62f2c879bc1cb0601`. The local evidence branch starts from `f293b74420fdf23069449d3cdce324d38d1b3338`, whose complete tree is identical; neither that local commit ID nor this later documentation commit replaces the measured source ID.

| Workflow | Run | Result and completion (UTC, 2026-11-08) |
| --- | --- | --- |
| Native consumer publication | [42431132637](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42431132637) | Six matrix jobs and the cross-variant comparison succeeded; 00:47:45 |
| Real consumer compatibility | [42431132838](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42431132838) | Nine jobs succeeded, including three baselines, three candidates and the summary; 00:53:05 |

Both workflows ran on the named branch push to `codex/performance-followup-20261108`, attempt 1. The workflows were not rerun and the source branch was not moved during measurement. They did not use a synthetic pull-request merge. Manual dispatch remains available for the same bounded mode.

The native artifacts record the approved release/Main pins and byte-identical benchmark overlays. All 21 archived Java source files in every cell were checked against the published source tree at capture, and their per-file hashes remain retained. GitHub's commit/tree responses independently match the recorded Core, Search and Java Analysis module trees. Main and Proposed have identical production modules:

| Module | Main and Proposed tree |
| --- | --- |
| Core | `af765a6f84a62e91421990f3108d7e381575c034` |
| Search | `10b83ab9e40865caadd1c48ec8f07d39d087fa58` |
| Java Analysis | `30608349d417d48d71d03c29e663a6b702a466c9` |

Consequently their timing differences cannot establish a new library implementation gain. The release has separately recorded module trees. The [raw comparison](archives/consumer-publication-comparison.zip) preserves that distinction.

## Matrix and correctness observations

Each role used separate hosted PostgreSQL 17.10 jobs at configured 0 and 20 ms RTT. Every coordinate has one warmup followed by three measurements in one fork, with a 1 GiB heap. The 0-ms job measures both Taxonomy worker counts and both Audio sizes; the 20-ms job measures Taxonomy only. This is 18 coordinates: 36 Taxonomy observations plus 18 Audio observations. The archived native JUnit reports contain 72 successful test executions, with no failures, errors or skips.

| Role | Calibrated 0-ms median | Calibrated 20-ms median |
| --- | ---: | ---: |
| Release | 0.125 ms | 20.850 ms |
| Main | 0.318 ms | 20.611 ms |
| Proposed | 0.172 ms | 20.623 ms |

The direct PostgreSQL Docker service recorded actual `SHOW fsync`, `SHOW synchronous_commit` and `SHOW full_page_writes`, all `on`. The measured Hibernate provider independently checked the three values before every iteration, outside its timer and telemetry window. Every retained native observation records the same values. This is engine-reported configuration, not a hardware durability test.

### Taxonomy: identical deltas with one or four workers

Four independent workspaces start with 250 reachable commits each. One invocation publishes the same deterministic terminology delta to each workspace and indexes its four new commits. Across all 36 observations, the per-invocation counters are exactly **four publications, four indexed commits, 40 prepared statements, 12 ORM transactions and four JDBC batch executions**. Changing worker count does not reduce the work being verified.

Warmup is publication 1; measurements are publications 2, 3 and 4. Complete Git, SQL and Lucene membership therefore agrees at **1,008, 1,012 and 1,016 commits**. Before and after provider/index reopen, the oracle verifies the complete reachable Git set, refs and symbolic HEAD, reflog transitions, delta blob, full SQL/Lucene ID lists, new projection contents, author/committer identities and times, and the compound query results. The final comparison finds identical membership hashes and ordered refs across every library, worker and RTT variant.

These are observed medians in milliseconds, each from three retained samples:

| Library | 0 ms, one worker | 0 ms, four workers | 20 ms, one worker | 20 ms, four workers |
| --- | ---: | ---: | ---: | ---: |
| Release | 91.876 | 69.791 | 1,171.322 | 350.248 |
| Main | 116.217 | 107.898 | 1,149.555 | 342.861 |
| Proposed | 112.167 | 70.690 | 1,314.425 | 513.538 |

**Operational decision:** use a small bounded workspace pool when these independent publications face material database RTT. Four workers overlapped the same work at 20 ms in this experiment; increasing batch sizes or changing a global writer default is not required to obtain that overlap. Keep one owner per workspace publication and preserve the explicit limit. The result does not establish an optimal worker count for arbitrary connection pools, same-repository contention or larger histories. Each workspace commits independently; this is neither a four-workspace transaction nor an atomic Git-plus-Search commit.

WAL insert-LSN deltas were 23,824–24,008 bytes in 35 observations. One Proposed/20-ms/one-worker observation recorded **403,992 bytes**. It is retained unchanged. A cumulative instance counter does not attribute this difference to a particular publication or establish a universal WAL upper bound.

### Audio: native WAL with bounded analysis pressure

Every Audio invocation publishes one incompressible 2- or 16-MiB artifact and its metadata, advances both refs atomically, and retains a 32-MiB synthetic analysis working set with one CPU worker. Search is disabled. All observations verify complete payload hashing, metadata bytes, branch histories and reftable reflogs, then repeat verification after reopening. Git histories contain 6, 8 and 10 commits after the measured invocations; SQL and Lucene projection counts remain zero.

| Logical artifact | Prepared statements | ORM transactions | JDBC batches | Database payload bytes | Observed WAL insert-LSN bytes |
| --- | ---: | ---: | ---: | ---: | ---: |
| 2 MiB | 10 | 3 | 1 | 2,100,173–2,100,293 | 2,252,840–2,254,416 |
| 16 MiB | 13 | 3 | 2 | 16,784,714–16,784,835 | 17,973,480–17,975,040 |

Every role has the same structural counters and positive native WAL deltas. The irreducible logical input is the complete payload; Git/metadata framing and database/ref bookkeeping add work. Chunk batches must cover the actual pack chunks, including framing. These instance WAL observations are not transaction-attributed physical write amplification.

**Operational decision:** retain the automatic writer and existing physical chunk format for these sizes; this evidence supplies no structural reason to change them. For a constrained concurrent consumer, the separately retained [Audio chunk-batch experiment](../consumer-audio-concurrency-2026-11-07/README.md) supports an explicit batch-8 budget across four repositories: twelve JDBC batches at a configured 32-MiB aggregate chunk budget. The larger budget alternatives trade fewer batches for more retained chunk capacity. The new native pair adds the missing Audio-specific database-log evidence; it does not relabel that historical experiment or claim a new cross-release speedup.

## Exact-source real-consumer qualification

The candidate build log records checkout `5ed8aad0f0691bcecb414b319235b67dc0520581`. It uploaded Maven artifact **13597995184**, SHA-256 **`5bcd755e2e345b48c6a95c82f783e0135b5cd75f9ee78c624753fe12906940eb`**. Each candidate log records the same library/tooling checkout and downloads that exact artifact ID with a matching digest. The full 67-MB ZIP was independently downloaded and hashed at capture; its bytes are not duplicated in this documentation directory. The compressed build/download logs, all contract result archives and the recorded digest preserve the qualification chain.

| Consumer | Pinned consumer commit | Candidate modules | Baseline / candidate tests |
| --- | --- | --- | ---: |
| audio-analyzer | `18ca14dda69a51ac83fa8fad0bd22071778fcab0` | Core, Search | 1,096 / 1,096 |
| Taxonomy | `098aa054ad2dd9f8e11fbabbc48a1fc7631e13c3` | Core, Smart HTTP | 41 / 41 |
| sandbox | `26391b1c8e4d0324ae3669c7febf45f048e8bccc` | Core, Search, Java Analysis | 210 / 210 |

Both modes total **1,347 tests**, with zero failures, errors or skips. Candidate dependency trees resolve exactly the declared modules to `0.12.1-SNAPSHOT`; only POM version selections changed. Consumer baselines resolve `0.11.8`. That baseline is distinct from the native benchmark's v0.12.0 release comparison. The [archived consumer summary](archives/consumer-compatibility-summary.zip) was reproduced byte-for-byte from the retained results.

The manifest's original pending-compatibility text is preserved inside each raw native archive. This subsequent audited source/build/download/contract chain resolves that publication gate for this measurement source. It does not turn the synthetic fixtures into measurements of the consuming applications.

## Original acceptance criteria

| Criterion from #211 | Concrete evidence and disposition |
| --- | --- |
| All three deterministic fixture generators are committed and tested | [Generators](../../../jgit-storage-hibernate-benchmarks/src/main/java/io/github/carstenartur/jgit/storage/hibernate/benchmark/ConsumerBenchmarkFixtures.java) and [generator tests](../../../jgit-storage-hibernate-benchmarks/src/test/java/io/github/carstenartur/jgit/storage/hibernate/benchmark/ConsumerBenchmarkFixturesTest.java) cover Audio, Taxonomy and sandbox. The retained generator log has seven passing tests. Native publication lifecycle/worker/delta tests and the earlier all-profile smoke add public-API verification. **Met.** |
| Each profile has a bounded smoke matrix and a larger scheduled/manual matrix | [Consumer workflow](../../../.github/workflows/consumer-profile-benchmarks.yml) provides smoke, large, Audio concurrency and sandbox semantic modes. [PostgreSQL workflow](../../../.github/workflows/consumer-taxonomy-postgres.yml) provides smoke, 1k/10k/100k history modes and the bounded publication follow-up. The criterion requires these matrices to exist; it does not require every Cartesian combination to run in one acceptance pass. **Met.** |
| Dashboard separates generic and consumer-shaped fixtures | The stable [`consumer-shaped-evidence` anchor](../../../.github/benchmark-dashboard/index.html) leads to the dedicated [consumer evidence index](../consumer-shaped-benchmarks-2026-11-07.md). Manifests tag fixture kind/consumer relevance and the consumer workflows keep their data out of generic history. **Met.** |
| Consumer contracts pass on the commit whose benchmark data is published | The new 54 observations and all three candidate contracts use exact source `5ed8aad0`. The separately retained [PR-463 audit](previous-integrated/consumer-211-final-ci-review.md) qualifies 13 all-profile smoke observations, including both sandbox variants, plus ten native history/recovery observations on exact synthetic merge `a528d951`. Each source has its own successful three-consumer build/download/contract chain. **Met for these qualified series; historical unpaired measurements are not relabelled.** |
| Documentation states where results generalize | [Profile documentation](../../benchmark-consumer-profiles.md), raw manifests and this report delimit synthetic data, current-snapshot versus historical work, known-edit discovery, database/cache state, RTT, native telemetry, GC scope and worker limits. **Met.** |
| Each profile yields a real optimization decision | **Audio:** use the measured chunk-budget/batch tradeoff and retain writer defaults where no structural gain appears. **Taxonomy:** choose summary projection when hydration is unnecessary, retain bounded recovery batches and use bounded independent-workspace overlap where RTT matters. **Sandbox:** replace one proven dependency-free file projection instead of 251/1,001 files, preserving the full-rebuild oracle. These decisions are documented in the [evidence index](../consumer-shaped-benchmarks-2026-11-07.md) and its raw experiments. **Met.** |

The original release/current comparison covers all three original profile smokes with an identical harness. This follow-up supplies matched release/Main/Proposed native publication comparisons, with production-tree equality explicit. No new production implementation is concealed behind a changed benchmark harness. The larger historical Audio and sandbox experiments are not claimed to have a fresh release baseline for every added operation, nor a new same-commit timing qualification. Such matched operation-by-operation studies are needed before making those additional performance claims; they are not an extra full-matrix acceptance condition.

**Closure recommendation:** the stated fixture, workflow, dashboard, source-qualification, documentation and operational-decision criteria are satisfied. No unimplemented required fixture shape or unverified new publication cell remains in this scoped completion. Keep larger 10k/100k executions, higher pressure/worker combinations, alternative physical chunk layouts, production-capacity tuning and general cross-file invalidation as future research. The issue did not require a supported general incremental indexer, a universal winning default or a complete optional cross-product.

## Historical boundary and replay

The retained [PR-463 evidence](previous-integrated/consumer-211-final-ci-review.json) keeps executed source `a528d95101547b81c484b33bd7f3c54c22335512` and full tree `974c3c0589f0067f0135dbd65e3e6f205591902b`. Its thirteen original archives and compact producer/consumer provenance logs are now durable beside this report. Those 23 single-shot observations are separate from the 54 new publication observations. No new durability settings are attributed retrospectively to the older native run.

The earlier 72-observation Audio experiment remains on `30f569e`; the 24-observation persisted sandbox experiment remains on `92c4f99`; the first PostgreSQL experiment remains on `23a5f6a`. Their source snapshots, cache differences and limitations remain in their original evidence folders. None becomes a measurement of `5ed8aad0` through a later successful CI run.

All 15 new artifact archive digests were checked at capture. Fourteen original archives are retained here, plus all thirteen archives from the earlier qualified integrated series. [manifest.json](manifest.json) records IDs, run IDs, archive sizes, original SHA-256 digests, source hashes and retained files. The large candidate Maven binary is the only new archive omitted from Git; offline replay verifies its producer/download chain but does not pretend to download it again.

```bash
python3 docs/evidence/consumer-publication-2026-11-08/verify.py
```

The verifier checks retained hashes, source/role identity, identical harness snapshots, complete native matrices and Git-result equality, actual durability and RTT records, JUnit totals, consumer dependency versions, candidate artifact identity, both archived summaries and the previous source's 23-observation qualification. It only reads evidence and uses a temporary extraction directory; it does not run Maven, JMH, Docker or any workload.

JMH GC counters cover whole iterations, including verification and provider reopen, so they are not per-publication allocation measurements or heap high-water marks. Reopening does not flush OS or database caches. Lucene sizes are live, non-atomic filesystem gauges excluding SQL/Git storage. Three observations per coordinate in one fork do not establish stable deployment latency rankings. The unfavourable Proposed Audio timing and the Taxonomy WAL outlier remain in [all raw coordinates](derived-native-coordinates.json); no result was discarded to select a winner.
