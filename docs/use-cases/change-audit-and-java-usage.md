# Use case: investigate changes and semantic impact

A payment platform has hundreds of Git-backed rules and Java services. After a production incident, an investigator asks two questions:

1. **Which changes did Alice make below `services/payments/fraud/` during Q1?**
2. **Which code locations used `ApprovalPolicy` in each released version, including after the class moved packages?**

Both questions can be implemented by an application using lower-level JGit and JDT APIs. The differentiator is that `jgit-storage-hibernate` supplies the repeatable projection, compound database query, binding-aware identity tracking and executable compatibility tests instead of requiring every consuming application to build them.

## Question 1: author + changed area + time range

Plain JGit gives an application everything needed to write the algorithm:

1. walk reachable commits;
2. inspect each author and timestamp;
3. diff each commit tree against its parent;
4. test every changed path;
5. collect and sort the matching commits.

That is standard Git machinery, but it is not a ready database query. Repeating the traversal for every support, audit or reporting request becomes application-owned indexing and caching code.

The Search module stores the **actual first-parent changed paths** for each commit. A root commit treats all paths as changed. A merge commit is described relative to its first parent. The predicates can then be combined:

```java
CommitHistoryQuery query =
    CommitHistoryQuery.forRepository("payment-platform")
        .authoredBy("alice@example.com")
        .touchingPath("services/payments/fraud/")
        .between(
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-03-31T23:59:59Z"))
        .limit(100)
        .build();

List<GitCommitIndex> changes =
    new GitHistorySearchService(sessionFactory).findChanges(query);
```

Each result contains the commit ID, message, author, commit time, changed paths and indexed content of added or modified changed files. Deleted files remain queryable by their path.

This supports application endpoints such as:

```text
GET /audit/changes?author=alice@example.com
                   &path=services/payments/fraud/
                   &from=2026-01-01T00:00:00Z
                   &to=2026-03-31T23:59:59Z
```

The executable
[`CompoundCommitHistoryQueryH2Test`](../../jgit-storage-hibernate-search/src/test/java/io/github/carstenartur/jgit/storage/hibernate/search/CompoundCommitHistoryQueryH2Test.java)
creates commits by different authors in different subsystems and proves that only the commit satisfying all three predicates is returned. It also proves that an unchanged file is not mislabeled as changed.

## Question 2: who used this Java type in each version?

Text search can find the token `ApprovalPolicy`, but it cannot reliably answer the semantic question:

- was this occurrence bound to the intended type or merely a same-named class?
- did the type move from `demo.policy` to `demo.risk`?
- which declaration contains the use?
- is the relation a type reference, construction, inheritance or annotation?
- in which commit did each usage exist?
- was the binding resolved, recovered or partial?

The Java Analysis module analyzes an ordered series of repository snapshots, follows the logical declaration through `SymbolTimeMachine`, builds a software graph for each commit and returns incoming type-usage relations:

```java
List<JavaProjectAnalysisResult> orderedAnalyses =
    List.of(release_2026_01, release_2026_02, release_2026_03);

JavaTypeUsageHistory usageHistory =
    new JavaTypeUsageHistoryQuery()
        .find(orderedAnalyses, "demo.policy.ApprovalPolicy")
        .orElseThrow();

for (JavaTypeUsageHistory.Version version : usageHistory.versions()) {
  System.out.println(version.commitId() + " -> " + version.type().getQualifiedName());
  for (JavaTypeUsageHistory.UsageSite usage : version.usageSites()) {
    System.out.printf(
        "  %s:%d  %s  %s  binding=%s%n",
        usage.path(),
        usage.line(),
        usage.relation(),
        usage.sourceQualifiedName(),
        usage.bindingStatus());
  }
}
```

The executable example produces the following binding-quality shape:

```text
release-2026-01 -> demo.policy.ApprovalPolicy
  src/main/java/demo/checkout/CheckoutService.java:4  REFERENCES_TYPE  demo.checkout.CheckoutService  binding=RECOVERED

release-2026-02 -> demo.risk.ApprovalPolicy
  src/main/java/demo/batch/BatchApprovalJob.java:4    REFERENCES_TYPE  demo.batch.BatchApprovalJob     binding=RECOVERED
  src/main/java/demo/checkout/CheckoutService.java:4  REFERENCES_TYPE  demo.checkout.CheckoutService  binding=RECOVERED
```

`RECOVERED` means JDT supplied a recovered binding for the materialized project snapshot. The result remains useful for following the logical type, but it is deliberately not presented as equally strong as `FULL`, which represents a non-recovered binding. `PARTIAL`, `NONE` and `FAILED` remain visible as still weaker evidence.

The query accepted the **old qualified name** but still found usages after the package move because the type is followed as one logical symbol timeline.

The executable
[`JavaTypeUsageHistoryQueryTest`](../../jgit-storage-hibernate-java-analysis/src/test/java/io/github/carstenartur/jgit/storage/hibernate/javaanalysis/JavaTypeUsageHistoryQueryTest.java)
analyzes two versions, moves the class, adds a new user and verifies both versions' code locations and the documented recovered binding status.

## Why these are stronger library use cases

| Question | With plain Git/JGit/JDT | With this project |
|---|---|---|
| What did one author change? | Rev-walk and filter author metadata | Query the reusable commit projection |
| What changed in one subsystem? | Diff every candidate commit and inspect paths | Query first-parent `changedPaths` |
| What did one author change in one subsystem during one interval? | Compose traversal, diffing, filtering, ordering and caching in the application | One `CommitHistoryQuery` combines all predicates |
| Where is a Java class used now? | Parse sources and resolve bindings for one checkout | Query incoming semantic graph edges |
| Where was that class used across releases after moves or renames? | Recreate each version, analyze it, match logical identities and aggregate results | `JavaTypeUsageHistoryQuery` combines symbol timelines and per-version graphs |
| How trustworthy is each Java result? | Design and expose a binding-quality model | Every usage carries `BindingStatus` |

The claim is not that these questions are mathematically impossible with JGit or JDT. The claim is that the library provides the storage protocol, projections, composable query API, semantic identity model and regression tests as one maintained integration.

## Data and transaction model

Git objects and refs remain authoritative. Generic commit and Java-analysis tables are derived projections and can be rebuilt.

The Core transaction contract remains per storage operation:

- uncommitted pack rows are invisible to readers;
- pack publication and replacement deletion commit or roll back together;
- the DFS/Reftable backend advertises atomic ref transactions;
- reflog appends and projection upserts are individually transactional.

Search indexing and Java analysis do not join the object write and ref update into one ambient application-wide transaction. Applications should use expected-old ref checks plus retryable or outbox-driven projection updates when they need coordinated publication.
