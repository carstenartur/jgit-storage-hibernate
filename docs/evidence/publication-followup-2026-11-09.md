# Native publication follow-up — 9 November 2026

The new runs extend the evidence for [staging overlap (#189)](https://github.com/carstenartur/jgit-storage-hibernate/issues/189)
and [adaptive publication (#161)](https://github.com/carstenartur/jgit-storage-hibernate/issues/161).
Both issues remain open. Production keeps the synchronous staging writer and the
deterministic one-MiB publication policy. Each study below retains its own source,
protocol and failed attempts; the earlier studies keep their original conclusions.

## Complete Git publication (#189)

[Merged PR #474](https://github.com/carstenartur/jgit-storage-hibernate/pull/474)
adds a benchmark-only transfer seam and twelve direct/pre-persisted publication
and rollback contracts. The measured source is
[`2b779f7c246a56c49e755deb0bab6e644da329a4`](https://github.com/carstenartur/jgit-storage-hibernate/commit/2b779f7c246a56c49e755deb0bab6e644da329a4);
[merge `f9b0693d`](https://github.com/carstenartur/jgit-storage-hibernate/commit/f9b0693d6fcfbf9407b17c0727923f44ed23644a)
has the same tree. Its supported database/JGit checks passed, including the
post-merge checks.

[Native coverage run 42564627223](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42564627223)
completed eight raw-transfer coordinates, retaining 72 transfer and 144
single-writer rollback samples. It also retained **18 complete Git publication
measurements** across two single-writer controls: 128 MiB + 257 bytes, a 16 MiB
window, one warm-up and three measured rounds per mode. This remains eight of the
216 raw-transfer coordinates; the complete Git controls cover only the two
conditions below.

The interval starts before object insertion and ends after the ref update commits.
It includes blob/tree/commit creation, compression, PACK/INDEX staging and the
current publication policy. Fixture preparation, protocol negotiation and cold
verification are outside it. Every retained publication passed cold byte/hash and
ref/tree/index verification, selected pre-persistence, and recorded positive WAL
movement. Synchronous mode retained one payload window; both reader modes retained
two. See the [measurement and reproduction contract](../operations/staging-pipeline-evidence.md#complete-git-publication-controls).

| Single-writer control | Observed RTT | Synchronous median | Virtual median | Platform median |
| --- | ---: | ---: | ---: | ---: |
| Runner staging, no added RTT | 0.156 ms | 5,308.988 ms | 5,898.548 ms (+11.10%) | 5,703.693 ms (+7.43%) |
| Reads paced at 150 MiB/s, 50 ms added RTT | 50.839 ms | 6,965.996 ms | 6,129.460 ms (−12.01%) | 6,102.175 ms (−12.40%) |

The percentages compare each mode's median with the synchronous median. Both
reader modes were faster in all three paced rounds, but these are observational
results from one run. Local variability establishes neither a repeatable regression
nor non-regression. The missing local non-regression evidence and remaining matrix
coordinates keep #189 open; production stays synchronous.

The run's `staging-pipeline-p128-b16-w1-runner-r0` and
`staging-pipeline-p128-b16-w1-paced-r50` artifacts contain `git-publication.csv`,
`git-publication.properties`, native telemetry and cold-verification reports.
Their artifact IDs are `13650338645` and `13651163621`; downloaded ZIP hashes matched
GitHub's recorded digests. The [smoke run](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42564465984)
is a separate check and is excluded from the 18 retained measurements above.

## JGit initial-buffer candidates (#161)

Two personal-fork drafts preserve logical block boundaries while reducing initial
allocation: [Reftable PR #20](https://github.com/carstenartur/jgit/pull/20), head
[`d1059d39645dcc16ce82cb13bb12b78b432423e2`](https://github.com/carstenartur/jgit/commit/d1059d39645dcc16ce82cb13bb12b78b432423e2),
and [PACK PR #21](https://github.com/carstenartur/jgit/pull/21), head
[`5f1c52f249be4f45c29101eb2371e766d74c91fd`](https://github.com/carstenartur/jgit/commit/5f1c52f249be4f45c29101eb2371e766d74c91fd).
The immutable [combined candidate `5b65cb64`](https://github.com/carstenartur/jgit/commit/5b65cb64ec528344b459f43bf451eba11bdbd605)
contains exactly those changes. Its focused joint gate passed **221 DFS/Reftable
tests with zero failures, errors or skips**. Larger first blocks can incur extra
growth allocations and copies; the patches do not establish general peak-memory
or throughput improvements.

The **full JGit core suite is not certified green**. The unchanged baseline
reproduced a GC-pruning assertion failure. The final two-line create-only concurrent
ref fixture passed its 37-test class, but no final full-core rerun was performed.
An exploratory cached-ref read failure also remains unresolved. PR #20 records
these failed runs and their source distinctions. Both changes remain personal-fork
drafts; no upstream submission or dependency release has been made.

The [combined native run 42574284117](https://github.com/carstenartur/jgit/actions/runs/42574284117)
uses a separately frozen [harness `8bd23c8e`](https://github.com/carstenartur/jgit/commit/8bd23c8ecb846dba9bce001825d3c67f4ad29533).
It compares official JGit 7.8.1
[`2784b2cb0164726a2923848dc9c8087925d51b97`](https://github.com/carstenartur/jgit/commit/2784b2cb0164726a2923848dc9c8087925d51b97)
with the combined candidate. Both use benchmark/profiler
[`e08ca28e0373b799e558f7670b3648c420020459`](https://github.com/carstenartur/jgit-storage-hibernate/commit/e08ca28e0373b799e558f7670b3648c420020459)
and original `jgit-storage-hibernate-core`
[`96f1b08d5d0ca32f98508828c78074f12da688fc`](https://github.com/carstenartur/jgit-storage-hibernate/commit/96f1b08d5d0ca32f98508828c78074f12da688fc),
including its unchanged one-MiB alignment. PostgreSQL 17.10, HikariCP, stateful
writes, the size policy, 16 independent writers, 256 KiB + 257-byte payloads,
zero added RTT, Temurin 21.0.13+9 and a 1 GiB maximum heap are fixed. The eight
attempts use the preregistered order B,C,C,B,B,C,C,B with no retries. Actual child
classpath, class origins and JAR hashes confirm that only JGit differs.

All four candidate forks completed without an OOM exception or GCLocker warning.
Three baseline forks completed; baseline attempt five failed allocating the
`DfsInserter.PackStream` buffer. Thus only **three of four pairs are complete**.
The protocol requires all four pairs for an overall effect and 95% interval, so
both fields remain **null**. The retained baseline failure correctly makes the
workflow red. The three complete pairs show allocation reductions of 34.41%,
26.91% and 31.43%, with mixed throughput changes of −9.91%, +9.48% and +0.047%.
These are descriptive observations; they do not prove OOM elimination, throughput
improvement, independent-repository non-regression or adaptive-policy acceptance.

The run retains artifact `13654349326`, named
`jgit-both-initial-buffers-7.8.1-four-pairs`, with raw JMH JSON, logs, all eight
outcomes, source/JAR manifests and the protocol. Its verified ZIP SHA-256 is
`adfd006ac5c5ffe5cce31467063ddb4dc5c9fcebf5e39d2a4095745faba9df58`.

The preceding [PACK-only run 42571543782](https://github.com/carstenartur/jgit/actions/runs/42571543782)
remains separate: two baseline and three candidate forks completed, leaving one
complete pair. Actual OOMs occurred in attempts 1, 5 and 7; its original
`oomDetected` field also matched normal class loading and must not be read as eight
OOM failures. The unchanged artifact `13653304186` and the correction explained in
PR #21 preserve that distinction. Neither new diagnostic replaces the incomplete
[original policy qualification](publication-paired-2026-11-08/README.md), and #161
remains open.
