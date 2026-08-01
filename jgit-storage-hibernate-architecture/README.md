# jgit-storage-hibernate-architecture

Version architecture intent beside the code and produce explainable drift findings by comparing rules and evidence with the observed software graph.

Choose this module when architecture decisions must be reviewable, versioned and enforceable without reducing the result to an unexplained pass/fail gate.

> [!IMPORTANT]
> The parser, snapshot, semantic-diff, mapping and drift-evaluation APIs are usable. The Hibernate/Hibernate Search entity layer is **incubating** in the `0.1.x` line: the artifact does not yet ship module-owned Flyway migrations, transactional projection writers/rebuilders or repository-deletion integration. Production applications should keep architecture sources in Git and use the in-memory reports, or explicitly own and test any experimental persistence schema.

## Dependency

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-architecture</artifactId>
  <version>0.1.18</version>
</dependency>
```

## What it adds

- language-neutral `ArchitectureDslParser` SPI;
- stable architecture elements, relations, rules and evidence;
- semantic DSL diff by stable IDs;
- code-to-architecture mapping through versioned selectors;
- rule evaluation against `JavaSoftwareGraph`;
- deterministic findings with rule, element, code location and evidence provenance;
- incubating Jakarta Persistence/Hibernate Search entity models for applications that deliberately own the schema and lifecycle.

## Reference DSL

```text
element ui layer "UI" packagePrefix=com.example.ui
element application layer "Application" packagePrefix=com.example.application
element database layer "Database" packagePrefix=com.example.persistence

relation ui-app depends ui -> application

rule no-ui-db forbid REFERENCES_TYPE from ui to database \
  evidence=adr-7 reason="UI must not access persistence directly"

rule app-db require CALLS from application to database \
  evidence=adr-12 reason="Persistence access is owned by the application layer"

evidence adr-7 for no-ui-db kind=ADR path=docs/adr/0007.md \
  rationale="Layering decision" confidence=1.0
```

The reference parser supports `.architecture` and `.archdsl` files. Other DSLs can implement `ArchitectureDslParser` and produce the same neutral snapshot model.

## Evaluate drift

```java
ArchitectureSnapshot architecture = parser.parse(source).snapshot();
JavaSoftwareGraph codeGraph = JavaSoftwareGraph.from(javaAnalysis);
ArchitectureDriftReport report =
    new ArchitectureDriftEngine().evaluate(architecture, codeGraph);
```

Detected findings include:

- forbidden observed relations;
- missing required relations;
- unmapped code symbols;
- ambiguous element mappings;
- missing referenced evidence;
- evidence that is stale for the analyzed code commit.

## Mapping selectors

Architecture elements can declare:

- `codePattern`: regular expression against stable semantic keys;
- `packagePrefix`: Java package prefix;
- `pathPrefix`: repository source-path prefix.

Exactly one matching architecture element is required for a clean mapping. Zero and multiple matches are explicit findings rather than silent guesses.

## Versioned semantic diff

```java
List<ArchitectureChange> changes =
    new ArchitectureSemanticDiff().compare(oldSnapshot, newSnapshot);
```

Elements, relations, rules and evidence are compared by stable IDs, so text movement and formatting changes do not masquerade as architectural changes.

## Persistence maturity

The authoritative state is the Git history containing the architecture sources. Drift reports and any database rows derived from them are rebuildable projections.

`ArchitectureEntities.annotatedClasses()` exposes incubating entity mappings for controlled experiments. In `0.1.x`, a consuming application that registers them must also provide its own versioned migrations, persistence/upsert logic, reindex procedure and repository cleanup. The helper is not yet part of the production schema contract documented for Core and Search.
