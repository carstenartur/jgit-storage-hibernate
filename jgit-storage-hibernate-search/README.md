# jgit-storage-hibernate-search

Add rebuildable structured and full-text commit-history search instead of walking and parsing the Git object graph for every query.

## Why use it

- index repository, object ID, messages, author, commit time, changed paths and selected text;
- query generic history through Hibernate Search/Lucene;
- share the Core persistence context while keeping Search optional;
- delete and rebuild projections because Git objects remain authoritative;
- provision the projection table through its own versioned Flyway history.

Choose this module when users or services need fast, repeated history queries. Core alone is sufficient when repository storage is needed without generic search.

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

## Database ownership

Search owns `git_commit_index` and `jgit_storage_hibernate_search_schema_history`. Domain-specific projections stay in the consuming application even when they share one `SessionFactory`.

## Operational model

The projection is derived data. Back up Git/Core data as authoritative state; plan a repeatable reindex operation for Search after loss, corruption or analyzer changes. See the [consumer and migration operations guide](../docs/consuming.md) for provisioning and upgrade procedures.

## Verification

H2 tests run on every build. With Docker available, Testcontainers starts PostgreSQL 17.10 and verifies Core-plus-Search installation, immutable 0.1.4 fixture adoption, projection persistence and Hibernate validation across a `SessionFactory` restart.
