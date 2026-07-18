# jgit-storage-hibernate-java-analysis

Go beyond line diffs with binding-aware Java symbol history, semantic change detection and transitive impact analysis across Git revisions.

Choose this module when the question is not only *which lines changed?*, but *which declaration changed, where did it move, and which callers, implementations or types are affected?*

## Dependency

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-java-analysis</artifactId>
  <version>0.1.5</version>
</dependency>
```

## What it adds

- analyze one `JavaSourceSnapshot` or a complete `JavaProjectSnapshot`;
- materialize a shared source tree so JDT can resolve sibling compilation units;
- resolve Maven modules, source level, source roots and locally available dependency jars;
- preserve unresolved dependency coordinates as explicit diagnostics;
- persist symbols, references, binding quality, provenance and projection lifecycle state;
- compare project snapshots with `JavaSemanticDiff`;
- build symbol timelines and a versioned software graph;
- query symbols, references and impact through module-owned APIs.

## Binding model

The schema is binding-aware from the start. Symbols and references contain syntax-level data plus:

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

## Public API boundary

The public API exposes module-owned DTOs and entities. JDT AST and binding objects remain implementation details. The module contains no Eclipse UI, cleanup or quick-fix code.
