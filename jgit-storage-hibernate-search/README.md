# jgit-storage-hibernate-search

Add a rebuildable relational and Lucene query model over Git history.

The module moves revision traversal, first-parent diffing, changed-text extraction and index construction from every request to commit-ingestion or explicit reindex time. Request-time work is then handled by relational indexes and Hibernate Search/Lucene.

## Why indexing matters

Without a projection, repeated history requests repeatedly traverse commits and compare trees:

```text
query -> RevWalk -> parent diff -> path/content filtering -> result
query -> RevWalk -> parent diff -> path/content filtering -> result
```

With this module:

```text
commit/reindex -> RevWalk + first-parent diff + text extraction + index update
query          -> relational predicates and/or Lucene full-text search
query          -> relational predicates and/or Lucene full-text search
```

This is a deliberate read-optimized architecture. Indexing adds work when a commit enters the projection, but avoids recalculating the same history for each audit, support, reporting or search request.

Git/JGit does not normally provide a general full-text query engine over commit messages, actual changed paths and changed-file contents. Hibernate Search/Lucene adds analyzers, an inverted index and composable full-text queries over those fields.

## Compound structured query

> Which changes did Alice make in the fraud subsystem during Q1?

```java
CommitHistoryQuery query =
    CommitHistoryQuery.forRepository("payment-platform")
        .authoredBy("alice@example.com")
        .touchingPath("services/payments/fraud/")
        .between(
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-03-31T23:59:59Z"))
        .limit(100)
        .build();

List<GitCommitIndex> hits =
    new GitHistorySearchService(sessionFactory).findChanges(query);
```

`CommitIndexer` stores paths changed relative to the first parent. Root commits treat every path as changed; merge commits use first-parent semantics. The relational projection makes author, changed-path and time predicates jointly queryable without traversing and diffing history at request time.

The executable [`CompoundCommitHistoryQueryH2Test`](src/test/java/io/github/carstenartur/jgit/storage/hibernate/search/CompoundCommitHistoryQueryH2Test.java) proves that unchanged files are not reported and that all predicates are combined with logical `AND`.

## Full-text query

```java
List<GitCommitIndex> hits =
    search.searchCommitText(
        "payment-platform",
        "\"dual control\" OR fraud OR CVE-2026-*",
        50);
```

Full-text search covers:

- short and full commit messages;
- actual first-parent changed paths;
- indexed content of added or modified changed files.

Deleted files remain represented by path. Large or non-blob content is intentionally not loaded into the text projection.

The full-text index is not a wrapper around `git log` or `git grep`. It is a maintained Lucene inverted index over historical metadata and selected changed content, optimized for repeated search queries.

## What it adds

- materialize repository, object ID, messages, author, commit time and actual changed paths;
- extract selected changed-file text during indexing rather than during every query;
- combine author email, literal path fragment and inclusive time bounds through `CommitHistoryQuery`;
- run full-text queries through Hibernate Search/Lucene;
- retain `findByAuthorEmail`, `findByPath`, `findBetween` and full-text convenience methods;
- share the Core database configuration while keeping Search optional;
- delete and rebuild projections because Git objects and refs remain authoritative;
- provision the projection table through its own versioned Flyway history.

Choose this module when history is an application query workload, not only something inspected occasionally with repository traversal.

## Dependency

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-search</artifactId>
  <version>0.1.5</version>
</dependency>
```

The Search artifact depends on Core. Apply Core migrations first, then Search migrations with its separate history table.

## Registration

```java
List<Class<?>> annotatedClasses = new ArrayList<>();
annotatedClasses.addAll(SearchEntities.annotatedClasses());
annotatedClasses.add(MyApplicationEntity.class);

try (HibernateSessionFactoryProvider provider =
    new HibernateSessionFactoryProvider(properties, annotatedClasses)) {
  CommitIndexer indexer =
      new CommitIndexer(provider.getSessionFactory(), repositoryName);
  GitHistorySearchService search =
      new GitHistorySearchService(provider.getSessionFactory());
}
```

## Update and consistency model

The index can be maintained synchronously after ref publication, asynchronously, or from an application outbox.

- Git/Core remains authoritative.
- The Search projection may temporarily lag behind Git when indexing is asynchronous.
- `CommitIndexer` upserts each `GitCommitIndex` row in an explicit Hibernate transaction.
- Search indexing is not the same transaction as Core pack publication or a JGit ref update.
- A failed index update is retried or rebuilt; it does not invalidate a successfully published commit.
- Reindexing is the explicit operation that pays the derivation cost again after loss, analyzer changes or projection-semantics changes.

## Database ownership

Search owns `git_commit_index` and `jgit_storage_hibernate_search_schema_history`. Domain-specific projections remain in the consuming application even when they share one `SessionFactory`.

## Verification

H2 tests run on every build. With Docker available, Testcontainers starts PostgreSQL 17.10 and verifies Core-plus-Search installation, immutable 0.1.4 fixture adoption, projection persistence and Hibernate validation across a `SessionFactory` restart.

See the [change-audit and Java-usage use case](../docs/use-cases/change-audit-and-java-usage.md) for the complete architectural comparison.
