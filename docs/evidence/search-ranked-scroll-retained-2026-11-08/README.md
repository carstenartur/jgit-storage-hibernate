# Ranked-scroll live-hit evidence (cached classpath)

This folder retains the original cached-classpath Java 25 runs. A subsequent
[hosted Java 21 followup](../search-ranked-scroll-hosted-2026-11-08/README.md)
resolved the declared Maven graph and executed all four Search 8.4.0/8.4.1 and
8,192/65,536-document cells. Those native results are retained separately.

Issue [#230](https://github.com/carstenartur/jgit-storage-hibernate/issues/230)
remains open. The existing cursor limits the number of projected hits returned
per call, but Hibernate Search's ranked Lucene scroll retains larger native hit
pages as traversal advances. These measurements establish a concrete retained-hit
failure, independently of the earlier 2 GiB full-export measurements.

The [standalone public-API reproducer](../../../reproducers/hibernate-search-ranked-scroll/README.md)
indexes synthetic objects with no database, then traverses the normal Hibernate
Search `SearchScroll`. It measures live `FieldDoc` objects using HotSpot's native
class-histogram diagnostic through its documented management MBean. No application
search implementation or unsupported backend access is used.

## Observations

Both runs used Hibernate Search **8.4.0.Final**, Lucene **9.12.3**, Hibernate ORM
**7.4.14.Final**, Java **25.0.5+10-LTS**, `-Xms256m -Xmx512m`, and a chunk size of **128**.
Each ran in a fresh JVM. The fixed diagnostic budget for this reproduction was
1,024 live ranked hits above the pre-scroll baseline, eight times the requested
chunk size.

| Documents | First active sample | Sampled midpoint | Live hits at midpoint | FieldDoc shallow bytes at midpoint | After releasing closed scroll |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 8,192 | 512 at row 128 | row 4,096 | 4,096 | 131,072 | 0 |
| 65,536 | 512 at row 128 | row 32,768 | 32,768 | 1,048,576 | 0 |

The eightfold larger result set retained eight times as many ranked hits at the
sampled midpoint despite an unchanged requested chunk size. In the larger run,
the sampled hit counts grew through 512, 1,024, 2,048, 4,096, 8,192, 16,384, and
32,768. The optional fixed-budget assertion rejected both runs **after** full
traversal, successful projection/order checks, and cleanup. The expected final
errors are retained in each `console.txt`; they are the reproduction result,
not a passing bounded-memory test.

Each run checked score/time/object-ID ordering for this equal-score fixture,
timestamp ties, null field projection, and full `Instant` precision. Both recorded
zero entity loads and zero prepared SQL statements; session open and close counts
matched. These checks isolate the ranked-hit state from database entity hydration.

## Artifacts and scope

- `documents-8192/` and `documents-65536/` contain the native-run CSV, environment
  counters, console output, and labeled histogram excerpts.
- Excerpts retain the native histogram header, the exact `FieldDoc` and
  `ScoreDoc[]` lines when present, and the total. A missing ranked-hit line is
  labeled explicitly. They omit unrelated classes. The runnable reproducer emits
  complete histograms for every checkpoint; the excerpts are not full heap dumps.
- `runtime-jars.sha256` records the exact cached runtime dependencies used for
  these direct `javac --release 21` and `java` runs, including Hibernate Models
  1.1.2 and JBoss Logging 3.6.1.Final. They are cached versions; Search declares
  Hibernate Models 1.1.1 and JBoss Logging 3.6.3.Final, while the pinned ORM declares
  1.1.2 and 3.6.1.Final respectively. The standalone Maven recipe must be repeated
  with the resolved declared dependency graph before native acceptance.
- `SHA256SUMS` protects the retained artifacts and the source/POM used to produce
  them. Verify from the repository root with `sha256sum -c
  docs/evidence/search-ranked-scroll-retained-2026-11-08/SHA256SUMS`.
- The [frozen reproducer POM](sources/hibernate-search-ranked-scroll.pom) preserves
  the exact original `1.0-SNAPSHOT` bytes and checksum. The active standalone POM
  now uses version `1.0` to satisfy the repository release guard. This coordinate
  change does not alter the Java source or dependency versions, and is not a rerun
  or relabeling of these measurements.

The counts are live-object samples, and the bytes are shallow sizes. They do not
measure the entire cursor object graph, all temporary allocations, or a peak
between samples. The additional `ScoreDoc[]` arrays and the objects referenced by
each hit add memory beyond the `FieldDoc` bytes shown in the table. The last
nonempty page is smaller than the midpoint pages; checking only memory after
exhaustion would miss the growth.

The management diagnostic excludes unreachable objects and perturbs garbage
collection. These runs make no throughput or latency claims. In this execution
environment a separate `jcmd` process could not attach to the reported JVM PID;
the documented in-process diagnostic MBean successfully supplied the same live
class histogram. The probe itself still runs in a separate JVM from the build.

No production ranked-scroll replacement is included. Custom Similarity, implicit
tenant isolation, routing, and stable membership while the index changes remain
required qualification work described in the
[supported-integration review](../search-scroll-upstream-review-2026-11-08.md).
The independent cursor cleanup correction preserves original failures and
cancellation when close also fails; it does not change this memory result.

The separate source review found the following files byte-identical between the
official 8.4.0.Final and 8.4.1.Final tags. The 8.4.1 implementation still starts
with four chunks and doubles its ranked page. **8.4.1 was not executed in these
runs**; the source match is not a runtime qualification of that version.

| Upstream source in 8.4.1 | SHA-256, also present in 8.4.0 |
| --- | --- |
| [LuceneSearchScrollImpl.java](https://github.com/hibernate/hibernate-search/blob/8.4.1.Final/backend/lucene/src/main/java/org/hibernate/search/backend/lucene/search/query/impl/LuceneSearchScrollImpl.java) | `07fa3ce01b09e3da4baa71f5e9fd927193d47b122bbc97e3c7ab3634b1346fdf` |
| [LuceneSearcher.java](https://github.com/hibernate/hibernate-search/blob/8.4.1.Final/backend/lucene/src/main/java/org/hibernate/search/backend/lucene/work/impl/LuceneSearcher.java) | `b03dac123a411c969a4f4e36bb3aed4fe861c5d0e9ede67f415fd88c705e506d` |

The application Maven reactor could not start locally because the current
Jackson 3.2.4 and Spring Boot 4.1.2 BOMs are unavailable in the environment's cache.
The independent reproducer's offline Maven invocation separately stopped at an
uncached `maven-resources-plugin:3.3.1`, before dependency resolution. The direct
cached-classpath runs above succeeded in reproducing the issue; neither offline
Maven attempt is reported as a successful build.
Focused cleanup tests were run directly with JUnit 6.1.3: five failures before
the correction and all twelve cases passing after it. Native CI remains the merge
gate for that production correction; the reproducer's budget failures do not
satisfy #230's bounded-memory acceptance criterion.
