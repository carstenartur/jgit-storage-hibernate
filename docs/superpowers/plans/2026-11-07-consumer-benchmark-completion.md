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
- [ ] Verify partial/full counts, result membership, refs, unaffected workspace, and cleanup on native PostgreSQL.
- [ ] Retain new lifecycle results and database-native measurements under an exact source manifest.

### Task 3: Calibrated RTT workflow and evidence

Files: `.github/workflows/consumer-taxonomy-postgres.yml`, PostgreSQL benchmark runner/fixture, `docs/benchmark-consumer-profiles.md`.

- [x] Provide smoke and larger manual matrices with local and proxied RTT coordinates.
- [x] Implement SELECT 1 calibration, raw timing/GC/ORM/JDBC/native retention, and explicit cache labels; native execution remains a hosted gate.
- [ ] Run new bounded coordinates using the recovered local PostgreSQL/Toxiproxy binaries.
- [ ] Document operational conclusions supported by retained data and any remaining issue acceptance gaps.
- [ ] Deliver a patch relative to the reviewed PR457 baseline, with exact test commands and evidence paths.

## Execution rulings

- Root explicitly requested continued implementation with no additional design approval.
- Original prepared code and historical measurements are recovered work, not new code to discard for TDD.
- New behavior is verified with a failing assertion before its implementation.

## Verification checkpoint

Historical verifier: 6/6 tests and 46 original observations pass. Focused Java fixtures/lifecycle: 11/11 pass. Generic JMH smoke: all nine coordinates pass on Java 21 forks. Native PostgreSQL/RTT awaits the workflow on `codex/consumer-211-postgres-validation`. The local executor maps only UID/GID 0, rejects non-root ownership and group switching, has no Docker, and denies user-namespace remapping; PostgreSQL cannot be started here without bypassing its non-root requirement.
