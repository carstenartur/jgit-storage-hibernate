# Preserve and advance Git ancestry between logical repositories

`DefaultHibernateRepositoryFactory` can provision selected refs in another logical repository and later advance them while preserving original Git object IDs and the commit graph.

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

An initial clone requires a target with no refs and always uses `CREATE_ONLY`.

## Advance a tracking ref by fast-forward

```java
RepositoryTransferResult fetched =
    repositories.transfer(
        RepositoryTransferRequest.incrementalFetch(
            central,
            workspace,
            List.of(
                new RefTransferSpec(
                    "refs/heads/main",
                    "refs/heads/upstream/main")),
            TargetRefPolicy.FAST_FORWARD_ONLY));
```

The target repository must already exist. The operation captures both source and target tips, transfers only newly reachable objects, verifies connectivity and then atomically updates the target ref. A divergent target is rejected.

Every target ref whose object also exists in the source is used as a known-history boundary. The source walk therefore stops at objects already reachable in the target instead of rescanning the complete history. A target-side existence check additionally reuses canonical objects left by an interrupted or rejected earlier attempt.

Repeating the same request after it succeeded returns `noOp() == true`, with zero newly transferred objects and bytes.

## Compare-and-set for application-controlled synchronization

Use `COMPARE_AND_SET` when the application has displayed or persisted a known target tip and must reject a stale decision:

```java
ObjectId expectedWorkspaceTip = ...;

RepositoryTransferResult updated =
    repositories.transfer(
        RepositoryTransferRequest.incrementalFetch(
            central,
            workspace,
            List.of(
                new RefTransferSpec(
                    "refs/heads/main",
                    "refs/heads/upstream/main",
                    expectedWorkspaceTip)),
            TargetRefPolicy.COMPARE_AND_SET));
```

The target must still equal `expectedWorkspaceTip`, and a changed commit-valued ref must be a fast-forward. An expected `ObjectId.zeroId()` can guard creation of a missing ref. If a previous attempt already published the desired source ID, retry is accepted as a no-op even though the original expected value is now stale.

## Explicit force update

`FORCE` is the only policy that permits divergence:

```java
RepositoryTransferRequest forced =
    RepositoryTransferRequest.incrementalFetch(
        central,
        workspace,
        List.of(
            new RefTransferSpec(
                "refs/heads/main",
                "refs/heads/upstream/main",
                expectedWorkspaceTip)),
        TargetRefPolicy.FORCE);
```

Supplying an expected target ID is strongly recommended. It proves that the caller is replacing the value it actually reviewed. Even without an explicit expected ID, publication still uses the target value captured at operation start as the command old ID, so a concurrent writer is never silently overwritten.

## Create an additional tracking ref

`INCREMENTAL_FETCH + CREATE_ONLY` can add a new branch or tag to a repository that already has unrelated refs:

```java
RepositoryTransferRequest addTrackingTag =
    RepositoryTransferRequest.incrementalFetch(
        central,
        workspace,
        List.of(
            new RefTransferSpec(
                "refs/tags/v1.1.0",
                "refs/tags/upstream-v1.1.0")),
        TargetRefPolicy.CREATE_ONLY);
```

A different existing target value is rejected; an identical value is an idempotent no-op.

## Atomicity and concurrency contract

The service resolves every source ref exactly once before opening the target. Incremental operations also resolve every target ref once before copying. Those IDs form the operation snapshot.

Objects are streamed into the target and flushed. When connectivity verification is enabled, the target graph is traversed from every captured source root. Only then are all changed refs submitted in one atomic `BatchRefUpdate`.

The captured target IDs become each command's expected old IDs. Consequently, a concurrent target update after validation causes publication to fail rather than being overwritten. A multi-ref request succeeds as a unit or leaves all target refs unchanged.

`FAST_FORWARD_ONLY` and `COMPARE_AND_SET` require commit-valued changed refs. Use `CREATE_ONLY` for new tags and `FORCE` only when replacing a non-commit or intentionally divergent ref.

## Failure and retry

A failure before ref publication leaves no newly visible target history. If object persistence completed but a ref policy or atomic publication failed, unreachable target objects may remain. Repeating the request is safe: existing canonical objects are reused and the policy is evaluated again against the current target refs.

Deleting the target through `HibernateRepositoryFactory.deleteRepository(...)` removes both visible and unreachable target-owned storage.

## Result and audit evidence

Source reflogs are repository-local operational history and are not copied. `RepositoryTransferResult` is the authoritative transfer evidence:

- source and target repository names;
- exact captured source and final target object IDs per ref;
- objects visited and transferred;
- canonical content bytes transferred;
- whether the target repository was created;
- whether the operation was a complete no-op.

The transfer configures a neutral JGit ref-update identity. Atomic integration with the dedicated queryable Hibernate reflog and pluggable security/projection completion hooks remains tracked in issue #236.

## Verified database and restart matrix

The same portable contract is run on every Core database after applying the packaged Flyway stream and starting Hibernate with `hbm2ddl.auto=validate`.

| Database | Restart boundary | Verified transfer sequence |
|---|---|---|
| H2 | file-backed database plus a new `SessionFactory` for every phase | initial two-ref clone, reopen, incremental CAS fetch, no-op retry, source/target deletion isolation |
| HSQLDB | file-backed database, explicit `SHUTDOWN`, engine reopen and new `SessionFactory` | the same sequence across real engine restarts |
| PostgreSQL | Testcontainers database and a new `SessionFactory` for every phase | the same sequence against the PostgreSQL migration stream |
| Microsoft SQL Server | Testcontainers database and a new `SessionFactory` for every phase | the same sequence against the SQL Server migration stream |

For every database the contract additionally verifies:

- the target branch has the exact source commit ID;
- merge parent order and all reachable commit IDs remain intact;
- the annotated target tag keeps its tag-object ID and original commit target;
- selected file contents remain readable after every reopen;
- deleting one target does not change source or another target;
- deleting source does not make the surviving target unreadable;
- `git_repository_lifecycle`, `git_repository_lock`, `git_packs` and `git_reflog` rows are scoped and removed only for the deleted `RepositoryName`.

This matrix is a correctness/restart contract, not a large-history performance claim.

## Current limitations

The implemented API provides bounded initial clone and incremental selected-ref transfer, but it does not yet provide:

- pack negotiation or a backend-native pack-copy optimization;
- automatic branch discovery or Git refspec wildcard expansion;
- shallow or partial clone semantics;
- dedicated queryable Hibernate reflog rows for a multi-ref transfer;
- application security/authorization or projection-completion hooks;
- retained large-history, failure-injection and concurrent-writer/repack evidence;
- automatic consumer catalog or UI integration.

See [ADR-0002](../adrs/0002-logical-repository-transfer.md) for the architecture and atomicity decision.
