# jgit-storage-hibernate-search

Add rebuildable structured and full-text commit-history search instead of walking and parsing the Git object graph for every query.

## Why use it

- index repository, object ID, messages, author, commit time, changed paths and selected text;
- query generic history through Hibernate Search/Lucene;
- share the Core database configuration and Hibernate lifecycle while keeping Search optional;
- delete and rebuild projections because Git objects remain authoritative;
- provision the projection table through its own versioned Flyway history.

Choose this module when users or services need fast, repeated history queries. Core alone is sufficient when repository storage is needed without generic search.

## Application example

In the [approval-workflow use case](../docs/use-cases/versioned-approval-workflows.md), Core stores immutable workflow commits and the active ref in PostgreSQL. Search then answers application questions that plain JGit would otherwise require custom revision walking and content parsing for every request:

```java
CommitIndexer indexer = new CommitIndexer(sessionFactory, "approval-workflows");
indexer.indexCommit(repository, publishedCommitId);

GitHistorySearchService history = new GitHistorySearchService(sessionFactory);

List<GitCommitIndex> policyChanges =
    history.searchCommitText("approval-workflows", "dualcontrol", 20);

List<GitCommitIndex> workflowVersions =
    history.findByPath("approval-workflows", "purchase-approval.yaml", 50);
```

The projection records commit messages, authors, timestamps, paths and selected blob text. It is an application read model, not a replacement for the Git object graph.

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

## Transaction model

`CommitIndexer` upserts each `GitCommitIndex` row in an explicit Hibernate transaction and rolls that transaction back on failure. This protects the relational/Lucene projection update itself.

Search indexing is deliberately **not** the same transaction as Core pack publication or a JGit ref update. A commit can be valid and reachable even when projection indexing fails. The correct recovery is to retry or rebuild the projection from authoritative Git history, not to treat the Search table as the source of truth.

The current implementation also does not automatically join an arbitrary application transaction. See the [precise Core/Search transaction boundaries](../docs/use-cases/versioned-approval-workflows.md#important-transaction-boundary).

## Database ownership

Search owns `git_commit_index` and `jgit_storage_hibernate_search_schema_history`. Domain-specific projections stay in the consuming application even when they share one `SessionFactory`.

## Operational model

The projection is derived data. Back up Git/Core data as authoritative state; plan a repeatable reindex operation for Search after loss, corruption or analyzer changes. See the [consumer and migration operations guide](../docs/consuming.md) for provisioning and upgrade procedures.

## Verification

H2 tests run on every build. With Docker available, Testcontainers starts PostgreSQL 17.10 and verifies Core-plus-Search installation, immutable 0.1.4 fixture adoption, projection persistence and Hibernate validation across a `SessionFactory` restart. `VersionedApprovalWorkflowUseCaseTest` compiles and executes the documented end-to-end application flow.
