# Follow-up measurements for publication and history exports

These studies use fixed coordinates from the three open performance issues.
Each workflow retains its measurement inputs, exact source identity and raw data.

## Independent publication on JDK 25

The 16-writer independent-repository comparison completed all 16 pairs
(32 fresh JVMs). It used released JGit 7.8.1, Temurin 25.0.5+8 with G1, a
1 GiB maximum heap and disk-backed PostgreSQL 17.10 with Hikari.
The payload was 256 KiB + 257 bytes and measured median RTT was 0.502 ms
with no artificial delay.

The geometric mean paired throughput change for the contention-aware policy
was **+1.16%**, with a one-sided 95% lower bound of **−0.39%**. This clears
the preregistered −5% non-regression margin. Both policies selected direct
publication throughout. Median fork throughputs were 99.69 and 100.53
publications/s for size and contention-aware, respectively.

This result qualifies one independent coordinate on the measured JDK and
library versions. The original JDK 21/JGit 7.8.0 cell remains incomplete.
The new workflow archive passed its SHA-256 check and replay reproduced the
hosted summary and both CSVs byte-for-byte.
[Native run](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42609723703) · [Raw artifact](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42609723703/artifacts/13669834215) · [Source](https://github.com/carstenartur/jgit-storage-hibernate/commit/61a961093a7516ecb5fd44e526ca1ebc9700c361)

## Local single-writer staging

Six independently hosted JVM/PostgreSQL 17.10 jobs measured complete Git
publication of 128 MiB + 257 bytes, with a 16 MiB staging window, one writer
and no added RTT. The recorded ext4 staging runs saw 0.144–0.265 ms round-trip
latency. The measured interval starts at object insertion and ends at durable
ref commit. The jobs validated the same Git identities, cold reopen, WAL
movement and the one- or two-window bounds for all 162 retained publications.

| Reader mode | Geometric mean elapsed-time change | One-sided upper bound | Meets +5% margin |
| --- | ---: | ---: | --- |
| Virtual thread | −0.90% | +0.81% | Yes |
| Platform thread | −2.07% | −1.44% | Yes |

The comparison uses six paired fork means and 95% joint confidence for the two
reader modes. This establishes non-regression for the local single-writer
control. Larger and concurrent publication coordinates still need measurement.
The earlier paced-read/50-ms effect remains a separate three-round observation.
The workflow archives passed SHA-256 checks, and replay from the six fork ZIPs
reproduced the hosted summary byte-for-byte.
[Native run](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42609949503) · [Summary JSON](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42609949503/artifacts/13669474453) · [Source](https://github.com/carstenartur/jgit-storage-hibernate/commit/144b34754b57d67f938c0f235f30f106d02dff92)

## History export with a larger ranked page

The released Lucene scroll grows its internal ranked-hit buffer during a
traversal. The four-chunk continuation keeps that buffer small and can repeat
a broad ranked search many times. At one million documents, the earlier native
warm indexed export took 454.464 seconds on PostgreSQL and 522.196 seconds on
SQL Server with the four-chunk candidate. The corresponding release exports
took 18.135 and 21.036 seconds. [Earlier comparison](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42581585809)

### Standalone page-size experiment

The fixture uses equal scores with timestamp and object-ID tie breakers,
Temurin 21.0.13+9, a 512 MiB maximum heap and a 128-row application chunk.
It verifies every projected row and its order. Timed traversals run separately
from the live `FieldDoc` histogram sampling. The 100,000-document medians use
three warmed traversals after one warm-up per configuration. The million-document
spot check uses one timed traversal per runtime in fresh, sequential JVMs.

| Documents | Search variant | Full traversal | First fetch | Maximum sampled live ranked hits |
| ---: | --- | ---: | ---: | ---: |
| 100,000 | 8.4.1.Final | 2.046 s | 57.0 ms | 34,976 |
| 100,000 | Four chunks | 6.756 s | 48.4 ms | 513 |
| 100,000 | 4,096-hit page | 2.124 s | 78.4 ms | 4,097 |
| 100,000 | 16,384-hit page | 1.573 s | 98.2 ms | 16,385 |
| 1,000,000 | 8.4.1.Final | 24.903 s | 637.9 ms | 476,224 |
| 1,000,000 | 16,384-hit page | 38.973 s | 1,041.3 ms | 16,385 |

The 100,000-document first-fetch column measures the first `next()`. The
million-document rows measure from cursor creation to the first returned chunk.
All histogram traversals reached zero retained hits after closing and releasing
the scroll. The million-document timed and histogram traversals balanced 3,909
ORM sessions per runtime and recorded zero SQL statements and entity loads.
The counts sample the ranked state at chunk boundaries. Index structures and
transient allocations during refill lie outside that measurement.

In a separate 100,000-document sweep, setting the hit-count threshold to 1
gave warmed medians of 6.734, 2.276 and 1.735 seconds at caps 512, 4,096 and
16,384. The measured benefit comes from the larger page in this fixture.
[Four-chunk source](https://github.com/carstenartur/hibernate-search/commit/2e6751194ff514e4d3e6fc31b672acc68ae96997) · [Standalone fixture](../../reproducers/hibernate-search-ranked-scroll/README.md)

The retained evidence includes the exact timed, threshold and cap-overlay
sources, launch scripts, raw CSVs, classpath and source fingerprints, and
84 full class histograms. The verifier reproduces both derived summaries.
[Standalone evidence and reproduction](https://github.com/carstenartur/jgit-storage-hibernate/blob/6857b83d22f1f6015ba3527f08b7ea4fb3734267/docs/evidence/search-ranked-page-local-2026-11-09/README.md)

### PostgreSQL and SQL Server spot check

The application harness compares Search 8.4.1.Final with a pinned source build
on the same runner for each database, in release-then-candidate order. It uses
one million rows, one warm repetition per filter and 100-row chunks. The
candidate's 16,384-hit target rounds down to a 16,300-hit page so every
interior page contains whole chunks. Surefire properties and class loading
identify the explicit opt-in and the five executed Search JARs.
[Application harness](https://github.com/carstenartur/jgit-storage-hibernate/commit/79c035f037a6e6605c47fce2cc9ce39b032bdba2) · [Candidate source](https://github.com/carstenartur/hibernate-search/commit/9f8ed03ffde4d8881331b3c45051761b171a57b2) · [Native run](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42610693833)

Both hosted database jobs passed. The report compares the warm indexed
export on each runner.

| Database | Release full export | Larger page full export | Release first chunk | Larger page first chunk |
| --- | ---: | ---: | ---: | ---: |
| PostgreSQL 17.10 | 13.98 s | 23.28 s | 287 ms | 506 ms |
| SQL Server 2022 CU20 | 15.85 s | 28.22 s | 296 ms | 712 ms |

During the warm indexed exports, the sampled process heap peak fell from
796 to 639 MiB on PostgreSQL and from 830 to 647 MiB on SQL Server. These are
process observations at measurement points. The standalone histograms above
measure ranked-hit objects separately.

Across both engines and runtimes, the artifacts retain 28 successful test cases,
40 validated exports and 212 offset observations. Release and candidate
offsets have identical row counts and object-ID endpoints. The native contracts
cover deterministic ordering, row equivalence and resource release. Both
archives passed SHA-256 checks and the four offset comparison reports
reproduced from the retained CSVs. The results cover one hosted spot check per
database. The reopened-index observations reuse the database process and OS
caches. Repeated candidate chunk-size comparisons, statement plans, waits and
database restart measurements remain open.
[PostgreSQL artifact](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42610693833/artifacts/13670264252) · [SQL Server artifact](https://github.com/carstenartur/jgit-storage-hibernate/actions/runs/42610693833/artifacts/13671620395)
