# JGit versus indexed history-query crossover

This benchmark answers a different performance question from the normal repository backend comparison.

The storage benchmark asks whether database-backed Git operations can stay in the same performance class as a normal JGit `FileRepository`. This benchmark asks when the additional materialized history projection turns that architecture into a performance advantage for repeated application queries.

## What is compared

Every retained query is answered from the same deterministic linear Git history through three implementations:

| Series | What happens at query time | Why it exists |
|---|---|---|
| `FileRepository / JGit on demand` | JGit walks commits and, where needed, inspects the exact changed path and current changed blob from a normal bare filesystem repository. | Practical baseline for an application that stores ordinary Git and computes the answer when requested. |
| `HibernateRepository / JGit on demand` | The identical JGit algorithm runs against the database-backed repository without consulting the Search projection. | Separates storage-backend cost from the value of materialization. |
| `Indexed history projection` | `GitHistorySearchService` answers from the relational projection or Hibernate Search/Lucene, returning compact `CommitSearchHit` values. | Measures the architecture's intended repeated-query path. |

The comparison is deliberately **not** “Lucene versus an intentionally slow filesystem scan”. The on-demand implementation uses one `ObjectReader` and one `RevWalk` per query, applies cheap author/time/message predicates before tree access, stops after crossing a lower time bound, and restricts tree inspection to the exact requested path. Blob content is loaded only for queries that actually ask about changed content.

The fixture itself is an ordinary Git hierarchy: `README.md`, `docs/operations.txt`, `services/payments/core.txt` and `services/payments/fraud/rules.txt` are written through an in-memory JGit `DirCache`, which creates canonical nested trees. This matters for the path benchmarks because JGit must traverse the same tree hierarchy that a normal repository would contain.

The benchmark also verifies the indexed result set against the on-demand JGit result set on the same Hibernate repository before timing begins. A semantic mismatch fails the benchmark instead of producing an attractive but invalid speedup.

## Retained application questions

The deterministic fixture marks a sparse, repeatable subset of commits so complete matching result sets remain bounded even at 100,000 commits. The same commits carry the relevant author, path, message and changed-content markers, which keeps the compound query non-empty and makes cross-size comparisons stable.

### Author and time

> Which changes did Alice author during the selected interval?

The indexed implementation uses the compact relational projection because no full-text or explicit Lucene path predicate is required.

[Chart: author + time](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-git-history-author-time-query)

### Exact changed path and time

> Which commits changed `services/payments/fraud/rules.txt` during the selected interval?

The JGit variants inspect the exact path relative to the first parent. The indexed implementation uses the exact Hibernate Search path field.

[Chart: path + time](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-git-history-path-time-query)

### Commit-message search

> Which commits contain the incident marker in their commit message?

The JGit variants inspect commit metadata while traversing history. The indexed implementation uses the Hibernate Search full-text projection.

[Chart: commit-message query](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-git-history-commit-message-query)

### Exact changed path plus changed content

> Which changes to the fraud-rules file contain the policy marker in the changed file content?

This is the first query where ordinary JGit must combine commit traversal, first-parent path-change detection and blob reading for candidates. The indexed implementation combines exact path and changed-text predicates.

[Chart: path + changed content](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-git-history-path-changed-content-query)

### Compound audit query

> Which changes by Alice, in the selected interval, touched the fraud-rules file and contain the policy marker?

This composes identity, time, exact changed path and changed-file content. It is intentionally close to the audit/reporting use cases for which the Search projection exists.

[Chart: compound audit query](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-git-history-compound-audit-query)

## Indexing cost is part of the result

Query latency alone would hide the largest cost paid by the indexed architecture: the projection must first be created and subsequently maintained.

A separate JMH operation therefore rebuilds `content-v1` from an already-created authoritative Git history:

[Chart: indexed projection build](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-git-history-indexed-projection-build)

For every indexed query series, chart metadata reports both measured speedup and the query-count break-even:

```text
break-even queries
  = projection build time
    / (on-demand query time - indexed query time)
```

The calculation is shown independently against `FileRepository / JGit` and `HibernateRepository / JGit`. If the indexed query is not faster in a particular run, the dashboard reports that break-even was not reached rather than producing a negative or misleading number.

This is deliberately conservative. A real application normally maintains the projection incrementally as commits arrive; it does not rebuild the complete index before every reporting query. The full rebuild is used here as an easily reproducible upper-bound startup cost.

## First canonical smoke evidence

The first green run using canonical nested Git trees used 1,000 commits, PostgreSQL 17 and a fully rebuilt local-filesystem Lucene `content-v1` projection. The complete projection-build point estimate was **840.0 ms**.

| Query | FileRepository + JGit | HibernateRepository + JGit | Indexed projection | Indexed vs filesystem | Full-build break-even vs filesystem |
|---|---:|---:|---:|---:|---:|
| Author + time | 23.93 ms | 8.64 ms | 2.62 ms | 9.14× | 39.4 queries |
| Exact path + time | 66.86 ms | 17.29 ms | 3.10 ms | 21.59× | 13.2 queries |
| Commit-message text | 39.85 ms | 15.99 ms | 4.03 ms | 9.89× | 23.5 queries |
| Exact path + changed content | 96.20 ms | 22.02 ms | 3.47 ms | 27.73× | 9.1 queries |
| Compound audit | 36.31 ms | 10.22 ms | 2.62 ms | 13.86× | 24.9 queries |

