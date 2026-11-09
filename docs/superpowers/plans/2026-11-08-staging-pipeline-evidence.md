# Bounded staging evidence completion plan

> **For agentic workers:** Use `superpowers:executing-plans` for this scoped continuation of the existing experiment.

**Goal:** Extend the retained experiment with native failure/rollback measurements and a repeatable, bounded way to collect the missing payload, writer, storage and RTT coordinates.

**Architecture:** Keep all pipeline behavior in benchmark test sources. Exercise the existing chunk writer and owner transaction, measure failures after at least one complete writer window, and verify that both parent rows and chunks are absent after rollback. An opt-in workflow selects a small diagnostic or the explicit matrix and retains raw records.

**Tech stack:** Java 21, JUnit, Hibernate, PostgreSQL 17.10, Testcontainers/Toxiproxy, Python standard library and GitHub Actions.

**Spec:** [Issue #189](https://github.com/carstenartur/jgit-storage-hibernate/issues/189), including its current retained-evidence limitations.

## Global constraints

- Production remains synchronous; do not add a runtime feature flag or a core injection seam.
- No more than two payload windows and one outstanding reader task per writer.
- Existing native transfer times remain labeled as staged transfer through commit, excluding Git construction and ref publication.
- Do not infer a full Git publication gain or close the issue from narrower transfer measurements.
- Native CI is required because this workspace has neither Docker nor the current Maven BOMs and cannot resolve their hosts.

## Review focus

- Reader and real JDBC failures after an earlier flushed window must roll back all parent/chunk rows.
- Failure-to-return time includes reader cancellation/join and rollback; JDBC rollback time is recorded separately.
- A configured RTT must be measured; a requested label alone does not qualify a run.
- Tmpfs placement must be checked against the actual fixture filesystem and not inferred from its label.
- Multiwriter failures must not leave background work alive or return before transaction cleanup.

## Tasks

- [x] Add rollback observation to the benchmark helper and contract tests for every reader implementation and both failure directions.
- [x] Extend native evidence with warm-up, measured RTT samples, actual staging placement, temporary bytes and rollback records.
- [x] Add a bounded workflow selector and evidence validator/summary; fail closed on incomplete cells.
- [ ] Run local executable checks, run native CI through the coordinating agent, and retain only verified artifacts.
- [ ] Update the production decision and link the new evidence; explicitly retain any original criteria still unqualified.

## Execution record

The seven Python selector/qualification tests pass, including rejection of missing
modes, incorrect buffer bounds, incomplete rollback directions, leaked chunks,
wrong source identity, missing native WAL movement and inconsistent timeline bytes.
The new workflow parses and all five shell steps pass `bash -n`.

The normal Maven attempt and the initial new Java test attempt stopped during POM
resolution: the current Jackson and Spring Boot BOMs are missing and their hosts
cannot be resolved in this workspace. A supplementary direct Java 21-targeted
compilation of 87 core/experiment/test sources succeeded using cached JGit 7.8.0;
nine existing overlap/cancellation contract invocations passed under Java 25. This
does not qualify the exact JGit 7.8.1 Maven build or execute the new database
rollback assertions. Native CI remains required before integration.

Ruling: keep the core unchanged and the full Git publication gate open. Introducing
a production injection seam only to expand this diagnostic would broaden the
experiment's risk without supplying representative full-publication evidence.

Review fix: suppress connection metadata before provider construction in both
benchmark test classes. An always-run privacy scan now covers the entire output
directory, build/native logs and Surefire reports; upload requires its successful
outcome. The focused regression was observed failing before the scan existed and
now executes the configured scanner against clean output and contaminated build,
native and HSQLDB report fixtures. It also checks that failure, skip and cancellation
outcomes block upload even after an earlier build failure. Nine staging Python
tests and six existing privacy-scanner tests pass, all six workflow shell steps
parse, the supplementary 88-source compilation succeeds, and the nine existing
overlap/cancellation contract invocations still pass. Exact native Maven/rollback
execution remains pending CI.

## Bounded diagnostic continuation

Native run 42534328623 completed its build and embedded contracts but its first
two native cells remained in collection. The one-cell diagnostic preserves the
16 MiB/32 MiB/single-writer/runner/0 ms coordinates and 4 GiB heap, removes warm-up,
and takes one repetition. It adds opt-in phase checkpoints outside measured
intervals, labels output diagnostic-only, and bounds Maven plus descendants in a
fresh process group. Periodic and final JVM thread capture precede finite TERM/KILL
cleanup. Only the exact diagnostic branch or manual dispatch can trigger this
workflow; partial artifacts still require a successful whole-output privacy scan.

Two executable runner regressions cover ordinary failure/output retention and a
TERM-resistant parent/child that must stop at the deadline. A real Java sleeper
also produced retained full JVM thread stacks and a timeout status. In this local
sandbox jcmd attach encountered translated PID values; the SIGQUIT fallback
retained the stacks in native stdout. These checks validate diagnostic plumbing,
not the unresolved native wait. Production behavior and publication thresholds
are unchanged, and the original native run remains independently unqualified.

## Review correction: reader-failure chronology

Prefetch submits the second read before the owner writes the first window. The
injected reader exception previously recorded detection immediately at that read
boundary; the owner could then finish its ordinary first write before noticing
the failed future. A final flushed-row count did not establish detection order.

The rollback probe now signals first-window completion after the existing writer
returns, and injected read failure waits interruptibly for that signal. A first
write failure cancels the wait. Raw rows retain first-flush and failure-detection
elapsed times from a shared monotonic origin; Java assertions and the evidence
validator reject missing or reversed chronology.

The ordering regression failed before the gate because detection returned while
the first JDBC window was active. The validator regression also failed before its
change on reversed, zero and absent chronology. After the correction, 13 pure
transfer/ordering/cancellation contract invocations pass under the supplementary
cached-dependency Java build, including waiting-reader cancellation for both
virtual and platform threads. Ten staging evidence Python tests and six privacy
tests pass. Exact Maven/native execution remains the coordinating CI gate; this
change modifies benchmark diagnostics only and does not establish the cause of
the separate long-running native collection.

## Observed minimal diagnostic and repeated probe

The minimal diagnostic completed successfully in native run 42537024048 at exact
source 586e8088c8d849c9245ac178414156fa0301cda2. Downloaded archive SHA-256 and source
identity were checked, its full content passed the privacy scanner, and the
performance qualifier rejected its diagnostic-only boundary. All phases returned
within the 28.743-second Maven command. Six expected PostgreSQL batch-warning
lines each embedded about two million characters of a binary parameter; this
12.6 MB log volume is retained as a possible console-backpressure factor, not a
proven wait cause.

The separately declared next diagnostic restored the original one warm-up round
and three repetitions with a 240-second native deadline and the same phase/stack
collection. It inherited the reviewed process-group cleanup fix and retained the
original failure probe, keeping the rollback-metric correction outside the wait
investigation.

## Original-round diagnostic and console capture

Run 42537813528 at source bc1adbc5e3d5739d1c5fe5cc0929242a4038c4d9 restored the
original warm-up/repetition counts and completed in 37.844 seconds. All 385 phase
checkpoints returned. Its verified artifact contains 37,821,317 native log bytes,
including eighteen 2,097,420-character PostgreSQL warnings. The full downloaded
artifact passed the privacy scan, source/tree and archive digest matched, and
the diagnostic-only boundary was rejected by the ordinary qualifier. Compact
checkpoints, status, provenance and log-volume metadata are retained beside the
minimal run, without duplicating the large synthetic binary literals.

The normal workflow now retains complete build/native stdout and stderr directly
in the original log files, then emits a valid UTF-8 console tail bounded to 16,384
bytes including its header. Actual workflow-command regressions failed first on
four build/native and exit-zero/exit-seven combinations, then passed with exact
raw bytes and preserved status. A real closed-stdout regression exposed Python's
shutdown changing exit seven to 120; redirecting that failed descriptor prevents
the extra flush failure. All three capture tests pass, and independent review
found no remaining important or critical issue. The existing privacy/upload gate
is unchanged. Console volume is addressed directly; the original stalled stack
location remains unknown and no prior run is relabeled as qualified.
