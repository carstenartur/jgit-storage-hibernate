# Semantic History Query Cookbook

This cookbook shows how to answer semantic history questions that are hard or impossible to solve with line-oriented Git text search alone.

## Recipe 1: Find All Renamed Methods Between Two Commits

Use semantic diff filtering to isolate rename operations.

```java
SemanticHistoryQuery query = new SemanticHistoryQuery(before);
List<SemanticChange> renamed =
    query.changesOfKind(after, SemanticChangeKind.RENAMED);
```

Expected output: a list of semantic changes whose `before` and `after` declarations represent the same method identity but different names.

## Recipe 2: Find Methods That Moved to Different Packages

Detect moves and then narrow the results to methods.

```java
SemanticHistoryQuery query = new SemanticHistoryQuery(before);
List<JavaSymbolIndex> movedMethods = query.movedSymbols(after).stream()
    .filter(symbol -> symbol.getSymbolKind() == JavaSymbolKind.METHOD)
    .toList();
```

Expected output: methods that still exist after the change but now live in another file or package path.

## Recipe 3: Find All Callers Affected by a Signature Change

Combine diff results with the software graph to discover impacted callers.

```java
SemanticHistoryQuery query = new SemanticHistoryQuery(before);
JavaSoftwareGraph graph = JavaSoftwareGraph.from(after);
String semanticKey = query.changesOfKind(after, SemanticChangeKind.SIGNATURE_CHANGED).stream()
    .map(change -> change.after().getStableSemanticKey())
    .findFirst()
    .orElseThrow();
Set<String> impacted = graph.transitiveImpact(semanticKey, 3);
```

Expected output: stable semantic keys for direct and transitive callers that need review after the signature change.

## Recipe 4: Track a Symbol's History Across Multiple Commits

Build timelines from ordered analysis results, then locate one symbol's history.

```java
SymbolTimeMachine machine = new SymbolTimeMachine();
List<SymbolTimeline> timelines = machine.build(orderedResults);
SymbolTimeline timeline = machine.find(timelines, identity);
```

Expected output: a timeline of semantic states and changes for one logical symbol across many commits.

## Recipe 5: Detect Architecture Drift

Use the architecture module when semantic history needs to be compared against architectural rules.

```java
// See the architecture module for ArchitectureDriftEngine usage.
ArchitectureDriftEngine engine = new ArchitectureDriftEngine();
```

Expected output: architecture-level drift findings that complement commit-by-commit semantic history.

## Not Answerable by Git Text Search

- Whether a method was renamed even though the implementation stayed semantically identical.
- Which declarations moved across files or packages while preserving their logical identity.
- Which callers are transitively impacted by a changed method signature.
- How unresolved or recovered bindings changed between commits.
- Whether a symbol's semantic timeline spans multiple renames, moves, and modifier changes.
