# Execution ledger — 2026-11-07-consumer-publication-followup.md

Baseline: immutable `495653b` tree `974c3c05`, already verified by final integrated Maven/consumer CI in this session. No redundant baseline performance run.

Authorization: Root explicitly requested this implementation and the preceding narrow design. Skill confirmation gates do not require another approval.

Pre-flight: Task 2 consumes Task 1's native store and ordered publication/counter results; Task 3 copies all new harness sources. Keep the existing consumer benchmark operations unchanged to avoid broad manifest changes.

Task 1: implemented. Six genuine RED behavior failures were observed before adding
deterministic append/CAS/reflog handling and the 1/4-worker lifecycle. All six
then passed. Three persistence tests first failed on missing fixtures, then passed
against real HSQLDB/Hibernate repositories, filesystem Lucene, full Git/SQL/index
membership and provider restart. Deliberately deleted SQL projections and incorrect projection content with unchanged object IDs are rejected; the latter also has a dedicated RED→GREEN regression.

Task 2: implemented. Audio uses Core alone, current automatic writer/chunk defaults,
the existing bounded pressure worker, complete metadata/payload/ref verification,
and the atomic update's reftable reflog. Native telemetry ends before verification
and provider restart. No local JMH/performance run was made.

Task 3: implemented, final review pending. The manual `publication-followup` mode
has six dedicated library/RTT jobs and a cross-artifact Git-equivalence gate. Release
is pinned to `ba6aeecc`; published Main to `1c33640b`. The exact same overlay compiled
and passed 16 initial functional tests on the real v0.12.0 checkout; its native runner was
correctly skipped without opt-in. Final verification passed 17 functional tests on current source, and all four strengthened lifecycle tests also passed against the release overlay. Five evidence-gate tests went RED→GREEN, covering
missing/duplicate observations, incorrect individual counters, mismatched worker
results and missing WAL/restart proof. Source and library identities remain separate.

Durability follow-up: direct Docker service explicitly configures `fsync`,
`synchronous_commit` and `full_page_writes` on, retains actual SHOW values, and
checks them again through the measured provider before each iteration. Missing
or disabled values fail both Java and artifact gates (new RED→GREEN cases).
Final current verification passed 18 functions plus one expected opt-in skip;
the same release overlay passed all five final lifecycle/durability tests plus
the expected native-runner skip. Six Python gate tests and YAML/shell/embedded
Python syntax checks pass. Native settings and timings remain unobserved locally.

Review correction: the publication-only upload now requires success of a separate
always-run privacy scan over all raw/Maven/source output and both JUnit directories.
The initial record-time check alone did not gate `always()` upload and omitted
JUnit reports. Real-file leak and symlink tests went RED→GREEN; all eight Python
gate tests now pass. Existing lifecycle upload behavior is separate and unchanged.

Remaining: complete independent review. Root then publishes and starts both native benchmark and
real-consumer workflows on the unchanged branch commit. Retain and inspect all
native artifacts before recording measurement or closure claims.
