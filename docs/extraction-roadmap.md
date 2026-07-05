# Extraction roadmap

This document tracks the consolidation from the source projects into `jgit-storage-hibernate`.

## Sources considered

- `carstenartur/jgit`: original Hibernate storage work in a JGit fork.
- `carstenartur/sandbox`: `sandbox-jgit-storage-hibernate`, including storage and search experiments.
- `carstenartur/Taxonomy`: application-level database-backed JGit usage and simplified DSL repository adapter.

## Extracted now

### Core module

`jgit-storage-hibernate-core` contains:

- public facade: `HibernateRepositories`, `HibernateGitStorage`, `RepositoryName`;
- internal Hibernate-backed `DfsRepository` implementation;
- internal Hibernate-backed `DfsObjDatabase` implementation;
- internal reftable-based ref database adapter;
- persistent reflog entity, reader and writer;
- core Hibernate entity registration helper;
- H2 configuration smoke test.

### Search module

`jgit-storage-hibernate-search` contains:

- optional Hibernate Search projection entities for commits, blobs and paths;
- a small query API for commit and blob search;
- a configuration helper for registering search projection entities.

## Deliberately not moved into core

The following Sandbox capabilities are intentionally not part of `jgit-storage-hibernate-core`:

- Java-aware file type strategies;
- ECJ/JDT parsing helpers;
- semantic search helpers;
- web application/server endpoints;
- application-specific DSL repositories.

These are optional history-analysis capabilities and should stay out of the releaseable core artifact.

## Recommended next extraction steps

1. Add an H2 repository lifecycle integration test that writes and reads a blob, tree and commit through JGit APIs.
2. Add a database vendor test matrix for PostgreSQL, MariaDB/MySQL and SQL Server.
3. Migrate file-type strategies into the search module if they remain generic enough.
4. Add a compatibility test that fails if public code exposes `org.eclipse.jgit.internal.*` types.
5. Add a JGit upgrade smoke test to reveal DFS/Reftable internal API breakage early.

## Architecture rule

Only implementation packages in this repository may import JGit internal packages. Consuming applications must depend on the public facade and must not import `org.eclipse.jgit.internal.*`.
