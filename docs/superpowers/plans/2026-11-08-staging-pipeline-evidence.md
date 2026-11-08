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
