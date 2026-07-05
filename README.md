# jgit-storage-hibernate

Hibernate-backed storage backend for JGit repositories.

`jgit-storage-hibernate` provides a database-backed repository implementation for JGit. The goal is to persist Git pack data, references, reflogs and optional searchable history projections in relational databases through Hibernate ORM and Hibernate Search.

The project is intended for applications that need Git semantics without relying on a filesystem-backed `.git` directory, for example versioned domain models, collaborative editors, audit trails and searchable history.

## Status

This repository is being bootstrapped as an independent infrastructure module. The initial implementation consolidates the reusable parts of the existing `carstenartur/jgit` and `sandbox-jgit-storage-hibernate` work, instead of copying the same storage code into every consuming application.

The first technical milestone is a releasable core plus an optional search module:

```text
JGit Repository API
  -> jgit-storage-hibernate-core
       -> Hibernate-backed DFS/Reftable storage adapter
       -> relational database
  -> jgit-storage-hibernate-search
       -> Hibernate Search commit/history projections
       -> Lucene-backed full-text search
```

## Modules

| Module | Purpose | Intended consumers |
|---|---|---|
| `jgit-storage-hibernate-core` | Database-backed JGit repository storage for packs, reftables and queryable reflogs. | Applications that need Git semantics without filesystem-backed `.git` directories. |
| `jgit-storage-hibernate-search` | Optional commit/history projections and full-text search over messages, paths and indexed text content. | Applications that want searchable Git history through Hibernate Search/Lucene. |

This split is intentional. Simple consumers should not have to carry Lucene, Hibernate Search or future Java/JDT-specific analysis dependencies. Java source analysis, embeddings and REST server functionality remain extension candidates and are not part of the core storage artifact.

## Design stance

- The project is not a fork of JGit.
- The project is not affiliated with the Eclipse Foundation.
- JGit internals must be encapsulated behind this module and must not leak into consuming applications.
- Consuming applications should depend on a stable facade, not on `org.eclipse.jgit.internal.*` packages.
- The initial Java baseline is Java 17 to align with modern JGit releases and remain usable from Java 21 applications.
- The license is BSD-3-Clause, chosen to stay close to JGit's Eclipse Distribution License / BSD-3-Clause-compatible licensing model.

## Current capabilities

- Open or create a JGit repository backed by Hibernate-managed database tables.
- Persist Git pack data and reftable data in relational databases.
- Keep newly written pack extensions hidden until JGit commits the pack, avoiding visible half-written pack rows.
- Persist and read queryable reflog entries.
- Support JGit reftable reference updates through the DFS abstraction.
- Index Git commit metadata, paths and text content in the optional search module.
- Provide H2 integration tests for the core and search modules.

## Consuming

See [docs/consuming.md](docs/consuming.md) for Maven repository setup, dependency snippets and the recommended first `audio-analyzer` integration path.

Core dependency:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Optional search dependency:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-search</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

## Release and citation metadata

The repository includes release and citation metadata:

- [CITATION.cff](CITATION.cff)
- [CITATION.md](CITATION.md)
- [.zenodo.json](.zenodo.json)
- [codemeta.json](codemeta.json)
- [docs/release-process.md](docs/release-process.md)

GitHub Packages publishing is prepared through:

- `.github/workflows/publish-snapshot.yml`
- `.github/workflows/release.yml`
- `.github/scripts/release.sh`
- `.github/scripts/update-release-metadata.py`

A DOI can be added after the first GitHub Release has been archived by Zenodo or another DOI provider.

## Non-goals

- This project is not a general Git hosting server.
- This project does not replace JGit.
- This project does not define domain-specific workflow models.
- This project should not contain application-specific logic from Audio Analyzer, Taxonomy or Sandbox.
- This project should not expose JGit internal package types through public APIs.

## Basic usage

```java
Properties properties = new Properties();
properties.put("hibernate.connection.url", "jdbc:h2:mem:demo;DB_CLOSE_DELAY=-1");
properties.put("hibernate.connection.driver_class", "org.h2.Driver");
properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
properties.put("hibernate.hbm2ddl.auto", "update");

try (HibernateSessionFactoryProvider provider = new HibernateSessionFactoryProvider(properties)) {
    HibernateRepositoryFactory factory =
        new DefaultHibernateRepositoryFactory(provider.getSessionFactory());

    try (HibernateGitStorage storage = factory.open(new RepositoryName("demo"))) {
        Repository repository = storage.repository();
        // Use standard JGit APIs here.
    }
}
```

For search projections, create the session factory with the additional search entities:

```java
new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses());
```

## Expected consumers

- Audio Analyzer / Audioprocessor
- Taxonomy
- Sandbox
- Other applications that need database-backed Git semantics for versioned domain models

## License

BSD-3-Clause. See [LICENSE](LICENSE).
