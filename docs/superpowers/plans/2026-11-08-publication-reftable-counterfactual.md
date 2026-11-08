# Bounded reftable allocation counterfactual

**Goal:** Observe whether preserving JGit's reftable block configuration reduces
the allocation pressure seen in the original-runtime diagnostic. This is a
separate descriptive experiment, not a continuation or replacement of the
failed publication qualification.

**Preceding evidence:** The [original diagnostic](../../evidence/publication-gc-diagnostic-2026-11-08/README.md)
completed four forks with repeated humongous allocation and GCLocker collections.
Its full collections reclaimed the heap from roughly one GiB to 83–96 MiB.
The [independently reproduced configuration defect](2026-11-08-reftable-output-alignment.md)
overrode JGit's default 4,096-byte reftable blocks with a one-MiB physical pack
alignment hint. The correction preserves configured/default reftable behavior.

## Fixed source and protocol

- Compare the measured library, dependency POMs and workload against original
  source `5ed8aad0f0691bcecb414b319235b67dc0520581`. The only permitted difference
  is `ReadAheadHibernateObjDatabase.java`, exact reviewed Git blob
  `69da64f8bb579d409ed975e338a35b40cf861f3b`. Reject uncommitted and untracked
  measured source before building; retain the committed source inventory.
- Keep PostgreSQL/Hikari, stateful writes, independent repositories, 16 writers,
  256 KiB + 257 byte payload and zero added RTT. Keep two three-second warmups
  and three five-second measurements per fork, Temurin 21.0.13+9,
  `-Xms256m -Xmx1024m`, and the same bounded GC/JNI logging options.
- Attempt exactly four fresh JVMs in the original predetermined order: size,
  contention-aware, contention-aware, size. Keep failed attempts; do not add
  retries, replace outcomes or extend the budget based on observations.
- Retain the original diagnostic sequence's interruption correction so a
  pre-launch cancellation remains partial without inventing a started trial.
- Set the parent-only protocol variant to `reftable`; child JVM arguments and
  benchmark work remain the same. Record the actual remote source commit/tree,
  complete host/runtime/database environment and every raw result/log.
- Run only the existing diagnostic job on the exact branch
  `codex/publication-reftable-diagnostic-20261108` or explicit manual profile
  `publication-gc-counterfactual`. Other benchmark studies do not run.

## Analysis boundaries

Report completed/failed forks, measurement allocation per operation, GC/JNI
events and full-collection occupancy. Keep full-JVM log counts separate from
JMH measurement-only statistics. Report differences in host, CPU and measured
network latency; the original and corrected diagnostic runs are not paired
randomized host comparisons. Do not attribute differences to the contention
policy when both policies select the direct path, and do not infer a throughput
acceptance interval from four diagnostic forks.

Original run `42431132779` remains incomplete. Its budgets and outcomes are
immutable. A later qualification requires a separately declared current-source
protocol with all five cells and the original stopping rules; it must retain
every outcome and cannot borrow successful forks from either diagnostic.
Neither diagnostic changes the production size-policy default.

## Execution and verification

- [x] Verify real JGit configured/default reftable behavior fails on the old
  implementation and passes after the narrow correction; keep all other
  extension alignment checks.
- [x] Add failing event-selection, protocol-variant and exact-source tests,
  then implement the counterfactual route. Include real Git fixture checks
  that reject uncommitted/untracked measured source and retain a correct
  committed inventory.
- [x] Run focused and complete workflow-script verification: 375 Python tests
  pass, as do 11 directly compiled Java sequence/pairing tests. Replay the
  original diagnostic's hosted summary byte-for-byte (609 GC events, six full
  collections). The hosted build remains mandatory before the new workload.
- [ ] Review and publish the isolated source through the parent agent.
- [ ] Run once, retain the artifact/digest and exact measured sources, replay
  the hosted summary offline, and assess whether a new qualification is
  justified. A failure remains evidence, not a reason to enlarge the heap.
