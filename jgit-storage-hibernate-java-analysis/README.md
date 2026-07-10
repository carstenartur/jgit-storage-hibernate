# jgit-storage-hibernate-java-analysis

Optional Java/JDT semantic-history module for `jgit-storage-hibernate`.

## Implemented capabilities

- analyze one `JavaSourceSnapshot` or a complete `JavaProjectSnapshot`
- materialize a shared source tree so JDT can resolve sibling compilation units
- resolve Maven modules, source level, source roots and locally available dependency jars
- preserve unresolved dependency coordinates as diagnostics
- persist symbols, references, binding quality and analysis provenance
- persist projection lifecycle state
- compare project snapshots with `JavaSemanticDiff`
- query symbols and references through `SemanticHistoryQuery`
- run the executable `SemanticHistoryDemo`

## Binding model

The schema is binding-aware from the start. Symbols and references contain syntax-level data plus:

- `BindingStatus`
- raw JDT binding key
- declaration binding key
- declaring/type binding keys
- stable semantic key

Incomplete classpaths are not hidden. Recovered and partial bindings remain visible and queryable.

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

## Semantic diff

```java
List<SemanticChange> changes = new JavaSemanticDiff().compare(before, after);
```

Matching uses declaration/raw binding keys, stable semantic keys and a guarded declaration-shape heuristic. Every change includes confidence and matching evidence.

## Public API boundary

The public API exposes module-owned DTOs and entities. JDT AST and binding objects remain implementation details. The module contains no Eclipse UI, cleanup or quickfix code.
