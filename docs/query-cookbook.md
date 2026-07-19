# History Query Cookbook

This cookbook shows how to answer repeatable history questions with the generic Search projection and binding-aware Java analysis.

## Recipe 1: text, author, subsystem and time range in one query

The separate questions can be answered with ordinary Git/JGit primitives. The useful application query combines them without walking and diffing the repository again for every request or intersecting independently truncated result lists.

```java
CommitHistoryQuery query =
    CommitHistoryQuery.forRepository("payment-platform")
        .matchingText("threshold")
        .authoredBy("alice@example.com")
        .touchingPath("services payments fraud")
        .between(
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-03-31T23:59:59Z"))
        .limit(100)
        .build();

List<GitCommitIndex> changes =
    new GitHistorySearchService(sessionFactory).findChanges(query);
```

Expected output: relevance-ranked commits matching the full-text expression that were authored by Alice, actually changed an analyzed fraud-subsystem path and fall inside the inclusive interval.

All supplied predicates execute in one bounded Hibernate Search query. Omitting `matchingText(...)` retains the relational newest-first query and literal, case-insensitive path-fragment behavior.

`changedPaths` uses first-parent diff semantics. A root commit treats every path as changed; a merge commit is compared with its first parent. Branch reachability is not duplicated into the generic projection and remains a JGit or application-level access/lifecycle decision.

## Recipe 2: full-text search over changed material

```java
List<GitCommitIndex> changes =
    search.searchCommitText("payment-platform", "dualcontrol OR threshold", 50);
```

Expected output: commits whose messages, changed paths or indexed added/modified changed-file content match the query. This convenience method delegates to the same compound query implementation used by Recipe 1.

## Recipe 3: find all renamed methods between two commits

Use semantic diff filtering to isolate rename operations.

```java
SemanticHistoryQuery query = new SemanticHistoryQuery(before);
List<SemanticChange> renamed =
    query.changesOfKind(after, SemanticChangeKind.RENAMED);
```

Expected output: semantic changes whose `before` and `after` declarations represent the same method identity but different names.

## Recipe 4: find methods that moved to different packages

Detect moves and then narrow the results to methods.

```java
SemanticHistoryQuery query = new SemanticHistoryQuery(before);
List<JavaSymbolIndex> movedMethods =
    query.movedSymbols(after).stream()
        .filter(symbol -> symbol.getSymbolKind() == JavaSymbolKind.METHOD)
        .toList();
```

Expected output: methods that still exist after the change but now live in another file or package path.

## Recipe 5: find callers affected by a signature change

Combine diff results with the software graph to discover impacted callers.

```java
SemanticHistoryQuery query = new SemanticHistoryQuery(before);
JavaSoftwareGraph graph = JavaSoftwareGraph.from(after);
String semanticKey =
    query.changesOfKind(after, SemanticChangeKind.SIGNATURE_CHANGED).stream()
        .map(change -> change.after().getStableSemanticKey())
        .findFirst()
        .orElseThrow();
Set<String> impacted = graph.transitiveImpact(semanticKey, 3);
```

Expected output: stable semantic keys for direct and transitive callers that need review after the signature change.

## Recipe 6: track a symbol across multiple commits

Build timelines from ordered analysis results, then locate one symbol's history.

```java
SymbolTimeMachine machine = new SymbolTimeMachine();
List<SymbolTimeline> timelines = machine.build(orderedResults);
SymbolTimeline timeline = machine.find(timelines, identity);
```

Expected output: a timeline of semantic states and changes for one logical symbol across many commits.

## Recipe 7: find every code location using one type in every version

```java
JavaTypeUsageHistory history =
    new JavaTypeUsageHistoryQuery()
        .find(orderedResults, "demo.policy.ApprovalPolicy")
        .orElseThrow();

for (JavaTypeUsageHistory.Version version : history.versions()) {
  for (JavaTypeUsageHistory.UsageSite usage : version.usageSites()) {
    System.out.printf(
        "%s %s:%d %s %s%n",
        version.commitId(),
        usage.path(),
        usage.line(),
        usage.relation(),
        usage.bindingStatus());
  }
}
```

Expected output: binding-aware type references, constructions, inheritance relations and annotation uses grouped by Git commit. The logical history continues after a move or rename, so an old qualified name can lead to the new declaration and its users.

## Recipe 8: detect architecture drift

Use the architecture module when semantic history needs to be compared against architectural rules.

```java
ArchitectureDriftEngine engine = new ArchitectureDriftEngine();
ArchitectureDriftReport report = engine.evaluate(intent, observedGraph);
```

Expected output: architecture-level drift findings that complement commit-by-commit semantic history.

## Questions not answered reliably by line-oriented Git text search alone

- Whether a method was renamed even though the implementation stayed semantically identical.
- Which declarations moved across files or packages while preserving logical identity.
- Which callers are transitively impacted by a changed method signature.
- Whether an occurrence is bound to the intended same-named Java type.
- Which code locations used that logical type in each version after package moves.
- How unresolved or recovered bindings changed between commits.
- Whether a symbol's semantic timeline spans several renames, moves and modifier changes.

See the complete [change-audit and Java-usage use case](use-cases/change-audit-and-java-usage.md) for a comparison with the manual JGit/JDT algorithms.
