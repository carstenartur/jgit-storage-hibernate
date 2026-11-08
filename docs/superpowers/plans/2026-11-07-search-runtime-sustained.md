# Search Runtime Sustained Diagnostic Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete issue #196's sustained/overload and direct Lucene-activity evidence with a bounded, reproducible benchmark-only diagnostic.

**Architecture:** A separate diagnostic runner uses production indexing/search paths and persistent storage. It writes bounded raw records and captures a separate InfoStream pass; a Python validator derives events and verifies completion. The existing runtime workflow gains a manual diagnostic mode and preserves its current JMH jobs.

**Tech Stack:** Java 21+, JUnit 5, Hibernate Search 8.4/Lucene 9.12.3, file HSQLDB for focused tests, hosted PostgreSQL 17.10, Python standard library.

**Spec:** `docs/superpowers/specs/2026-11-07-search-runtime-sustained-design.md`.

## Global constraints

- Benchmark/test/documentation/workflow changes only; no production defaults, APIs or existing JMH operation identities change.
- Isolated worktree `issue196-sustained` based on `495653b`; root's integration worktree remains untouched.
- No repeated full settings matrix or local parallel performance study. Root publishes and dispatches after review.
- Bound workers, queue, fixture and log size, phase duration, drain and join waits.
- Preserve raw measurement scope and exact source; trace and untraced observations remain separate.

## Review focus

- A producer/query failure before reaching the gate must release and join all workers before provider close.
- Interruptions must not bypass cleanup or make a background failure look successful.
- NRT reader-open work and segments-file publication must not be called successful refreshes or durable acknowledgements.
- Event parsing must not count no-op commits, aborted merges, setup or graceful-close work as sustained activity.
- Missing/truncated/nonmonotonic raw data or incomplete drain/reopen evidence must fail validation.

## Task 1: Raw record and activity validation

**Files:** `.github/scripts/summarize-search-runtime-diagnostic.py`, `.github/scripts/test_summarize_search_runtime_diagnostic.py`.

**Interfaces:** Read an evidence directory containing metadata, phase/time-series/query/transaction CSVs, trace CSV and completion properties. Produce a deterministic `summary.json`, event CSV and a small Markdown report in an explicit output directory. The schema and pinned event signatures are shared with Task 2.

- [x] Write focused failing parser/validator tests for event semantics, duplicate/no-op/aborted events, malformed sequence, absent completion and improper trace/timing mixing.
- [x] Run the Python tests and retain RED evidence for the missing behavior.
- [x] Implement the minimal parser/exporter with finite numeric validation and strict completion/membership/resource checks.
- [x] Run the Python tests to GREEN and retain the command/output.

## Task 2: Bounded runner and lifecycle

**Files:** benchmark test package `SearchRuntimeSustainedDiagnostic.java`, `SearchRuntimeSustainedDiagnosticTest.java`, `SearchRuntimeSustainedDiagnosticIT.java`; helper classes only where they have a separate responsibility.

**Interfaces:** `run(Properties connection, Path index, Path output, Options options)` owns each invocation. `Options` specifies scenario, resource label, traced/untraced pass and bounded counts/durations. The runner emits Task 1's raw schema and completes only after drained membership, persistent reopen, worker and handler checks.

- [x] Add failing small lifecycle tests for gate release/join ordering, original failure before gate entry, and interruption coverage.
- [x] Implement bounded worker ownership, output gate and deterministic CSV/InfoStream capture; preserve original failures.
- [x] Add the real small HSQLDB/Lucene correctness runs, including traced and untraced passes, verifying repeated transactions, actual gate entry, post-release progress, exact membership and reopen.
- [x] Run focused tests to GREEN under the local JDK 25/Maven setup; do not repeat the full reactor tests.
- [x] Run the Python validator on the tiny correctness artifacts to exercise the Java/Python boundary.

## Task 3: Hosted mode, guide and review handoff

**Files:** `.github/workflows/search-runtime-performance.yml`, `docs/operations/search-runtime-tuning.md`, and this plan's progress entries.

- [x] Add an explicit `matrix`/`sustained` manual mode to the registered workflow. Keep ordinary PR/push/scheduled matrix behavior unchanged.
- [ ] Run four bounded native coordinates (two modes × two resource budgets), each with distinct timing and trace passes, on dedicated hosted jobs after root dispatches. Record exact source, JVM, DB, affinity, heap and trace settings.
- [x] Wire validation/export and the existing privacy check before upload; a skipped/absent diagnostic cannot count as successful evidence. Apply the same checks to local correctness evidence.
- [x] Document precise metrics, phase boundaries, interference from tracing and overload injection, and the minimal reproduction command.
- [x] Verify focused Java/Python results, diff whitespace and workflow structure. Prepare the reviewable commit and root handoff with the plan, test logs and unresolved native-evidence gate. Root owns publication/native execution.

## Execution ledger

- Design and plan authorized by root; implementation starts from the already green final integration tree. Its full test matrix is not repeated.
- Task 1 and Task 2 share the explicit raw schema; the real fixture artifact must pass the same parser that validates hosted evidence.
- No hosted measurements have yet been run for this diagnostic.
- The parser and evidence validator have 15 focused tests, including RED-to-GREEN checks for phase relabelling, absent activity being mistaken for zero, and missing/disabled observed PostgreSQL durability settings. Logs are retained outside the checkout under `issue196-sustained-*-red.log` and `issue196-sustained-validator-final-green.log`.
- Three worker lifecycle tests reproduced the original failures before implementation. Three real persistent HSQLDB/Lucene fixtures cover reference tracing, asynchronous tracing and asynchronous untraced execution. Each verifies 68 Git/SQL/Lucene identities, 24 incremental transactions, release/drain, persistent reopen and no live application workers.
- The real fixture exposed two pinned-runtime details: JBoss Logging formats `logf` before delivering JUL records, and an explicit `io.writer.infostream=false` maps to a Lucene-rejected null. Capture parses the actual formatted prefix; the untraced pass omits the property and uses the default disabled stream.
- Root's review required valid Git trees and explicit database durability. Generated tree objects now pass JGit's `ObjectChecker`. The new native container enables `fsync`, `synchronous_commit` and `full_page_writes`; actual `SHOW` values are recorded and checked by both Java and the exporter. Historical measurements are unchanged.
- The gate alone is not treated as queue saturation: asynchronous runs must also retain a producer stack blocked on queue submission while output is gated. Counts, sampled heap and stale visibility observations remain separate from internal queue depth or instantaneous peak claims.
- Native execution remains a root-owned follow-up. The four coordinate jobs, each with fresh timing and trace passes, must finish with non-skipped PostgreSQL evidence before the remaining issue #196 criteria can be declared complete.
- Final local verification: six Java tests, 15 Python tests and all three actual artifact replays pass. The existing privacy gate passes on raw/derived files, Maven logs and JUnit reports. YAML parses, all shell steps pass `bash -n`, and the diff passes whitespace checks. These are correctness checks, not deployment performance measurements.
