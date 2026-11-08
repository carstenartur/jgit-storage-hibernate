# Consumer CI provenance review — PR #463

**All monitored jobs succeeded. The 23 fresh observations and all three candidate contracts use the identical library checkout.**

- Published head: `ae54925c02b186162c15c23d155ffef6cfa663b5`.
- Base: `c33cc13212a8397e5dc045ae1ea0675d50e7df31`.
- Executed PR merge: `a528d95101547b81c484b33bd7f3c54c22335512`.
- Full tree: `974c3c0589f0067f0135dbd65e3e6f205591902b`; identical to the published head tree.
- Run attempt: **1** for every run below.

| Workflow | Run | Result | Retained measurements |
|---|---|---|---|
| Consumer-shaped Benchmarks | [42425979945](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42425979945) | 4/4 jobs succeeded | Audio 4; Taxonomy 3; Sandbox analysis 2; Sandbox semantic projection 4 |
| Taxonomy PostgreSQL | [42425979894](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42425979894) | 2/2 jobs succeeded | 5 observations each at requested 0/20 ms RTT |
| Real Consumer Compatibility | [42425979963](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42425979963) | 9/9 jobs succeeded, including summary | Three baseline/candidate pairs |

## Consumer-owned contracts

| Consumer | Pinned consumer commit | Baseline tests | Candidate tests | Failures / errors / skips |
|---|---|---:|---:|---|
| audio-analyzer | `18ca14dda69a` | 1096 | 1096 | 0 / 0 / 0 |
| taxonomy | `098aa054ad2d` | 41 | 41 | 0 / 0 / 0 |
| sandbox | `26391b1c8e4d` | 210 | 210 | 0 / 0 / 0 |

All candidate dependency trees resolve the descriptor's modules to **0.12.1-SNAPSHOT**; baselines use **0.11.8**. The same-run candidate artifact is **13595750785**, SHA-256 **de851848eae11a3ec70a799313f0455f97c5c89e3d2d63184d3d727922308d82**. Its build log records checkout `a528d95101547b81c484b33bd7f3c54c22335512`. Every candidate job downloaded that exact artifact ID and validated the same digest. This candidate binary ZIP was verified through the producer/consumer job logs rather than downloaded locally.

## Raw evidence verification

All **13** benchmark, contract and summary ZIP archives were downloaded and their SHA-256 digests independently compared with GitHub's artifact metadata. The JSON note records every artifact ID, run, digest and local filename. Embedded file hashes in the native and semantic manifests also match. Consumer source commits match the pinned descriptor; duplicated contract result files agree; archived JUnit suites report zero failures/errors/skips.

Native cells each retain six successful lifecycle tests, matching JMH/telemetry records, and recovery **100 → 50 → 50 after reopen → 100**, with **58 Git-verified hits**. RTT probe medians are **0.371 ms / 20.649 ms**. These are single-shot smokes: one fork, zero warmup and one observation per coordinate. They establish current functionality and source pairing, not a stable latency ranking.

## Qualification boundary

Issue #211's same-commit requirement is satisfied for these **23 freshly published observations**: their manifests name the exact checkout used to build the successful real-consumer candidate. Historical Audio `30f569e` and Sandbox `92c4f99` observations keep their original provenance. The original PostgreSQL measurement `23a5f6a` and its historical contract checkout `e463feef` have different commit IDs but the identical complete tree `9f3ae505`; this distinction does not relabel the measurements.

Full machine-readable detail: [consumer-211-final-ci-review.json](consumer-211-final-ci-review.json). GitHub summary artifact: **13596085914**.
