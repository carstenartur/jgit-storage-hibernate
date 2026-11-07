# Consumer-shaped benchmark fixtures

The benchmark module contains three deterministic Git fixture generators for
[issue #211](https://github.com/carstenartur/jgit-storage-hibernate/issues/211).
They use synthetic data and the same public JGit operations on filesystem and
Hibernate repositories. Use a fresh, disposable bare repository for each
workspace and a fixed set of input arguments to reproduce its object IDs.
Each generator initializes the default branch to `main`.

| Shape | Generated Git data | What to hold constant |
|---|---|---|
| audio-analyzer | Each publication appends a pseudo-random binary blob under `refs/heads/artifacts` and an artifact ID and size manifest under `refs/heads/main`. One atomic ref update advances both branches, which retain earlier publications. | Choose a seed per repository, replay increasing publication numbers, and keep artifact sizes equal across compared implementations. Use Core alone for the persistence baseline. When enabling Search, choose `metadata-v1` so Search can index commit metadata without reading artifact payloads. |
| Taxonomy | Each workspace has exactly the requested number of reachable commits, with structured requirement and term edits, different authors, proposal branches, two-parent merges and ref updates. | Split the total commit count over separately named repositories and use stable workspace numbers. Commit times, reflog transitions and reflog messages repeat; storage backends timestamp reflog writes when they happen. |
| sandbox | A source tree includes stable background classes and a caller of the changing class. Revisions cycle through a method rename, class rename, package move and one small method-body edit. | Hold class and commit counts constant. Compare a one-file body edit against a fresh Java Analysis rebuild from the same history. The tree needs no downstream application dependencies. |

The Audio generator uses a fixed 64 KiB input buffer irrespective of payload
size; its Git/ORM/database path must still be measured separately. The Taxonomy
and sandbox generators flush objects before publishing at most 64 queued ref
updates at a time. Benchmarks can prepare histories outside timed operations
when comparing query or index costs.

## Initial runner matrix

The following coordinates are starting points for the benchmark runners.
The fixtures have ordinary JUnit coverage; no scheduled or public benchmark
uses these inputs yet.

| Shape | Pull request smoke | Larger manual or scheduled comparison |
|---|---|---|
| audio-analyzer | One repository, 256 KiB and 2 MiB artifacts, publish and read back recent objects and refs. | One and four repositories, 16 and 128 MiB artifacts, stateful and stateless writers under fixed CPU and heap pressure; 512 MiB as a separate capacity run. |
| Taxonomy | Two workspaces, 50 commits each, author/time and exact path queries, compare result IDs to Git. | 1k, 10k and 100k total commits across ten workspaces; local and measured network RTT to PostgreSQL; compare query projections, concurrent publications and rebuild, purge and restart. |
| sandbox | Ten generated classes plus the caller, five commits, verify path and symbol changes. | Compare 250 and 1,000 generated classes with a fixed 1 GiB Java heap, measuring incremental analysis, full rebuild, allocation and index size. |

For read comparisons, record what was reset and what remains warm. Resetting
JGit's cache leaves database and OS caches warm. Measure network RTT separately
from query latency and compare released, main and candidate artifacts using
the same fixture parameters. Retain raw JMH and GC output with transaction,
JDBC batch and database telemetry where relevant. A new binary publication
must consume the input payload bytes and advance the artifact and metadata
refs; a verified history hit must resolve to an authoritative Git object.
Database statements, commit costs and round trips above those bounds depend
on the implementation.

These fixtures represent workload shapes. They do not reproduce the consumer
applications' concurrency, configuration or downstream dependencies. The
[consumer relevance tags](benchmark-consumer-relevance.md) describe modules
selected by pinned consumer contracts, independently of which fixture was used.
For example, a Search experiment on a Taxonomy-shaped history does not establish
that the current Taxonomy contract uses Search.

Publication still needs explicit fixture provenance in the dashboard, evidence
that the real consumer contracts passed for the measured library commit, and
comparable runs that justify an operational decision for each shape. Keep
existing operation names and anchors, and base production defaults on evidence
across relevant profiles.
