# jgit-storage-hibernate-search

Add rebuildable structured and full-text commit-history queries instead of walking and diffing the Git object graph for every request.

## The useful question

The individual questions “what did Alice commit?”, “what changed below `services/payments/fraud/`?” and “what changed in Q1?” can all be implemented with JGit. The application-grade question combines them:

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

JGit supplies revision walking and tree-diff primitives. This module supplies the reusable projection and compound database query so each request does not have to traverse and diff history again.

`CommitIndexer` stores paths changed relative to the first parent. Root commits treat every path as changed; merge commits use first-parent semantics. Added and modified changed-file content is indexed for full-text search, while deleted files remain represented by their path.

The executable [`CompoundCommitHistoryQueryH2Test`](src/test/java/io/github/carstenartur/jgit/storage/hibernate/search/CompoundCommitHistoryQueryH2Test.java) proves that unchanged files are not reported and that author, path and time predicates are combined with logical `AND`.

## What it adds

- index repository, object ID, messages, author, commit time, actual changed paths and selected changed-file text;
- combine author email, path fragment and inclusive time bounds through `CommitHistoryQuery`;
- query generic history through Hibernate ORM and Hibernate Search/Lucene;
- retain `findByAuthorEmail`, `findByPath`, `findBetween` and full-text convenience methods;
- share the Core persistence context while keeping Search optional;
- delete and rebuild projections because Git objects remain authoritative;
- provision the projection table through its own versioned Flyway history.

Choose this module when users or services need repeated history queries. Core alone is sufficient when repository storage is needed without generic search.

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

## Full-text query

```java
List<GitCommitIndex> hits =
    search.searchCommitText("payment-platform", "dualcontrol OR fraud", 50);
```

Full-text search covers commit messages, changed paths and the indexed content of added or modified changed files.

## Transaction model

`CommitIndexer` upserts each `GitCommitIndex` row in an explicit Hibernate transaction and rolls that transaction back on failure. This protects the projection update itself.

Search indexing is deliberately **not** the same transaction as Core pack publication or a JGit ref update. A commit can be valid and reachable even when projection indexing fails. Retry or rebuild the projection from authoritative Git history rather than treating `git_commit_index` as the source of truth.

## Database ownership

Search owns `git_commit_index` and `jgit_storage_hibernate_search_schema_history`. Domain-specific projections stay in the consuming application even when they share one `SessionFactory`.

## Operational model

The projection is derived data. Back up Git/Core data as authoritative state; plan a repeatable reindex operation after loss, corruption, analyzer changes or adoption of corrected changed-path semantics. See the [consumer and migration operations guide](../docs/consuming.md) for provisioning and upgrade procedures and the [change-audit use case](../docs/use-cases/change-audit-and-java-usage.md) for the complete comparison with plain JGit.

## Verification

H2 tests run on every build. With Docker available, Testcontainers starts PostgreSQL 17.10 and verifies Core-plus-Search installation, immutable 0.1.4 fixture adoption, projection persistence and Hibernate validation across a `SessionFactory` restart.
