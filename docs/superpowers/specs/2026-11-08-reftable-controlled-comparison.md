# Controlled reftable alignment comparison

## Purpose and interpretation

The two four-fork diagnostics used different hosts. The candidate allocated less
per publication but had lower absolute throughput. Compare the old and candidate
core implementations on one host before a merge recommendation. JGit permits
the old alignment hint; the layout tests do not independently prove a correctness
defect. This experiment addresses one concrete performance risk, not #161's
size-versus-contention policy qualification or general read/compaction coverage.

## Source and launch approach

Use the exact core sources from original diagnostic
`96f1b08d5d0ca32f98508828c78074f12da688fc` and candidate diagnostic
`fcd8eb6ba5fa009f50c20f927fe6e932aca6510b`. Their guarded library sources, dependency
POMs, workload and profiler differ only in `ReadAheadHibernateObjDatabase.java`,
candidate blob `69da64f8bb579d409ed975e338a35b40cf861f3b`. Both use the original
JGit 7.8.0 dependency set, not the later integrated JGit 7.8.1. Record the actual
harness checkout separately from these immutable library references.

Build two core JARs on the same runner/JDK. Compare every entry: class changes
must be limited to `ReadAheadHibernateObjDatabase` and its nested classes. Record
JAR, primary class and entry hashes; permit only separately recorded build
manifest/pom.properties differences outside that class family. Reject missing,
extra, duplicate or unrelated changed class/resource entries before measurement.

The chosen approach scopes `java.class.path` around each serial JMH launch,
using the expanded `surefire.test.class.path` when available. JMH 1.37's
`Runner.addClasspath` reads this property when forming its child command. Replace
exactly one entry containing the core sentinel class, reject wildcard/manifest
ambiguity, retain every dependency path, and restore the original property in
`finally`. This avoids extra controller JVMs or shell rewriting of JVM arguments.

A benchmark-only internal profiler checks the actual child before its first
warmup: unique core resource, CodeSource equal to the selected JAR, exact JAR and
primary-class SHA-256, expected expanded classpath, and JVM identity. It writes
a per-child identity record. A completed fork without that proof is invalid.
The workload and existing publication profiler remain byte-identical. No
production hook or selector option is added.

## Fixed workload, order and failures

- One PostgreSQL 17.10/Toxiproxy fixture and host for the whole run, observed
  durable settings and host-bound non-memory filesystem, Hikari and stateful
  writes. Capture initial/final RTT, source/tree and host/runtime metadata.
- Independent repositories, 16 writers, 256 KiB plus 257 bytes, zero added RTT,
  **size policy only**. Keep two three-second warmups and three five-second
  measurements per fresh JMH JVM, `-Xms256m -Xmx1024m`, Temurin 21.0.13+9-LTS,
  and the same bounded GC/JNI logs for both implementations.
- Exactly four pairs and eight measurement forks: baseline/candidate,
  candidate/baseline, baseline/candidate, candidate/baseline. No extension,
  replacement, retries, selective exclusion or five-cell policy study.
- Ordinary child failure is retained and the remaining predetermined attempts
  continue. Interruption, source/identity mismatch or infrastructure failure
  leaves an explicitly incomplete diagnostic. No interval is reported unless
  all four pairs are complete, validated and on the same source/host fixture.

## Predeclared analysis and decision limits

Use each fresh-JVM pair's candidate/baseline throughput ratio. If all four pairs
complete, report every ratio and their geometric mean with a two-sided 95%
Student-t interval on log ratios (three degrees of freedom; critical value
3.182446305284263). Four pairs can be inconclusive. No additional observations
are added after seeing the result, and this interval does not satisfy the
separate 24/96-pair policy qualification.

Also retain allocation per publication, operation-time sampled heap, full-JVM
GC/JNI observations, lock latency, transaction/selection counters, checkpoint
snapshots and all raw output. Do not hide a throughput loss behind allocation
savings. Full-JVM GC counts and measurement-only counters have different windows.
No automatic merge/default decision follows from a successful workflow. The
parent reviews the paired interval, any failures and the unresolved read/stack
tradeoff; a negative or inconclusive result is preserved without extension.

## Native scope and preservation

Only the exact comparison branch `codex/publication-reftable-paired-20261108`
or explicit `publication-reftable-comparison` profile starts the job. Unrelated
diagnostic/qualification matrices are skipped. Build and audit inputs before
workload, retain both JARs and their entry manifests, child identity proofs,
all eight attempts, original raw JSON/logs and measured parser/source snapshots.
Keep connection properties outside artifacts and run the existing privacy scan.
Root reviews the concrete source/guards and publishes the immutable branch.
