# Preserve Git ancestry when creating a logical clone or fork

`DefaultHibernateRepositoryFactory` can provision selected refs in another logical repository while preserving the original Git object IDs and commit graph.

This is different from reading a file from the source and committing the same content into the target. A content-copy commit has a new object ID and no shared ancestry. A logical repository transfer keeps the source commit itself, so later merge-base, fetch and merge operations start from real Git ancestry.

## Initial selected-ref clone

```java
RepositoryName central = new RepositoryName("central/product-taxonomy");
RepositoryName workspace = new RepositoryName("workspace/alice/product-taxonomy");

DefaultHibernateRepositoryFactory repositories =
    new DefaultHibernateRepositoryFactory(sessionFactory);

RepositoryTransferResult result =
    repositories.transfer(
        RepositoryTransferRequest.initialClone(
            central,
            workspace,
            List.of(
                new RefTransferSpec(
                    "refs/heads/main",
                    "refs/heads/draft"),
                new RefTransferSpec(
                    "refs/tags/v1.0.0",
                    "refs/tags/upstream-v1.0.0"))));

ObjectId inheritedHead =
    result.refs().get("refs/heads/draft").targetObjectId();
```

After a successful call:

- `workspace` owns an independent copy of every object reachable from the selected source refs;
- `refs/heads/draft` points to the exact commit that `central/refs/heads/main` pointed to when the operation started;
- commit parents, trees, blobs and annotated tags retain their original IDs;
- closing or deleting the source repository does not affect the target;
- application and Hibernate Search projections are not copied and should be rebuilt from the returned target IDs.

## Safety contract

The phase-1 operation is deliberately restricted to:

```text
mode:              INITIAL_CLONE
target ref policy: CREATE_ONLY
source namespaces: refs/heads/* and refs/tags/*
target state:      no existing refs
```

The service resolves every source ref once before opening the target. Objects are streamed into the target and flushed, the target graph is traversed when connectivity verification is enabled, and only then are all target refs published atomically.

The service never silently force-updates a target ref. A non-empty target fails before object transfer.

## Failure and retry

A failure before ref publication leaves no visible target history. If object persistence completed but ref publication failed, unreachable target objects may remain. Repeating the same create-only transfer is safe: existing canonical objects are reused and the refs are attempted again.

Deleting the target through `HibernateRepositoryFactory.deleteRepository(...)` removes both visible and unreachable target-owned storage.

## Reflog and audit evidence

Source reflogs are repository-local operational history and are not copied. During phase 1, `RepositoryTransferResult` is the authoritative operation evidence:

- source and target repository names;
- exact source and target object IDs per ref;
- objects visited and transferred;
- canonical content bytes transferred;
- whether the target repository was created.

Atomic integration with the dedicated Hibernate reflog and pluggable security/projection completion hooks remains part of the incremental-transfer work tracked in issue #236.

## Current limitations

The following API values are reserved but intentionally rejected until their full semantics are implemented and tested:

- `INCREMENTAL_FETCH`;
- `FAST_FORWARD_ONLY`;
- `COMPARE_AND_SET`;
- `FORCE`.

Large-history pack negotiation, PostgreSQL/SQL Server/HSQLDB transfer matrices, failure injection, concurrent target writers and consumer adoption are also follow-up phases of issue #236. See [ADR-0002](../adrs/0002-logical-repository-transfer.md) for the architecture and atomicity decision.
