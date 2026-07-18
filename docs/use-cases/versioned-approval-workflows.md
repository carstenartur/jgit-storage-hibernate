# Use case: auditable approval workflows in the application database

Consider a purchasing platform in which business users publish approval workflows such as:

```yaml
id: purchase-approval
approvalLimit: 10000
approvalMode: dualcontrol
```

The application needs more than a mutable `workflow` table:

- every published definition must be immutable and addressable by commit ID;
- competing changes must be branchable and mergeable with normal Git semantics;
- the repository must live inside the application's PostgreSQL operational boundary rather than in a separately managed `.git` directory;
- readers must never observe a partially written pack or half-published ref transaction;
- support and audit screens must repeatedly answer questions such as “when was dual control introduced?” or “which workflow versions touched `purchase-approval.yaml`?” without walking every reachable Git object for every request.

Plain JGit provides the object model, commits, trees, refs, revision walking and merge algorithms. `jgit-storage-hibernate` keeps those semantics and supplies the database-backed storage, transaction boundaries and rebuildable query projections that JGit itself does not provide.

## What changes compared with plain Git/JGit

| Requirement | Plain Git/JGit | `jgit-storage-hibernate` |
|---|---|---|
| Immutable versions, branches, merges and commit IDs | Built in | Uses the same public JGit APIs and semantics |
| Store packs, Reftables and reflogs as relational rows | Not provided by the standard filesystem and in-memory backends | Core stores them through Hibernate in H2 or PostgreSQL |
| Publish pack files without exposing partial writes | Filesystem backends use their own lock/rename protocol | Pack rows are written as `committed=false`; readers select only committed rows, and publication is a database transaction |
| Atomic ref transactions | Defined by JGit's ref APIs, dependent on the backend | The Hibernate Reftable backend advertises and implements atomic ref transactions |
| Repeated search by message, author, path or indexed content | Requires custom traversal/indexing code | Search supplies a rebuildable Hibernate Search/Lucene projection |
| Operate repository state with database backup, access and schema controls | Requires separate filesystem operations | Repository tables use the application's database operational model |
| Semantic Java history and architecture drift | Not part of Git/JGit | Optional Java Analysis and Architecture modules add derived semantic projections |

Anything in the rightmost column could in principle be implemented by an application around JGit. The point of this project is that the persistence adapter, migration contract, transactional publication protocol, query projections and compatibility tests are already supplied as a library.

## End-to-end application flow

The executable integration test
[`VersionedApprovalWorkflowUseCaseTest`](../../jgit-storage-hibernate-search/src/test/java/io/github/carstenartur/jgit/storage/hibernate/search/VersionedApprovalWorkflowUseCaseTest.java)
contains the complete example. The essential application flow is:

```java
try (HibernateSessionFactoryProvider provider =
        new HibernateSessionFactoryProvider(properties, SearchEntities.annotatedClasses());
    HibernateGitStorage storage =
        new DefaultHibernateRepositoryFactory(provider.getSessionFactory())
            .open(new RepositoryName("approval-workflows"))) {
  Repository repository = storage.repository();

  ObjectId previous = repository.resolve("refs/heads/main");
  ObjectId commitId = commitWorkflow(
      repository,
      previous,
      "Require dual control for high-value purchases",
      """
      id: purchase-approval
      approvalLimit: 10000
      approvalMode: dualcontrol
      """);

  RefUpdate publish = repository.updateRef("refs/heads/main");
  publish.setExpectedOldObjectId(previous != null ? previous : ObjectId.zeroId());
  publish.setNewObjectId(commitId);
  RefUpdate.Result result = publish.update();

  if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FAST_FORWARD) {
    throw new IllegalStateException("Concurrent publication rejected: " + result);
  }

  new CommitIndexer(provider.getSessionFactory(), "approval-workflows")
      .indexCommit(repository, commitId);

  List<GitCommitIndex> hits =
      new GitHistorySearchService(provider.getSessionFactory())
          .searchCommitText("approval-workflows", "dualcontrol", 20);
}
```

`commitWorkflow(...)` is ordinary public JGit code using `ObjectInserter`, `TreeFormatter` and `CommitBuilder`; the storage-specific code is limited to opening the repository through `DefaultHibernateRepositoryFactory`. The test deliberately verifies both the final `main` ref and the indexed business-history query.

The service can now expose application-level operations such as:

```text
GET /workflows/purchase-approval/history?query=dualcontrol
GET /workflows/purchase-approval/versions/{commitId}
POST /workflows/purchase-approval/branches/risk-review/merge
```

The domain API remains application-owned. Git is the authoritative version graph, while the Search table is a disposable read model.

## Database transaction guarantees

The storage implementation uses explicit Hibernate transactions. The guarantees are intentionally stated per operation:

| Operation | Transaction and visibility guarantee |
|---|---|
| Write a pack extension | `HibernatePackOutputStream.flush()` persists or updates the row in one transaction with `committed=false`. Readers do not select it. |
| Publish a pack and replace older packs | `commitPackImpl(...)` deletes replacements and marks all new pack-extension rows committed in one transaction. A runtime failure rolls that transaction back. |
| Roll back an abandoned pack | `rollbackPack(...)` deletes the uncommitted rows transactionally on a best-effort basis without hiding the original JGit exception. |
| Read packs | `listPacks()` and `openFile(...)` filter on `committed=true`, so partially written pack rows are not visible as repository data. |
| Update refs | Core uses JGit's DFS/Reftable implementation, and `HibernateRefDatabase.performsAtomicTransactions()` returns `true`. Reftable files are published through the transactional pack mechanism. |
| Append a queryable reflog entry | `HibernateReflogWriter.log(...)` persists one entry in its own transaction and rolls it back on failure. |
| Upsert a Search projection | `CommitIndexer` performs each projection upsert in its own transaction. The projection remains derived and can be retried or rebuilt. |

This is the concrete ACID benefit described in the original
[JGit discussion #251](https://github.com/eclipse-jgit/jgit/discussions/251): pack publication, ref storage and rollback are implemented with relational database transactions instead of relying on filesystem storage operations.

## Important transaction boundary

The current library does **not** claim one ambient ACID transaction across all of the following:

```text
application entity change
+ Git object/pack write
+ ref publication
+ reflog append
+ Search indexing
```

Core and Search currently open their own Hibernate sessions and transactions for those storage operations. In particular, `ObjectInserter.flush()`, `RefUpdate.update()` and `CommitIndexer.indexCommit(...)` are distinct transactional steps. Supplying the application's `SessionFactory` gives one database configuration and lifecycle, but it does not automatically enlist every step in an already active application transaction.

A production service that must atomically coordinate a domain row with Git publication should therefore use an explicit coordination pattern, for example:

1. write the Git commit and publish its ref;
2. persist the resulting commit ID in the domain transaction;
3. enqueue indexing through an application outbox;
4. make projection processing idempotent and retryable.

Alternatively, keep the Git commit as the authoritative domain record and derive relational read models from it. The project should not be advertised as providing cross-domain atomicity until an explicit ambient-transaction API and corresponding failure tests exist.

## Failure behavior in this use case

- If pack persistence fails, the transaction rolls back or leaves an uncommitted row that normal repository reads ignore.
- If another publisher moves `main`, the expected-old-object-ID check rejects the stale ref update instead of overwriting it silently.
- If Search indexing fails after the ref was published, the Git history is still valid; indexing can be retried because the projection is derived.
- If the application restarts, repository state is reconstructed from committed database rows and the Search projection can be verified or rebuilt.

This separation preserves Git as the source of truth while giving application teams database-native durability, transaction-safe publication and queryable history.