# JGit internal API risk

## Summary

`jgit-storage-hibernate` should initially try to build against released JGit artifacts without maintaining a strategic JGit fork. The expected implementation route is a Hibernate-backed repository built around JGit's DFS/Reftable storage abstractions.

This creates a known risk: the relevant extension points are located in packages such as:

```text
org.eclipse.jgit.internal.storage.dfs
org.eclipse.jgit.internal.storage.reftable
```

These packages are implementation-oriented and should be treated as unstable integration points.

## Decision rule

Consuming applications must never depend directly on JGit internals. Only this repository may contain code that imports `org.eclipse.jgit.internal.*`.

If upstream JGit internals remain usable from a normal Maven build, the project should prefer a pinned upstream JGit dependency.

If object storage, reftable storage or atomic reference updates cannot be implemented safely against upstream JGit artifacts, a minimal fork is acceptable only as an implementation detail of this module.

## Required spike

The first implementation spike must answer:

1. Can a Hibernate-backed `Repository` be implemented against a released JGit dependency?
2. Can Git objects, packs and reftables be persisted and read back from H2?
3. Can reference updates be made atomic enough for concurrent writers?
4. Which JGit internal classes are imported?
5. Which imports would break if JGit is upgraded?
6. Can the implementation remain hidden behind a stable public facade?

## Exit criteria

The spike is successful when a test can:

```text
create repository
write blob/tree/commit
update refs/heads/main
close repository
open repository again
read commit and blob back
verify reflog behavior where supported
```

The spike must fail loudly if implementation depends on unacceptably fragile JGit internals or if transaction boundaries cannot be made safe.
