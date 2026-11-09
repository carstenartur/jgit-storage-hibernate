# Hosted ranked-scroll observations: Hibernate Search 8.4.0 and 8.4.1

**Both releases retain growing ranked-hit pages when the requested chunk size
stays fixed. Issue [#230](https://github.com/carstenartur/jgit-storage-hibernate/issues/230)
remains open.** The four successful jobs below establish that the diagnostic ran
correctly; the workflow intentionally makes no bounded-memory acceptance claim.

These are new hosted observations using the standalone reproducer's declared
Maven dependency graph and pinned Temurin 21. They are separate from the earlier
[cached-classpath Java 25 observations](../search-ranked-scroll-retained-2026-11-08/README.md).

## Exact run and source

- [GitHub Actions run 42535522871, attempt 1](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42535522871)
  completed successfully on 2026-11-08 at 23:32:02 UTC. All four matrix jobs passed
  traversal, provenance, native-histogram validation and privacy checks.
- Source commit:
  [`96760803b132e9902c22a3a2ccb0f2ceac0cc1ed`](https://github.com/carstenartur/jgit-storage-hibernate/commit/96760803b132e9902c22a3a2ccb0f2ceac0cc1ed).
- Source tree: `2469214987803a7d941ab361196482793d095258`.
- Workflow: [search-ranked-scroll-memory.yml at that commit](https://github.com/carstenartur/jgit-storage-hibernate/blob/96760803b132e9902c22a3a2ccb0f2ceac0cc1ed/.github/workflows/search-ranked-scroll-memory.yml).
- Trigger: push to `evidence/search-ranked-scroll-20261108`; two jobs at a time.
  There were no reruns or replacement attempts.

Each job built only the independent reproducer POM, resolved its runtime
classpath with Maven, and launched a separate JVM with `-Xms256m -Xmx512m`.
The actual runtime reported **Temurin Java 21.0.13+9-LTS**, ORM **7.4.14.Final**,
Lucene **9.12.3**, and the Search version for its matrix cell. Every cell recorded
**31 runtime JARs**, including Hibernate Models **1.1.1** and JBoss Logging
**3.6.3.Final**. The dependency trees and JAR hashes are retained; no cached local
substitution was used for these runs.

## Results

The requested chunk size was **128** in every cell. The first active sample
contained **512** live `FieldDoc` objects. The sampled growth and subsequent
release were identical between Search 8.4.0.Final and 8.4.1.Final:

| Search | Documents traversed | Sampled midpoint | Live ranked hits at midpoint | FieldDoc shallow bytes there | Live ranked hits after release |
| --- | ---: | ---: | ---: | ---: | ---: |
| 8.4.0.Final | 8,192 | 4,096 rows | 4,096 | 131,072 | 0 |
| 8.4.1.Final | 8,192 | 4,096 rows | 4,096 | 131,072 | 0 |
| 8.4.0.Final | 65,536 | 32,768 rows | 32,768 | 1,048,576 | 0 |
| 8.4.1.Final | 65,536 | 32,768 rows | 32,768 | 1,048,576 | 0 |

In both larger runs, sampled live-hit counts grew through **512, 1,024, 2,048,
4,096, 8,192, 16,384 and 32,768**. Increasing the corpus eightfold increased the
sampled midpoint hit state eightfold while each returned chunk still contained
at most 128 rows. The final nonempty page retained only 512 hits; inspecting only
the end of an export would miss the earlier growth.

All four runs checked every returned object ID, timestamp ordering and ID
tie-breaker, null author projection, and nanosecond `Instant` precision. All
reported zero entity loads and zero prepared SQL statements. Session counts were
33 opened/33 closed for each 8,192-document run and 257/257 for each larger run.
The raw scroll and its final result were released before the final live-object
histogram.

## Retained artifacts and verification

The four files in `archives/` are the **untouched original GitHub artifact ZIPs**,
totaling 2,368,351 bytes. Each downloaded ZIP matched the SHA-256 digest returned
by GitHub's artifact API. The ZIPs contain every full native histogram, the
original logs, CSV, environment information, resolved classpath, dependency tree,
source/JAR provenance and validator result.

| Search / documents | Artifact ID | Original ZIP SHA-256 |
| --- | ---: | --- |
| 8.4.0 / 8,192 | 13639092802 | `66c3b078049852d04f7cb54c1d1c38c26daa9b3ea53f0dd84cb12e50e82aff2f` |
| 8.4.0 / 65,536 | 13638883695 | `c7e656494f078b31ef7b007b4c4277c7c3a46ca71d6e3f1dff364abc08b6c096` |
| 8.4.1 / 8,192 | 13638782906 | `697795c6b3570562b5e6d74c81feb2490faf10453837e44e215462d9701beef5` |
| 8.4.1 / 65,536 | 13639142461 | `b12c1a55a677f6017a6db7d2ebe9432df418001b6a87f9eda96e61f1314c2358` |

`cases/` provides convenient byte-for-byte copies of the main text files.
`artifacts.csv` maps every matrix coordinate to its job, artifact, archive and
digest. `run-metadata.json` retains the relevant GitHub API fields and successful
job/step results. `qualification.json` records the post-download checks.

Verification established the following:

1. The run, attempt, source commit, tree and complete four-cell matrix agree.
2. All original ZIP digests and ZIP entry integrity checks pass.
3. Every cell's six source-file hashes match the exact source tree: 24 comparisons.
4. The CSV checkpoints, corpus totals, versions, heap limit and lifecycle counters
   satisfy the captured validator's contract. Its native histogram parser was
   reused to check **all 42 full histograms** against the CSV values.
5. Each complete extracted artifact passed the unchanged privacy scanner again.

The exact JAR bytes were hashed and rechecked by the validator **on each hosted
runner**, before upload. JAR binaries are not bundled in the output, so the
post-download qualification does not claim to rehash those binaries locally.
Their recorded hashes remain protected by the verified original archive digest.
No artifact values were replaced to reproduce the runner-only provenance check.

From the repository root, verify the retained files with:

```sh
sha256sum -c docs/evidence/search-ranked-scroll-hosted-2026-11-08/SHA256SUMS
```

## Interpretation and remaining scope

These native 8.4.1 observations strengthen the earlier
[upstream source comparison](../search-scroll-upstream-review-2026-11-08.md): the
expanding-page behavior remains present in the maintenance release on the
declared runtime graph and Java 21. Upgrading to 8.4.1 alone does not satisfy the
ranked-scroll memory requirement demonstrated by this fixture.

The counts represent live objects at specific checkpoints, and the byte columns
are shallow sizes. Objects referenced by each hit, other cursor state and
temporary allocations consume additional memory. HotSpot histogram collection
perturbs garbage collection; these runs provide no latency or throughput result.

The fixture uses one tenant, one shard, equal relevance scores and a fixed index.
It does not qualify custom Similarity, implicit tenant isolation, routing, or
snapshot behavior while the live index changes. Those remain required tests for
a supported production replacement. The separate failure-cleanup correction
does not change the ranked-page retention shown here.
