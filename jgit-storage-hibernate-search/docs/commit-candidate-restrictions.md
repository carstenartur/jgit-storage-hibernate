# Exact commit candidate restrictions

Applications may maintain domain-specific, rebuildable projections beside the generic Git-history projection. A workflow application, for example, can derive branch reachability, workflow identity and typed node metadata from the authoritative Git snapshot while this module continues to own commit metadata, paths and changed-text full-text search.

The two projections must not be queried independently with separate limits and intersected afterward. Either side may discard a valid result before the intersection is computed.

`CommitHistoryQuery.restrictedToObjectIds(...)` provides the correct candidate-plan boundary:

```java
List<String> semanticCandidates = workflowProjection.findMatchingCommitIds(semanticQuery);

CommitHistoryQuery query =
    CommitHistoryQuery.forRepository("audio-workflows")
        .matchingText("wingbeat")
        .authoredBy("researcher@example.org")
        .touchingPath("workflow")
        .restrictedToObjectIds(semanticCandidates)
        .limit(50)
        .build();

List<GitCommitIndex> hits = search.findChanges(query);
```

The exact object IDs are applied as a filter inside the generic query before relevance ranking or newest-first ordering and before the final result limit.

## Semantics

- Omitting `restrictedToObjectIds(...)` searches every indexed commit in the logical repository.
- Passing an empty collection deliberately matches no commits.
- IDs are trimmed, deduplicated and defensively copied.
- Full-text queries apply the IDs as a Hibernate Search `terms` filter, so candidate membership does not alter relevance scores.
- Structured queries apply the IDs through the same HQL selection before newest-first ordering and limiting.
- Repository, author, path, time and candidate predicates are combined with logical `AND`.

The candidate projection remains application-owned and disposable. Git objects and refs remain authoritative, and this generic module does not copy domain semantics or branch membership into `git_commit_index`.

`CommitCandidateRestrictionH2Test` proves full-text and structured composition, filtering before the final limit and explicit empty-set behavior.