The indexed path-and-content query is also **6.35×** faster than executing the same on-demand JGit algorithm over the Hibernate-backed repository. Exact path plus time is **5.58×** faster than HibernateRepository/JGit. Those comparisons isolate the benefit of the materialized read model from the difference between filesystem and database object storage.

These are **workload point estimates, not universal speedup claims**. The smoke run deliberately uses only three SingleShot measurements, so JMH's high-confidence error intervals are broad. The raw samples nevertheless preserve the same qualitative ordering for every retained query. For the strongest path+content comparison, FileRepository/JGit measured 122.41, 82.00 and 84.20 ms while the indexed query measured 2.85, 4.78 and 2.78 ms. For path+time, FileRepository/JGit measured 69.30, 68.09 and 63.18 ms while the indexed query measured 2.59, 2.58 and 4.12 ms.

The work counters explain the architectural difference. For path+content at 1,000 commits, JGit visited 1,000 commits and performed 1,000 exact-path tree inspections before loading four matching changed blobs. The indexed query visited no Git commits or trees at query time. For path+time, JGit visited 901 commits and performed 800 exact-path inspections, while the indexed query again used the precomputed path field.

## Work-amplification evidence

The raw JMH secondary counters retain, per query:

- matching result count;
- commits visited by the on-demand traversal;
- exact-path tree inspections;
- changed blobs read;
- changed blob bytes read;
- Hibernate prepared statements and transactions.

These counters distinguish a genuine indexing crossover from unrelated storage noise. For example, a growing `commitsVisited` count with nearly constant indexed latency explains *why* the two approaches diverge as history grows.

## Scale matrix

Pull requests and normal pushes run the bounded smoke profile with every retained query:

```text
smoke: 1,000 commits × all five query kinds × all three engines
```

The scheduled/manual `full` profile keeps the same broad query coverage while adding the next history scale:

```text
full: 1,000 and 10,000 commits × all five query kinds × all three engines
```

A separate manual `large` profile reaches 100,000 commits without rebuilding the same large deterministic history for all five query families. It retains the two queries where materialization avoids the most Git tree/content work:

```text
large: 100,000 commits × path+changed-content and compound × all three engines
```

All variants use a query limit of 500. The deterministic sparse fixture is validated so the complete result set remains below that limit at every supported size; this avoids comparing JGit's chronological truncation with Hibernate Search relevance-ranked truncation.

The weekly schedule runs `full`. The `large` profile is explicit/manual because constructing a 100,000-commit authoritative history is benchmark setup evidence rather than useful pull-request latency.

## Cache and deployment interpretation

This benchmark represents a long-running application with warm host/database caches. Every on-demand query creates a fresh `ObjectReader`/`RevWalk`, and every indexed query creates the normal Search/Hibernate query context. It does **not** clear the operating-system page cache or PostgreSQL shared buffers between measurements.

PostgreSQL runs in Testcontainers on the same GitHub Actions host; the filesystem baseline also uses local temporary storage. Network RTT is therefore not part of this comparison. Remote-database latency is measured separately by the network benchmark and should not be mixed into the indexing crossover until a dedicated deployment matrix is added.

## What a strong result means

A large indexed-query speedup does not mean that relational storage makes Git object access inherently faster. The three-way comparison is designed to make that distinction visible:

```text
FileRepository + JGit on demand
            ↕ storage backend effect
HibernateRepository + JGit on demand
            ↕ materialized query model effect
HibernateRepository + indexed projection
```

The canonical smoke evidence already shows the expected mechanism: the on-demand variants revisit commit/tree state for every query, while the indexed query avoids that work. The 10,000- and 100,000-commit profiles exist to establish the scaling curve before treating any single multiplier as a general project claim.

## Reproduce locally

With Java 21, Maven and Docker available:

```bash
mvn -B -pl jgit-storage-hibernate-benchmarks -am install -DskipTests
mkdir -p target/history-query-crossover
mvn -B -pl jgit-storage-hibernate-benchmarks verify \
  -Phistory-query-crossover \
  -Dbenchmark.history.commitCounts=1000 \
  -Dbenchmark.history.queryKinds=author-time,path-time,message-text,path-content,compound \
  -Dbenchmark.resultFile="$(pwd)/target/history-query-crossover/jmh-result.json"
python3 .github/scripts/convert-jmh-history-query-crossover.py \
  target/history-query-crossover/jmh-result.json \
  target/history-query-crossover/comparison.json
```

Use `-Dbenchmark.history.commitCounts=1000,10000` for the broad full matrix. For the bounded large matrix use `-Dbenchmark.history.commitCounts=100000 -Dbenchmark.history.queryKinds=path-content,compound`.
