# jgit-storage-hibernate-java-analysis

Go beyond line diffs with binding-aware Java symbol history, semantic change detection and transitive impact analysis across Git revisions.

Choose this module when the question is not only *which lines changed?*, but *which declaration changed, where did it move, and which callers, implementations or types were affected in each version?*

> [!IMPORTANT]
> The analysis and query APIs are usable, but the module-owned Hibernate persistence contract is **incubating** in the `0.1.x` line. The artifact currently does not ship Flyway migrations, a transactional projection writer/rebuilder or repository-deletion integration for its entity models. Use the returned DTOs in memory, or let the consuming application own any experimental schema and lifecycle. Do not register the entity helper in a production persistence unit configured with `hibernate.hbm2ddl.auto=validate` unless the application supplies and tests that schema itself.

## A question text search does not answer reliably

> Which code locations used `demo.policy.ApprovalPolicy` in every released version, and who uses it after the class moved to `demo.risk`?

A token search can find the name in one checkout. It does not establish that every occurrence binds to the intended class, follow the declaration through a package move, identify the enclosing declaration or report binding quality.

```java
List<JavaProjectAnalysisResult> releases =
    List.of(release_2026_01, release_2026_02, release_2026_03);

JavaTypeUsageHistory history =
    new JavaTypeUsageHistoryQuery()
        .find(releases, "demo.policy.ApprovalPolicy")
        .orElseThrow();

for (JavaTypeUsageHistory.Version version : history.versions()) {
  System.out.println(version.commitId() + " -> " + version.type().getQualifiedName());
  for (JavaTypeUsageHistory.UsageSite usage : version.usageSites()) {
    System.out.printf(
        "%s:%d %s %s binding=%s%n",
        usage.path(),
        usage.line(),
        usage.relation(),
        usage.sourceQualifiedName(),
        usage.bindingStatus());
  }
}
```

`JavaTypeUsageHistoryQuery` combines the existing symbol timeline with one semantic software graph per commit. The caller may supply an old qualified name; the query follows the logical type through moves or renames and returns incoming `REFERENCES_TYPE`, `CONSTRUCTS`, `EXTENDS`, `IMPLEMENTS` and `ANNOTATED_WITH` relations.

The executable [`JavaTypeUsageHistoryQueryTest`](src/test/java/io/github/carstenartur/jgit/storage/hibernate/javaanalysis/JavaTypeUsageHistoryQueryTest.java) moves a class between packages, adds a new user and verifies the usage locations in both versions.

JDT supplies the parsing and binding primitives needed to build this manually. This module supplies the repository-version model, durable identity matching, binding-quality model, graph construction and cross-version query as a maintained integration.

## Dependency

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-java-analysis</artifactId>
  <version>0.10.0</version>
</dependency>
```

## What it adds

- analyze one `JavaSourceSnapshot` or a complete `JavaProjectSnapshot`;
- materialize a shared source tree so JDT can resolve sibling compilation units;
- resolve Maven modules, source level, source roots and locally available dependency jars;
- preserve unresolved dependency coordinates as explicit diagnostics;
- produce symbol, reference, binding-quality, provenance and lifecycle models;
- compare project snapshots with `JavaSemanticDiff`;
- build symbol timelines and a versioned software graph;
- query type usages across versions through `JavaTypeUsageHistoryQuery`;
- calculate direct and transitive impact through module-owned APIs.

The included Jakarta Persistence/Hibernate Search entity classes are an incubating integration surface, not a complete module-owned database deployment contract in `0.1.x`.

## Binding model

The model is binding-aware from the start. Symbols and references contain syntax-level data plus:

- `BindingStatus`;
- raw JDT binding key;
- declaration binding key;
- declaring/type binding keys;
- stable semantic key.

Incomplete classpaths are not hidden. Recovered and partial bindings remain visible and queryable, so callers can distinguish evidence from inference.

## Project analysis

```java
JavaProjectAnalysisResult result =
    new JavaProjectAnalyzer().analyze(projectSnapshot, configuration);
```

`JavaProjectAnalyzer` creates a temporary source tree and adds it to the configured source path. This enables cross-file binding without an Eclipse workspace.

## Maven context

```java
var resolution =
    new MavenJavaAnalysisConfigurationResolver().resolve(repositoryFiles);
```

The resolver is deterministic: it uses artifacts already present in the local Maven repository and reports missing coordinates instead of downloading them implicitly.

## Semantic diff and impact

```java
List<SemanticChange> changes = new JavaSemanticDiff().compare(before, after);
List<SymbolTimeline> timelines =
    new SymbolTimeMachine().build(List.of(before, after));
JavaGraphDelta graphDelta =
    JavaGraphDelta.between(JavaSoftwareGraph.from(before), JavaSoftwareGraph.from(after));
```

Matching uses declaration/raw binding keys, stable semantic keys and a guarded declaration-shape heuristic. Every change includes confidence and matching evidence.

See the [change-audit and Java-usage use case](../docs/use-cases/change-audit-and-java-usage.md) and the [semantic query cookbook](../docs/query-cookbook.md) for complete examples.

## Projection boundary

Java analysis is derived from authoritative Git objects and can be regenerated. The supported `0.1.x` path returns analysis results and graphs to the caller. Analysis of several commits is not one ambient transaction with Git publication.

Applications that persist the incubating entity models must own migrations, idempotent writes, retries, reindexing and repository cleanup until a future module-owned persistence contract is released.

## Public API boundary

The supported analysis API exposes module-owned DTOs. JDT AST and binding objects remain implementation details. The module contains no Eclipse UI, cleanup or quick-fix code. Entity registration helpers are incubating and may evolve before a production persistence contract is declared.
