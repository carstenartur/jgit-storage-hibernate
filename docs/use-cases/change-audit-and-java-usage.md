# Use case: investigate changes and semantic impact

A payment platform has hundreds of Git-backed rules and Java services. After a production incident, an investigator asks two questions:

1. **Which changes did Alice make below `services/payments/fraud/` during Q1?**
2. **Which code locations used `ApprovalPolicy` in each released version, including after the class moved packages?**

These are not merely shorter spellings for simple JGit calls. They require query models optimized for repeated application use.

The Search module shifts revision traversal, first-parent diffing, text extraction and index construction to the point where commits enter the projection or an explicit reindex runs. Request-time work is then performed by relational indexes and Hibernate Search/Lucene. Java Analysis performs a comparable transformation for source code: it derives declarations, binding identities and semantic relations that are not present in Git itself.

Every use case below is tied to an executable integration test. The documentation therefore describes behavior that the Maven build verifies rather than illustrative snippets that may drift independently of the implementation.

## Index once, query repeatedly

Without a projection, every request must repeat work proportional to the relevant history:

```text
request
  -> walk commits
  -> inspect author and time
  -> diff each candidate against its parent
  -> inspect paths and contents
  -> collect, sort and paginate
```

With the Search projection:

```text
commit or reindex
  -> walk/diff/normalize once
  -> persist changed paths and structured fields
  -> update Lucene full-text indexes

request
  -> execute indexed author/path/time predicates
  -> optionally execute a Lucene full-text query
```

This changes the operational cost model. Ingestion becomes more expensive, but repeated queries no longer recalculate the same history. The projection can be rebuilt because authoritative Git objects and refs remain in Core.

Standard Git/JGit does not normally expose a general full-text search engine over commit messages, actual changed paths and changed-file contents. Hibernate Search/Lucene therefore adds a capability, not only convenience: an inverted index, analyzers and composable full-text predicates are maintained alongside the relational projection.

## Question 1: author + changed area + time range

The Search module stores the **actual first-parent changed paths** for each indexed commit. A root commit treats all paths as changed. A merge commit is described relative to its first parent. Structured predicates can then be combined:

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

Each result contains the commit ID, message, author, commit time and actual changed paths. Added or modified changed-file content is indexed for full-text search; deleted files remain queryable by path.

A separate full-text question can use the Lucene-backed projection:

```java
List<GitCommitIndex> securityChanges =
    new GitHistorySearchService(sessionFactory)
        .searchCommitText(
            "payment-platform",
            "\"dual control\" OR fraud OR CVE-2026-*",
            100);
```

This is not equivalent to Git's normal path, log or grep operations. The query searches analyzed terms across commit messages, changed paths and selected changed-file contents using a maintained inverted index.

Application endpoints may expose both dimensions:

```text
GET /audit/changes?author=alice@example.com
                   &path=services/payments/fraud/
                   &from=2026-01-01T00:00:00Z
                   &to=2026-03-31T23:59:59Z

GET /audit/search?q="dual control" OR fraud
```

### Integration-test contract

[`CompoundCommitHistoryQueryH2Test`](../../jgit-storage-hibernate-search/src/test/java/io/github/carstenartur/jgit/storage/hibernate/search/CompoundCommitHistoryQueryH2Test.java)
creates commits by different authors in different subsystems and timestamps. It verifies that:

- only the commit matching author, path and interval together is returned;
- unchanged files are not indexed as changed;
- root and first-parent changed-path semantics remain intact;
- path fragments containing `%` or `_` are treated literally.

[`GitHistorySearchH2Test`](../../jgit-storage-hibernate-search/src/test/java/io/github/carstenartur/jgit/storage/hibernate/search/GitHistorySearchH2Test.java)
indexes a commit, closes the JGit repository and then searches its message, changed path and changed-file content. The successful queries after repository closure demonstrate that request-time full-text search uses the maintained projection rather than reopening and traversing the repository.

## Question 2: who used this Java type in each version?

A Git or full-text index can find the token `ApprovalPolicy`, but it cannot by itself answer the semantic question:

- was this occurrence bound to the intended type or merely a same-named class?
- did the type move from `demo.policy` to `demo.risk`?
- which declaration contains the use?
- is the relation a type reference, construction, inheritance or annotation?
- in which commit did each usage exist?
- was the binding full, recovered or partial?

The Java Analysis module performs source parsing and binding analysis when a version is analyzed. It stores or exposes the resulting symbols and references as a versioned semantic projection. Query time then combines the previously derived symbol timeline with per-version usage relations:

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

`RECOVERED` means JDT supplied a recovered binding for the materialized project snapshot. The evidence remains useful but is weaker than `FULL`; `PARTIAL`, `NONE` and `FAILED` remain visible as distinct states.

The query accepted the **old qualified name** but still found usages after the package move because the type is followed as one logical symbol timeline.

### Integration-test contract

[`JavaTypeUsageHistoryQueryTest`](../../jgit-storage-hibernate-java-analysis/src/test/java/io/github/carstenartur/jgit/storage/hibernate/javaanalysis/JavaTypeUsageHistoryQueryTest.java)
analyzes two versions of a Java project, moves `ApprovalPolicy` to another package and adds a second user. It verifies that:

- both commits belong to one logical type history;
- the old qualified name resolves the usages after the move;
- the original and newly added usage locations are reported in the correct versions;
- relation and binding quality remain visible and match the documented `RECOVERED` fixture result.

## Architectural difference

| Capability | Plain Git/JGit/JDT primitives | Indexed project model |
|---|---|---|
| One historical answer | Traverse commits and calculate it on demand | Available, but not the optimization target |
| Repeated author/path/time queries | Repeat traversal, diffs, filtering and ordering or build an application-owned index | Query materialized first-parent changes through relational indexes |
| Full-text history search | Not normally provided as a general indexed query engine | Hibernate Search/Lucene maintains an inverted index over messages, changed paths and selected changed-file contents |
| Request-time cost | Includes repository traversal and tree comparison | Primarily indexed query execution |
| Ingestion cost | Low until a query is executed | Higher because history and text are normalized and indexed when added or rebuilt |
| Java type identity | Not represented in Git; JDT can derive it for one analyzed source state | Versioned binding-aware symbols and references |
| Java use across moves | Reanalyze versions, match declarations and aggregate relations | `JavaTypeUsageHistoryQuery` follows the logical type timeline |
| Evidence quality | Must be designed by the application | Every semantic result carries `BindingStatus` |

The value is therefore not captured by saying that an application could write equivalent loops. A database index, an inverted full-text index and a semantic source-code index are deliberately different data structures with different update and query behavior. Building those structures around JGit/JDT is precisely the functionality this project provides.

## Data, consistency and transaction model

Git objects and refs remain authoritative. Generic commit and Java-analysis projections are derived and rebuildable.

- Indexing may run synchronously after publication, asynchronously or from an outbox.
- Until indexing completes, a projection may lag behind authoritative Git history.
- A failed projection update does not invalidate a successfully published Git commit or ref.
- Reindexing moves the expensive derivation work back to an explicit maintenance operation.
- Core's pack publication and ref transaction guarantees remain separate from Search and Java-analysis projection updates.

The Core transaction contract remains per storage operation:

- uncommitted pack rows are invisible to readers;
- pack publication and replacement deletion commit or roll back together;
- the DFS/Reftable backend advertises atomic ref transactions;
- reflog appends and projection upserts are individually transactional.

Search indexing and Java analysis do not join the object write and ref update into one ambient application-wide transaction. Applications should use expected-old ref checks plus retryable or outbox-driven projection updates when they need coordinated publication.
