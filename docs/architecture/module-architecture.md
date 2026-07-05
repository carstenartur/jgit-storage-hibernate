# Module architecture

## Decision

`jgit-storage-hibernate` is a multi-module Maven project.

```text
jgit-storage-hibernate-parent
  ├─ jgit-storage-hibernate-core
  └─ jgit-storage-hibernate-search
```

## Rationale

The three source lines have different architectural concerns:

- the JGit fork contains the reusable DFS/Reftable/Hibernate storage implementation;
- Sandbox adds indexing, search and server experimentation;
- Taxonomy demonstrates application-level workflows such as branch management, DSL commits, diff, merge, cherry-pick, staleness tracking and searchable history.

Only the storage and generic history search concerns belong in this repository. Application DSLs, web endpoints, workflow editors and domain-specific materialization remain in consuming projects.

## Core module

`jgit-storage-hibernate-core` contains:

- public facade API;
- Hibernate-backed `Repository` implementation;
- DFS object database adapter;
- Reftable reference database adapter;
- pack/reftable persistence entities;
- queryable reflog reader/writer;
- H2 integration tests.

The core module intentionally does not depend on Hibernate Search, Lucene, JDT, REST frameworks or application domain models.

## Search module

`jgit-storage-hibernate-search` contains optional generic projections:

- `GitCommitIndex`;
- `CommitIndexer`;
- `GitHistorySearchService`;
- H2/Hibernate Search integration tests.

The search module is generic. It indexes commit messages, paths and text content. Java-specific AST parsing, embeddings, rank fusion and REST resources from Sandbox are candidates for later extension modules, not for the core storage artifact.

Potential future modules:

```text
jgit-storage-hibernate-java-search     Java/JDT source structure indexing
jgit-storage-hibernate-server          optional REST server, if needed
jgit-storage-hibernate-testkit         reusable DB/test fixtures
```

## Public API rule

Consuming applications must not import `org.eclipse.jgit.internal.*` directly. All JGit internal API usage is isolated in implementation packages inside this repository.

## Release rule

A release must allow a consumer to depend on at least:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-core</artifactId>
  <version>...</version>
</dependency>
```

Search consumers add:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-search</artifactId>
  <version>...</version>
</dependency>
```
