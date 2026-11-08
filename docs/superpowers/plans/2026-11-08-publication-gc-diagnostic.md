# Publication allocation diagnosis implementation plan

> **For agentic workers:** Use `superpowers:executing-plans` for this bounded diagnostic; retain the failed original study separately.

**Goal:** Distinguish GCLocker allocation starvation from retained-heap pressure in the failed 16-writer, 0-ms publication coordinate without changing its load or qualification budget.

**Architecture:** A separate manual test runner reuses the original paired runner's fixture helpers and JMH options. It attempts exactly four fresh child JVMs, persists each success/failure, and adds bounded GC/JNI logs. A dedicated mode of the existing performance workflow retains all outputs; a strict validator reports diagnostic observations without acceptance statistics.

**Tech Stack:** Java 21.0.13+9, JMH 1.37, PostgreSQL 17.10, Toxiproxy 2.12.0, Python standard library, GitHub Actions.

**Spec:** [Read-only diagnosis and fixed diagnostic design](../../evidence/publication-paired-2026-11-08/oom-diagnosis.md#smallest-diagnostic-only-next-step).

## Global constraints

- Original run 42431132779 and its incomplete fourth pair stay unchanged; no resume or replacement.
- Exactly four fresh JVMs: size, contention-aware, contention-aware, size. No retries or data-dependent extension.
- Preserve the original PostgreSQL/Hikari, 16 writers, 256 KiB + 257 byte payload, stateful writer, 0 ms added RTT, two 3-second warmups, three 5-second measurements, and `-Xms256m -Xmx1024m`.
- Require Temurin HotSpot 21.0.13+9 before starting the workload; add only bounded GC/allocation/JNI logs to child JVM settings.
- A diagnostic can reveal a mechanism; it cannot establish the missing non-regression result or change the production default.
- Keep credentials outside artifacts and scan all retained logs before upload. Do not create heap dumps or JFR recordings.

## Review focus

- A failed child must not prevent the remaining predetermined child attempts, nor disappear from the manifest.
- Interruption/infrastructure abort must remain partial; neither the validator nor workflow may call it a complete diagnostic.
- The new mode must not launch unrelated long benchmark matrices or the original qualification study.
- Diagnostic logs must retain all rotated segments and collector initialization within fixed bounds.
- Unexpected runtime/options/source identities or reordered forks must fail validation.

## Task 1: Diagnostic runner and retained-outcome validation

**Files:** Add test-only `PublicationDiagnosticSequence.java`, `PublicationDiagnosticSequenceTest.java`, `PackPublicationGcDiagnosticIT.java`; expose package-private read-only helper methods in `PackPublicationPairedBenchmarkIT.java`; add `.github/scripts/summarize-publication-gc-diagnostic.py` and its tests.

**Interfaces:** `PublicationDiagnosticSequence.run(ForkAction, Consumer<List<Outcome>>)` attempts the four fixed policies and checkpoints immutable outcomes after each; the diagnostic IT writes `protocol.json`, `environment.json`, `trials.json`, `outcomes.json` and per-fork JMH/GC/JNI/init logs. `summarize(root: Path)` validates complete or partial diagnostic evidence and never derives an acceptance interval.

- [x] Write and run failing tests for continuing after a failed fork, exact finite ordering, retaining interruption, and source/heap/fork validation.
- [x] Implement the sequence, separate runner, and diagnostic parser. Keep the paired protocol and production code unchanged.
- [x] Run focused Python/Java verification and replay the original five-cell evidence to prove it is unchanged. All 367 Python tests and five standalone Java sequence tests pass. Full Maven compilation is blocked locally by uncached Jackson/Spring BOMs and must pass on the hosted runner before any workload.

## Task 2: Isolated native diagnostic and evidence

**Files:** Extend `.github/workflows/performance-investigations.yml` and `.github/scripts/test_native_followup_trigger.py`; create a separately dated diagnostic evidence directory after execution.

**Interfaces:** Profile `publication-gc-diagnostic` and exact branch `codex/publication-gc-diagnostic-20261108` run only the diagnostic job plus workflow contracts. The job publishes one diagnostic-only artifact even when child forks fail.

- [x] Write/run failing event-selection tests, then add the explicit profile, exact branch, pinned runtime, build/validation and bounded upload steps.
- [x] Review and commit the source; root reviewed and published remote `96f1b08d5d0ca32f98508828c78074f12da688fc`, tree-identical to local `1b327b1f`.
- [x] Run the four-fork job once: run `42533525581`, all four completed. Original artifact `13638597050` and measured sources retained in `docs/evidence/publication-gc-diagnostic-2026-11-08/`; hosted summary replays byte-for-byte, with 609 GC events and six full collections. No acceptance result is inferred.
- [ ] Make a correction only if the diagnostic identifies a root cause; any later qualification requires a new protocol and retains the original failure.
