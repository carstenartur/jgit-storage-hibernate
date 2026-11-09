# Controlled Reftable Comparison Implementation Plan

> **For agentic workers:** Use `superpowers:executing-plans` in this isolated worktree. Parent reviews the final guards and performs remote publication.

**Goal:** Compare the original and candidate alignment implementations in four
fixed pairs on one host with auditable child library identity.

**Architecture:** Build and retain two immutable core JARs, replace exactly the
core entry in each serial JMH child classpath, verify the loaded JAR/class from
the child, and validate all eight attempts without replacing failures.

**Tech Stack:** Java/Temurin 21.0.13+9, JMH 1.37, PostgreSQL 17.10, Python standard library.

**Spec:** [Controlled comparison](../specs/2026-11-08-reftable-controlled-comparison.md).

## Global constraints and review focus

- Four pairs/eight fresh measurement forks, size policy only, unchanged original
  16-writer/0-ms/256-KiB workload, one-GiB heap and GC/JNI options.
- Both core variants share dependency POMs and all code outside the reviewed
  ReadAhead class family; a source/classpath ambiguity stops measurement.
- Surefire manifest classpaths must expand before substitution; duplicate core
  definitions and shadowing must fail closed.
- Child failures are retained, interruptions remain partial, classpath state is
  restored even when a launch fails, and only four complete pairs get an interval.
- Historical archives and original acceptance budgets remain immutable.

## Task 1: Source, JAR and child classpath identity

**Files:** Add `.github/scripts/prepare-publication-reftable-comparison.py` and
its tests; add test-only `PublicationVariantClasspath.java`,
`PublicationCoreIdentityProfiler.java` and focused tests in the benchmark package.

**Interfaces:** The preparer produces `variants.json` and two core JARs with
source/JAR/class/entry hashes. `PublicationVariantClasspath` replaces exactly
one core entry and runs a checked action with a temporary `java.class.path`.
The identity profiler accepts an encoded expected record and writes actual
child source/JAR/class/classpath identity before the first warmup.

- [x] Write/run failing source/JAR difference, missing/duplicate core entry,
  Surefire-expanded classpath, restoration-on-failure and wrong identity tests.
- [x] Implement strict preparation, scoped classpath substitution and actual
  child checks; run focused Python/Java checks.

## Task 2: Eight-attempt runner and measured validator

**Files:** Add `PackPublicationReftableComparisonIT.java`; extend the existing
finite diagnostic sequence with an explicit immutable coordinate list; add
`.github/scripts/summarize-publication-reftable-comparison.py` and tests.

**Interfaces:** Reuse the paired fixture/options and bounded logging helpers.
Write protocol/environment/variants/trials/outcomes and per-fork raw/identity
records. `summarize(root)` validates exact order, source/classpath/runtime/heap,
all raw evidence and failures. With four complete pairs only, derive the fixed
95% paired log-throughput interval and descriptive allocation ratios.

- [x] Add failing tests for eight-attempt order with retained failures,
  mismatched child class/JAR/classpath, missing identity and incomplete pairs.
- [x] Implement the runner and parser without changing production/workload
  classes or any previous study's stopping rule.
- [x] Verify parser fixtures and the actual Java classpath/sequence helpers:
  394 Python tests and 21 directly compiled Java tests pass. Full runner
  compilation requires uncached Testcontainers/Toxiproxy dependencies and remains
  a mandatory hosted gate before any measurement.

## Task 3: Isolated native execution and archival evidence

**Files:** Extend `.github/workflows/performance-investigations.yml` and the
exact event-selection tests; retain a separate evidence directory after running.

- [x] Test exact branch/profile selection, then add source/JAR build guards,
  pinned runtime, one comparison job and complete/partial artifact upload.
- [x] Run script tests and replay prior archives: both hosted summaries reproduce
  byte-identically, with 609/6 and 271/1 GC/full-GC events. Independent review
  checked the source/classpath/JMH design; its required empty-log fix is included
  separately. A fail-first review check also established the exact generated
  compiler-blackhole argument suffix; both the child and parser now validate it
  without permitting arbitrary extra JVM options. Commit the reviewed source for parent publication. Hosted
  compilation/audits must pass before measurement.
- [ ] Attempt the fixed study once; retain every outcome, source snapshot and
  original artifact/digest. Replay before reporting a merge recommendation.

### Configuration-only launch failure

The first published source `85865ead7c9c4c3fc4671866911a7745c58abf27`
(tree `b3bbe8fabcf8b360bd366a50afd924a1188b3f15`) produced failed run
[`42539933868`](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42539933868),
attempt 1, with **zero jobs and zero measurement forks**. The job-level environment
used `runner.temp`, which GitHub excludes from `jobs.<job_id>.env` but permits in
`jobs.<job_id>.steps.env`; see the official
[context availability table](https://docs.github.com/en/actions/reference/workflows-and-actions/contexts#context-availability).
A fail-first workflow check now rejects that unsupported placement. The two
temporary-directory expressions move to the build/audit steps without changing
source references, measurement arguments, ordering or the eight-fork budget.
