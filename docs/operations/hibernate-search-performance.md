# Hibernate Search performance

The optional Search module keeps Git as the authoritative history and materializes a relational plus Lucene projection for repeated audit and history questions. Performance therefore has two independent dimensions:

1. extracting and persisting projections when commits arrive or a repository is rebuilt;
2. serving bounded queries without traversing Git or materializing data that the result does not need.

The dedicated `Hibernate Search Performance` workflow retains raw JMH JSON, GC/ORM counters, Maven/Failsafe reports and grouped chart input for PostgreSQL 17.10 plus local-filesystem Lucene.

## Public charts

- [Incremental indexing](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-hibernate-search-indexing)
- [Projection rebuild](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-hibernate-search-rebuild)
- [Full-text query: entity hydration versus Lucene projection](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-hibernate-search-full-text-query)
- [Path query: SQL literal fragment versus Lucene analyzed terms](https://carstenartur.github.io/jgit-storage-hibernate/dev/bench/#benchmark-hibernate-search-path-query)

The chart operation names are stable. New implementation alternatives appear as series inside the same operation rather than creating unrelated chart pages.

## Bounded indexing and rebuild

`CommitIndexer` groups reachable commits instead of opening a new existence query and transaction for every commit:

```properties
jgit.storage.hibernate.search.index_batch_size=50
```

Accepted values are 1 through 1,000. One group:

- resolves existing object IDs through one bounded `IN` query for incremental indexing;
- reuses the same `RevWalk` and `ObjectReader` while extracting commits;
- skips existence checks entirely when the rebuild has just purged the repository projection;
- persists all missing projections in one transaction;
- flushes before commit and discards the bounded persistence context afterward.

The rebuild purge is independently bounded:

```properties
jgit.storage.hibernate.search.purge_batch_size=250
```

It removes at most one page of entities and corresponding Lucene documents per transaction. A failed or interrupted rebuild remains derived state: the next invocation purges the partial projection and starts deterministically from authoritative Git history.

### Batchable projection identifiers

The historical numeric `git_commit_index.id` identity column remains in migration-backed databases for compatibility. It is no longer the ORM/Hibernate Search document identifier. `projection_key` is assigned before persistence:

- existing rows receive `legacy-<id>` during migration;
- new rows receive UUID values;
- repository/object ID remains the domain-level unique key.

This removes generated-key retrieval from each new projection insert and allows Hibernate to submit real JDBC batches. H2 integration evidence verifies one JDBC batch for 50 compatible projection inserts.

In the deterministic 100-commit PostgreSQL fixture, the first bounded implementation still used the identity identifier and required roughly 103 prepared statements per indexing invocation. The assigned-key variant reduced that count to about five while retaining two bounded transactions. Its elapsed-time point estimate fell from approximately 262 ms to 214 ms. These are small-run point estimates rather than a universal throughput claim; the retained history is the regression baseline for larger scheduled fixtures.

## Lightweight search results

The existing entity-returning API remains available. It is useful when callers genuinely need the complete projection, including changed paths and up to roughly 250,000 characters of indexed changed text.

Most result lists need only compact metadata. `CommitSearchHit` can be returned without hydrating `GitCommitIndex`:

```java
List<CommitSearchHit> hits =
    search.findChangeSummaries(
        CommitHistoryQuery.forRepository("payment-platform")
            .matchingText("threshold")
            .authoredBy("alice@example.com")
            .limit(50)
            .build());
```

For full-text and explicit Lucene path queries, the compact fields are projected directly from Lucene. For structured SQL-only queries, an HQL constructor projection reads only the selected columns. Large changed-text/path columns are not projectable and are not loaded for summaries.

The first 100-commit/50-result fixture reported:

| Full-text result representation | Mean point estimate | Relational query/entity loads |
|---|---:|---:|
| Complete `GitCommitIndex` entities | about 21.8 ms | one SQL query and 20 entity loads per invocation |
| `CommitSearchHit` Lucene projection | about 17.1 ms | zero SQL queries and zero entity loads |

All three raw projection measurements were lower in that run, but the sample is intentionally small and its confidence intervals remain broad. The architectural guarantee is stronger than the timing claim: the summary path avoids relational hit hydration and large-column materialization.

## Path query modes

Path semantics are explicit:

```java
// Backward-compatible literal, case-insensitive SQL substring.
query.touchingPath("payments/fraud");

// All analyzed path components through Lucene.
query.touchingPathTerms("services payments fraud");

// One complete changed path through the exact Lucene keyword field.
query.touchingExactPath("services/payments/fraud/rules.yaml");
```

`touchingPath(...)` keeps the previous relational behavior for path-only queries. This matters for compatibility and remains efficient for small local projections. The two explicit alternatives use Lucene even without a free-text expression, apply repository/identity/time filters in the same indexed query and sort by selected timestamp descending plus object ID ascending.

In the initial 100-commit local-database fixture, the SQL literal query was faster than the analyzed Lucene query (about 7.6 ms versus 20.4 ms). Lucene nevertheless avoided all relational query/entity work. The larger 10,000/100,000-commit and remote-database profiles defined in the performance roadmap must establish the crossover before the compatibility default changes.

## Reproduction

Run the bounded smoke matrix with Docker available:

```bash
mvn -pl jgit-storage-hibernate-benchmarks verify \
  -Psearch-performance
```

The workflow also converts the raw result into grouped dashboard series and publishes history only from a successful `main` run. Pull requests upload evidence but do not mutate the public benchmark history.

## Interpretation boundaries

- Search indexing is derived and rebuildable; it is not the authoritative Git transaction.
- The current smoke fixture contains 100 deterministic commits and is designed for regression feedback, not absolute capacity planning.
- Lucene projections trade some stored-field bytes for removing relational hit hydration.
- SQL and analyzed/exact path modes are not semantically identical; their chart series must not be interpreted as interchangeable without considering query intent.
- Writer RAM, refresh/synchronization strategy, content profiles, scrolling, routing/sharding and multi-node coordination remain separate evidence-gated issues.
