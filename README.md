# jgit-storage-hibernate

Hibernate-backed storage backend for JGit repositories.

`jgit-storage-hibernate` provides a database-backed repository implementation for JGit. The goal is to persist Git pack data, references, reflogs and optional searchable history projections in relational databases through Hibernate ORM and Hibernate Search.

The project is intended for applications that need Git semantics without relying on a filesystem-backed `.git` directory, for example versioned domain models, collaborative editors, audit trails and searchable history.

## Status

This repository is being bootstrapped as an independent infrastructure module. The initial implementation should be extracted and consolidated from the existing `carstenartur/jgit` Hibernate storage work, instead of copying the same storage code into every consuming application.

The first technical milestone is a feasibility spike:

```text
JGit Repository API
  -> dedicated jgit-storage-hibernate facade
  -> Hibernate-backed DFS/Reftable storage adapter
  -> relational database
  -> optional Hibernate Search projections
```

## Design stance

- The project is not a fork of JGit.
- The project is not affiliated with the Eclipse Foundation.
- JGit internals must be encapsulated behind this module and must not leak into consuming applications.
- Consuming applications should depend on a stable facade, not on `org.eclipse.jgit.internal.*` packages.
- The initial Java baseline is Java 17 to align with modern JGit releases and remain usable from Java 21 applications.
- The license is BSD-3-Clause, chosen to stay close to JGit's Eclipse Distribution License / BSD-3-Clause-compatible licensing model.

## Planned capabilities

- Open or create a JGit repository backed by Hibernate-managed database tables.
- Persist Git pack data and reftable data in relational databases.
- Persist and read reflogs.
- Support atomic reference updates through database transactions where technically possible.
- Provide optional Hibernate Search projections for searchable Git history.
- Provide tests for H2 first, then PostgreSQL, MariaDB/MySQL and SQL Server.
- Keep all JGit internal API usage isolated in implementation packages.

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
