# Ranked-scroll page cap experiment

The Lucene draft for [jgit-storage-hibernate #230](https://github.com/carstenartur/jgit-storage-hibernate/issues/230) retains at most four chunks of ranked hits. Its previous native qualification observed a substantial slowdown at one million results. This experiment varies the number of retained ranked hits while holding the application chunk size at 128. The source candidate is [Hibernate Search draft #306 at `2e675119`](https://github.com/carstenartur/hibernate-search/pull/306/commits/2e6751194ff514e4d3e6fc31b672acc68ae96997). The fixture comes from jgit-storage-hibernate `2eeb52bff134133cd02aea361a6ec99428ac1d33`.

## 100,000 documents

The timing probe recorded one warmup and three measured traversals per configuration. The table reports the median of those three measurements. Independent histogram processes used the original consumer probe and verified the same order, compact projections and session balance. All four histogram processes returned to zero live `FieldDoc` instances after close and release.

| Runtime | Median full traversal | Median first `next()` | Maximum sampled live `FieldDoc` instances |
| --- | ---: | ---: | ---: |
| Released Search 8.4.1.Final | 2.046 s | 57.0 ms | 34,976 |
| Exact source draft, 512 hits | 6.756 s | 48.4 ms | 513 |
| Source draft with 4,096-hit overlay | 2.124 s | 78.4 ms | 4,097 |
| Source draft with 16,384-hit overlay | 1.573 s | 98.2 ms | 16,385 |

Using `totalHitCountThreshold(1)` as the sole query change gave 6.734, 2.276 and 1.735 seconds at caps 512, 4,096 and 16,384, respectively. It provided no material benefit for this equal-score sort. The cap of 16,384 improved traversal time in this fixture and raised the first fetch latency. [Raw summary](results/100000/summary.csv)

## One million documents

The predeclared spot check recorded one timed traversal in each freshly seeded JVM, followed by a separate histogram traversal. The total-hit threshold remained at its default for both cells. The larger cap retained the same maximum sampled ranked state at 100,000 and one million documents. At one million, it retained 29 times fewer sampled `FieldDoc` instances than the release and took 1.56 times as long to traverse. Both processes released all sampled ranked hits, balanced 3,909 opened and closed sessions, and issued zero SQL statements or entity loads. [Raw summary](results/1000000/summary.csv)

| Runtime | Full traversal | Time to first row | Maximum sampled live `FieldDoc` instances | After release |
| --- | ---: | ---: | ---: | ---: |
| Released Search 8.4.1.Final | 24.903 s | 637.9 ms | 476,224 | 0 |
| Source draft with 16,384-hit overlay | 38.973 s | 1,041.3 ms | 16,385 | 0 |

## Retained evidence

- `harness/` contains the exact Java sources and shell runners used for the reported observations. The 100,000-document retention sweep uses the original memory fixture at consumer commit `2eeb52bff134133cd02aea361a6ec99428ac1d33`.
- `results/` contains every raw timing CSV, every retained-hit CSV, environment records, classpaths, class origins and runtime hashes. Workspace and Maven-cache prefixes in text records are replaced with `@STUDY_ROOT@` and `@MAVEN_REPOSITORY@`. Numeric data, source hashes and artifact hashes are preserved.
- `histograms.tar.xz` contains all 84 complete original HotSpot class histograms. Archive member order, timestamps, owners and modes are normalized. The histogram contents are byte-for-byte originals.
- `SHA256SUMS` covers the retained files. `python3 verify.py` validates the data offline, re-parses all original histograms with the pinned consumer validator and regenerates both summary CSVs.

## Reproduce locally

Use Linux with Bash, Git, Python 3.9 or newer, Temurin 21.0.13+9, JDK 25.0.5+8 and Maven 3.9.16. The launcher verifies both Git commit and tree IDs before building. It builds the Search candidate with JDK 25 and runs the standalone fixture with JDK 21.

```sh
JAVA21_HOME=/path/to/jdk21 JAVA25_HOME=/path/to/jdk25 ./reproduce.sh /path/to/empty-workspace
```

The release cell loads Hibernate Search 8.4.1.Final. The exact candidate cell loads the five source-built Search JARs from `2e6751194ff514e4d3e6fc31b672acc68ae96997`. Caps of 4,096 and 16,384 use the same candidate runtime plus the source in `harness/src/LuceneSearchScrollImpl.java`, compiled as a one-class overlay. The runner passes the cap via `ranked.scroll.experimental-page-limit`. The 512-hit candidate uses its default cap with no overlay. The threshold sweep changes only the public query option to `totalHitCountThreshold(1)` using `harness/threshold/src/RankedScrollMemoryProbe.java`. Both million-result cells retain the default exact threshold. [Predeclared million-result protocol](harness/million-protocol.md)

New results appear under `study/experiment/results/` in the reproduction workspace. Timing is observational and depends on the machine and JVM state. The retained JAR digests identify the original measured binaries. Fresh source builds may yield different JAR digests.

To rebuild an archive from a run, enter its `results/` directory and use GNU tar and xz with the original options.

```sh
find . -name '*.histogram.txt' -print0 | LC_ALL=C sort -z |
  tar --format=ustar --null -T - --mtime='@0' --owner=0 --group=0 \
    --numeric-owner --mode='a=r,u+w' -cf - | xz -T1 -9e > ../histograms.tar.xz
```

This is a single-shard synthetic equal-score full-text workload on a local-heap Lucene index, with a 512 MiB JVM limit. `FieldDoc` counts sample reachable ranked-hit instances at chunk boundaries and omit transient refill allocations and index structures. Qualification of the native chunk matrix, database statement plans, waits and cold restarts remains separate.
