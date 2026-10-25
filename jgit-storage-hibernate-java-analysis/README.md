# jgit-storage-hibernate-java-analysis

## Historical file-text search

`JavaFileSearchIndex.build(documents)` builds one immutable, closeable generation of
historical file documents. `JavaFileSearchQuery` combines literal text, field boosts,
exact repository/module/file-type filters and an offset/limit. `JavaFileSearchHit`
returns ranked identity and provenance without copying source text into each hit.
The API exposes no Lucene, Hibernate or JDT implementation types.

```java
try (var index = JavaFileSearchIndex.build(documents)) {
  var query = new JavaFileSearchQuery("repo", "module-a", "java", "createValue",
      Map.of(JavaFileSearchField.METHODS, 1.5f, JavaFileSearchField.FIELDS, 1f), 0, 20);
  List<JavaFileSearchHit> page = index.search(query);
}
```

The caller supplies all historical documents and controls their order. Identity is
`(repositoryName, documentId)`; repeated identities retain the first complete input,
including commit/blob/path/module provenance. Use the blob ID as document ID for
content deduplication, or supply a distinct identity for every commit/path. This
policy does not reconstruct the original ingestion order from Git. Snapshots and
public semantic/source-text facets can be mapped directly into document fields.

The versioned `java-file-facets-v2` profile uses these exact field semantics:

- Qualified names, declared/extended/implemented/referenced types, imports and
  annotations: one keyword for the entire field, dots replaced with spaces, lowercase.
  Newline-joined names remain one term; the caller must not silently split this facet
  into independent field values when preserving existing whole-field semantics.
- Simple class names, methods, fields and method signatures: standard tokenization,
  CamelCase word parts plus original identifiers, then lowercase.
- Documentation and decoded string literals: the identifier pipeline plus English
  stop words. Queries use the same analysis, with disjunction across fields and
  ordinary query terms. Compound identifier graphs retain complete paths:
  `createValue` means `createvalue OR (create AND value)`, while `create value`
  matches either plain term. This follows Hibernate Search's graph-aware match
  behavior. Operator-looking input remains literal text, not a query language.

Exact metadata and whole qualified-name facets must fit 32,766 UTF-8 bytes after
analysis. Documents validate this limit before construction of a generation,
including Unicode lowercase expansion. Oversized values are rejected explicitly;
whole facets are never silently split or truncated. Source path and provenance
strings stored only for returned hits are not subject to this keyword limit.

Metadata filtering precedes ranking and paging. Ties are ordered by document ID.
Pages are bounded to 1,000 hits; search-after skips deep offsets in bounded chunks.
Deep traversal still takes time proportional to skipped matches. Page length is
not an exact total count. Blank and tokenless text produce no hits.

This optional Java module owns the Lucene implementation and declares its Lucene
9.12.3 dependencies explicitly, aligned with Hibernate Search 8.4. The complete
index is memory-resident and grows with the corpus; it never silently truncates
history. Reuse a generation for queries and replace it only after a complete rebuild.
Concurrent reads are supported; close waits for active reads. There are no background
threads, SQL mappings, persistence migrations or automatic dependency downloads.
`SOURCE` uses the public JDT scanner with Java 25 source/compliance and preview disabled,
for indexing and queries alike. It keeps keywords, identifiers, numeric and raw literal
contents, while skipping comments, operators and the `@` punctuation. Identifiers retain
the original spelling plus synonyms split only at lowercase-to-uppercase code-point boundaries,
including supplementary Unicode letters (a correction to the legacy UTF-16 character check);
`XMLParser` and `value2Factory` remain whole. All emitted terms are lowercased. Escapes
inside literals and identifiers remain written escapes. String/character delimiters are
removed, including Unicode-escaped delimiters; text blocks remove all three quotes
(a correction to the former single-quote stripping). Text-block whitespace remains raw.
The explicit token allowlist also retains `_` and restricted identifiers such as `record`,
`sealed` and `yield`. Malformed source uses best-effort recovery with guaranteed scanner
progress; this field is lexical evidence, not a compilation or binding-quality claim.
Oversized emitted terms fail explicitly with `IllegalArgumentException` in both build
and query analysis.

`PATH` uses standard tokenization, CamelCase word parts without the original compound,
then lowercase. Like the existing identifier/text profiles and the legacy path profile,
standard tokenization splits long tokens into chunks of at most 255 characters. Supply PATH explicitly
in the document field map: the provenance `path` string is not automatically searched.

## Declaration structure

`new JavaSourceStructureExtractor().extract(snapshot, configuration)` returns immutable
syntax facets for named classes, interfaces, enums, records and annotation types. Each
declaration retains source coordinates and explicitly written parent types, including
generic spelling and the parent name without type arguments. Imports retain their static
and on-demand flags. Extraction uses the configured source level without a classpath;
problem/error counts must accompany recovered results. `packageName()` preserves package
declarations even when package-info or a type-free source has no symbols. `typeUses()`
provides field, method/constructor parameter, method return and catch types, including
primitive/void and whole array/union syntax. Uses preserve duplicates and AST encounter
order, with method parameters before the return type and body. Coordinates associate
uses with their owning declaration; raw names remove outer generic arguments only,
without binding/import resolution. Local variables, throws clauses and casts are excluded.
The original four-argument structure constructor remains available with empty package/uses.

Use source positions to associate facets with semantic symbols. Existing
`JavaSymbolKind.TYPE` and analysis-result constructors remain unchanged. Enum constants
are now returned as `FIELD` symbols with the same binding/identity conventions as other
fields. The software graph uses declaration structure rather than regular expressions,
resolves bound parent identities first, and keeps unresolved syntax relations partial.
These in-memory facets add no persistence mappings or schema lifecycle requirements.

## Source text for historical search

`new JavaSourceTextExtractor().extract(snapshot, configuration)` extracts Javadoc comments, decoded string
literals and text blocks with source offsets and line numbers. It uses the configured Java source
level without resolving bindings or requiring a classpath. Documentation retains its original
spelling, including delimiters and tags. Module documentation and unattached Javadoc comments are
included; ordinary comments are excluded. The snapshot path identifies module descriptors.

The immutable `JavaSourceText` result includes syntax problem/error counts; recovered extraction
must not be presented as error-free analysis. The extractor supplements `JavaJdtAnalyzer` and
`JavaProjectAnalyzer` without changing their existing result types or persistence contract.
Callers retain each snapshot's repository/commit/blob/path identity and decide how to index the
returned fragments and combine them with semantic analysis.

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
  <version>0.11.5</version>
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
