# SPIKE-0001: Extract Hibernate storage from JGit fork

## Goal

Determine whether the Hibernate-backed storage code from `carstenartur/jgit` can be consolidated into this standalone repository without copying the same implementation into Audio Analyzer, Taxonomy or Sandbox.

## Scope

The spike should extract or reimplement the minimum viable storage path:

```text
HibernateRepository
HibernateObjDatabase
HibernateRefDatabase
HibernateReflogReader
HibernateReflogWriter
Hibernate entities for packs, refs, reflogs and optional projections
```

The first target database is H2. PostgreSQL and other databases can follow after the basic repository lifecycle works.

## Acceptance criteria

- The project builds with `mvn verify`.
- The implementation depends on a released JGit artifact, not on a checked-in JGit fork, unless the spike proves this impossible.
- A test can create and reopen a repository backed by H2.
- A test can write and read at least one blob, tree and commit through JGit APIs.
- A test can update a branch reference and read the same branch after reopening the repository.
- The implementation clearly documents every import from `org.eclipse.jgit.internal.*`.
- Public classes do not expose JGit internal types.
- The result states whether a minimal fork remains necessary.

## Out of scope

- Full Git server implementation.
- Application-specific workflow DSLs.
- Audio Analyzer integration.
- Web editor or collaboration features.
- Production hardening for all database vendors.

## Deliverables

- Minimal implementation or proof of blocker.
- H2-backed tests.
- Updated ADR if a JGit fork is unavoidable.
- List of internal JGit APIs used.
- Follow-up issues for database portability and Hibernate Search projections.
