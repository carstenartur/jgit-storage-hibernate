# Consumer benchmark completion implementation plan

> **For agentic workers:** Use superpowers:executing-plans. Root authorized inline implementation and owns GitHub updates.

**Goal:** Recover and verify the prepared PostgreSQL consumer workload and original raw measurements, then cover provider restart and calibrated database RTT without changing production APIs or defaults.

**Architecture:** Keep the existing deterministic fixture generators and JMH operation names. Add bounded PostgreSQL lifecycle measurements to the test harness, use isolated database schemas and filesystem Lucene indexes, and retain raw data with explicit provenance and environment limits.

**Tech stack:** Java 21 target, JMH 1.37, Hibernate ORM/Search, PostgreSQL 17.10, Toxiproxy 2.12.0, Python evidence validation.

**Spec:** https://github.com/carstenartur/jgit-storage-hibernate/issues/211 and the root task in this session.

## Global constraints

- Preserve existing generic series, consumer operation names and dashboard anchors.
- Synthetic data only; raw JSON/GC/ORM/JDBC/native evidence must substantiate published claims.
- Same-commit consumer compatibility remains a hosted merge gate, controlled by root.
- Separate historical source revisions from current candidate verification; never relabel old samples.
- Preserve prior worktrees and use only this isolated worktree for edits.
- Production APIs and production defaults remain outside this benchmark follow-up.

## Review focus

- Provider close must preserve projections and Git data until the final fixture cleanup.
- Interrupted rebuild recovery must restore all result membership and leave another workspace unchanged.
- Measured RTT must be calibrated and tied to the exact raw JMH coordinates.
- Raw measurement validation must reject missing, changed, or inconsistent evidence.
- Failed setup and benchmark runs must release providers, index directories, and isolated schemas.

## Tasks

### Task 1: Recover historical evidence with verification

Files: `.github/scripts/verify-consumer-benchmark-evidence.py`, `.github/scripts/test_verify_consumer_benchmark_evidence.py`, `docs/evidence/consumer-shaped-2026-11-07/`.

- [x] Add failing tests for corrupt raw input, missing GC metrics, and mismatching normalized counts.
- [x] Retain gzip-compressed original JMH JSON/logs and a provenance manifest.
- [x] Implement a verifier matching original per-iteration structural counters to the existing 46-row CSV.
- [x] Verify the retained original data without rerunning it.

### Task 2: Restore PostgreSQL lifecycle coverage and prove restart

Files: `ConsumerShapeBenchmark.java`, `ConsumerProfileBenchmarkTest.java`, a focused PostgreSQL fixture test if separation improves clarity.

- [x] Import prepared PostgreSQL rebuild/recovery work without overwriting the reviewed comparison workflow.
- [x] Exercise restart after interrupted projection rebuild; first assert the absent capability fails.
- [x] Preserve schema/index through provider close, reopen with validation, and rebuild from authoritative Git.
- [x] Verify partial/full counts, result membership, refs, unaffected workspace, and cleanup on native PostgreSQL.
- [x] Retain new lifecycle results and database-native measurements under an exact source manifest (independent evidence commit `33b153f`, integrated by root).

### Task 3: Calibrated RTT workflow and evidence

Files: `.github/workflows/consumer-taxonomy-postgres.yml`, PostgreSQL benchmark runner/fixture, `docs/benchmark-consumer-profiles.md`.

- [x] Provide smoke and larger manual matrices with local and proxied RTT coordinates.
- [x] Implement SELECT 1 calibration, raw timing/GC/ORM/JDBC/native retention, and explicit cache labels; native execution remains a hosted gate.
- [x] Run new bounded coordinates on the hosted PostgreSQL/Toxiproxy runner; the local executor cannot start PostgreSQL under an unprivileged UID.
- [x] Document operational conclusions supported by retained data and any remaining issue acceptance gaps.
- [x] Deliver immutable commits and a patch relative to the PostgreSQL follow-up, with exact test commands and evidence paths; root owns integration.

## Execution rulings

- Root explicitly requested continued implementation with no additional design approval.
- Original prepared code and historical measurements are recovered work, not new code to discard for TDD.
- New behavior is verified with a failing assertion before its implementation.

## Verification checkpoint

Historical verifier: 6/6 tests and 46 original observations pass. Focused Java fixtures/lifecycle: 11/11 pass. Generic JMH smoke: all nine coordinates pass on Java 21 forks. Native PostgreSQL/RTT passed in hosted run `42419257922` on exact tree-equivalent source `23a5f6a`: five JMH/native observations and six tests per RTT cell, calibrated median 2.194/20.634 ms, recovery 100→50→50 after reopening→100. The local executor maps only UID/GID 0, rejects non-root ownership and group switching, has no Docker, and denies user-namespace remapping; PostgreSQL cannot be started here without bypassing its non-root requirement.

## Continued scope authorized by root

### Task 4: Audio concurrency and bounded writer pressure

- [x] Verify four logical repositories publish and read back complete payloads using independent workers on one provider.
- [x] Retain correct per-worker JDBC batch totals, aggregate payload bytes, and configurable CPU/memory pressure.
- [x] Add bounded smoke/manual matrices for repository count and chunk batch size; link existing physical-layout candidate evidence separately because production chunks remain 1 MiB.

### Task 5: Persisted sandbox projection experiment

- [x] Persist the existing Java Analysis entity mappings into an explicitly benchmark-owned schema and filesystem index.
- [x] Verify a known body edit has the same semantic projection as a complete head snapshot, including Git/blob provenance; also verify rollback, mixed origin commits and provider/database restart.
- [x] Retain runtime, allocation, ORM and index-size evidence under a 1 GiB heap and state the incubation/lifecycle limitations.

## Final local evidence checkpoint

- Audio source `30f569e`: 24 coordinates / 72 observations; archive keeps the original successful one-analysis-worker path. The later cleanup fix `fae3d11` has three demonstrated RED→GREEN regressions and five passing Audio tests. Cache-residency differences are explicit.
- Sandbox source `92c4f99`: eight coordinates / 24 observations, 251/1,001 source files, equivalent full-head facts and one-file versus whole-snapshot transaction counts. Three persistence tests prove mixed-origin provenance, pre-commit rollback, reopen and stale-hit rejection. A later live-directory gauge fix does not relabel these measurements.
- Native PG source `23a5f6a`: root verified all 57 PR checks (45 successful, 12 skipped), including real-consumer contracts, on the same commit as native benchmark run `42419257922`.
- Broader Taxonomy concurrent-publication measurement and release/current/candidate pairs for all new profiles remain explicit issue scope; the combined integration SHA needs its own hosted gates.
