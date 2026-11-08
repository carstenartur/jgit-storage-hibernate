# Consumer Publication Follow-up Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans. Root authorized inline implementation and will arrange independent final review and GitHub publication.

**Goal:** Add bounded concurrent Taxonomy publication and native Audio-WAL evidence for #211.

**Architecture:** Keep old measurement operations intact. Add a separate publication benchmark, deterministic delta helper, isolated database lifecycle and a manual native workflow mode using a pinned comparison overlay.

**Tech Stack:** Java 21, JGit, Hibernate ORM/Search, JMH, HSQLDB functional tests, hosted PostgreSQL 17.10/Toxiproxy, Python evidence tooling.

**Spec:** `docs/superpowers/specs/2026-11-07-consumer-publication-followup-design.md`.

## Global Constraints

- Base `495653b`; no production API/schema/default changes.
- No local performance runs; local tests prove functionality only.
- Root performs GitHub mutations; benchmark and contracts use the same immutable branch source.
- Preserve old operation names, source manifests and comparison revision security checks.

## Review Focus

- Same four deterministic deltas for both worker counts, including ref compare-and-set.
- Worker-thread JDBC metrics must be summed rather than read only on the owner thread.
- Interrupted/failing owner must join workers before closing database resources.
- Native setup traffic and verification must remain outside the measured publication and WAL interval.
- Release overlay includes every helper and records actual library/harness identities without mutable-ref ambiguity.

### Task 1: Deterministic publication and concurrency

Files: `ConsumerBenchmarkFixtures.java`, new `ConsumerPublicationBenchmark.java`, new `ConsumerPublicationFixtureTest.java` in the benchmark module.

- [ ] Add failing real-store tests for exact delta publication, 1/4-worker equivalence, stale-ref failure and interrupted/failing cleanup.
- [ ] Run RED and retain logs.
- [ ] Implement deterministic append helper and separate Taxonomy publication state. Produce ordered new-head IDs, per-worker metrics and verification results.
- [ ] Run new tests plus existing fixture/lifecycle tests; commit the passing slice.

### Task 2: Native Audio and bounded runner

Files: new benchmark-only database helper if warranted, `ConsumerPublicationBenchmark.java`, new `ConsumerPublicationBenchmarkTest.java`, corresponding fixture tests.

- [ ] Add failing functional tests for Audio publication/reopen integrity and selected native configuration.
- [ ] Implement Core-only native Audio state with existing bounded analysis pressure and shared native telemetry.
- [ ] Implement opt-in JMH runner with fixed native coordinates, raw JSON/NDJSON, calibration and complete coverage assertions.
- [ ] Run all benchmark module functional tests without enabling JMH; commit.

### Task 3: Manual comparison, evidence and handoff

Files: existing `consumer-taxonomy-postgres.yml`, `consumer-profile-benchmarks.yml` or shared overlay script as required, new evidence validation script/tests, benchmark documentation.

- [ ] Add explicit manual follow-up mode, immutable release/main comparison inputs and same-harness overlay. Preserve comparison-ref validation and credential boundaries.
- [ ] Test overlay/validator behavior, including missing or mixed source inputs and incomplete metrics.
- [ ] Compile and functionally test the identical harness against v0.12.0; do not execute local performance measurements.
- [ ] Run focused/full relevant verification, commit and request independent review.
- [ ] Hand Root exact commit/tree, workflow inputs and artifact expectations; after hosted dispatch, validate raw evidence and same-source contracts before final conclusions.
