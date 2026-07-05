# jgit-storage-hibernate

Hibernate-backed storage backend for JGit repositories.

`jgit-storage-hibernate` provides database-backed repository infrastructure for JGit. It persists Git pack data, reftable data, refs/reflog projections and optional searchable history projections in relational databases through Hibernate ORM and Hibernate Search.

The project is intended for applications that need Git semantics without relying on a filesystem-backed `.git` directory, for example versioned domain models, collaborative editors, audit trails and searchable history.

## Status

This repository is being bootstrapped as an independent infrastructure module. The initial implementation consolidates the existing Hibernate storage work from `carstenartur/jgit`, `carstenartur/sandbox` and the database-backed JGit adapter used in `carstenartur/Taxonomy`.

The first extracted architecture is intentionally split into modules:

```text
jgit-storage-hibernate-parent
  ├─ jgit-storage-hibernate-core
  │    Hibernate/JGit repository, object database, reftable refs, reflog rows
  │
  └─ jgit-storage-hibernate-search
       Optional Hibernate Search projections for commits, blobs and paths
```

This keeps the core storage backend consumable without forcing Lucene, JDT or future embedding dependencies on every application.

## Design stance

- The project is not a fork of JGit.
- The project is not affiliated with the Eclipse Foundation.
- JGit internals are encapsulated in implementation packages and must not leak into consuming applications.
- Consuming applications should depend on the public `io.github.carstenartur.jgit.storage.hibernate` facade, not on `org.eclipse.jgit.internal.*` packages.
- The initial Java baseline is Java 17 to align with modern JGit releases and remain usable from Java 21 applications.
- The license is BSD-3-Clause, chosen to stay close to JGit's Eclipse Distribution License / BSD-3-Clause-compatible licensing model.

## Modules

### jgit-storage-hibernate-core

Core database-backed JGit repository implementation.

Responsibilities:

- open a JGit `Repository` backed by Hibernate-managed database tables;
- persist Git pack and reftable files as database rows;
- provide database-backed reflog read/write support;
- keep all JGit DFS/Reftable internal API usage inside implementation packages;
- expose a small stable facade for consuming applications.

### jgit-storage-hibernate-search

Optional search/indexing module.

Responsibilities:

- provide Hibernate Search projection entities for commit metadata, text blobs and file paths;
- provide a small query service for searchable Git history;
- serve as the future home for Java-aware indexing, file-type strategies and optional embedding support.

## Planned capabilities

- H2-backed integration test for repository create/open/write/read/reopen.
- PostgreSQL, MariaDB/MySQL and SQL Server compatibility tests.
- Semantically stable public API.
- Explicit compatibility tests for JGit internal API usage.
- Optional advanced search modules for Java-aware tokenization and embeddings.

## Non-goals

- This project is not a general Git hosting server.
- This project does not replace JGit.
- This project does not define domain-specific workflow models.
- This project should not contain application-specific logic from Audio Analyzer, Taxonomy or Sandbox.

## Expected consumers

- Audio Analyzer / Audioprocessor
- Taxonomy
- Sandbox
- Other applications that need database-backed Git semantics for versioned domain models

## License

BSD-3-Clause. See [LICENSE](LICENSE).
