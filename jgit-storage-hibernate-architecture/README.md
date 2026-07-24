# jgit-storage-hibernate-architecture

Version architecture intent beside the code and produce explainable drift findings by comparing rules and evidence with the observed software graph.

Choose this module when architecture decisions must be reviewable, versioned and enforceable without reducing the result to an unexplained pass/fail gate.

## Dependency

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-architecture</artifactId>
  <version>0.1.9</version>
</dependency>
```

## What it adds

- language-neutral `ArchitectureDslParser` SPI;
- stable architecture elements, relations, rules and evidence;
- semantic DSL diff by stable IDs;
- code-to-architecture mapping through versioned selectors;
- rule evaluation against `JavaSoftwareGraph`;
- deterministic findings with rule, element, code location and evidence provenance;
- Hibernate/Hibernate Search projections for rules, evidence and findings.

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

## Persistence

Register `ArchitectureEntities.annotatedClasses()` together with the Core, Search and Java-analysis entities. Rules, evidence and drift findings are rebuildable projections; the Git history and versioned architecture sources remain authoritative.
