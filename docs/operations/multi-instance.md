# Multi-instance operation

## Ref and pack publication

Each Core storage operation opens a Hibernate transaction. Pack extensions remain invisible until publication, and a normal JGit `RefUpdate` writes the Reftable plus the queryable reflog entry in the same repository-scoped transaction.

The PostgreSQL integration suite opens the same logical repository through two independent Hibernate `SessionFactory` instances. Two updates using the same expected old object ID are released concurrently. Exactly one update must succeed and the other must return `LOCK_FAILURE`; a third fresh persistence context then verifies the winning ref and reflog.

This proves the supported optimistic expected-old-ID update path across independent persistence contexts. It does not turn several application operations into one ambient transaction.

## Cache visibility

A repository instance may retain JGit DFS/Reftable caches. Code that requires an immediately authoritative view after a write performed through another process should refresh or reopen the repository before making a decision based on refs or pack lists.

## Repository deletion

Repository deletion has a stricter operating requirement than ref updates.

`DefaultHibernateRepositoryFactory` prevents deletion while handles opened through the same `SessionFactory` remain active. That lifecycle registry is JVM-local and cannot detect a handle in another process or an independently constructed `SessionFactory`.

For a clustered or multi-process deployment:

1. stop new repository opens and writes at the application boundary;
2. wait for active operations on the logical repository to finish;
3. obtain an application-level distributed/exclusive maintenance lock;
4. perform repository deletion from one node;
5. invalidate or close repository instances and caches on every node;
6. release maintenance mode only after the deletion transaction commits.

Do not run repository deletion concurrently with writers from another process. The `0.1.x` contract does not claim cluster-wide deletion locking.

## Projection consistency

Search indexing, Java analysis and architecture evaluation are separate derived-state operations. A ref may be valid even when a projection update failed. Consumers that require eventual indexing should use retries, an outbox or a rebuild job appropriate to the application.
