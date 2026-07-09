# jgit-storage-hibernate-java-analysis

Optional Java/JDT analysis module for `jgit-storage-hibernate`.

This module is intentionally separate from the generic storage and search modules. It parses Java source snapshots, asks JDT Core for bindings, and emits stable Hibernate/Hibernate Search projections for Java declarations and references.

## Scope

The module is for read-only analysis and indexing, not for Eclipse cleanups, quick fixes, UI markers or source rewriting.

It uses:

- Java 21 as source and build baseline
- JDT Core as parser and binding resolver
- own DTOs/entities as public API
- Hibernate ORM / Hibernate Search annotations for persistence and search

It deliberately does not expose JDT AST or binding types from public APIs.

## Binding model

The first schema is binding-aware. Symbols and references store both syntax-level data and semantic binding data:

- binding status: `NONE`, `PARTIAL`, `RECOVERED`, `FULL`, `FAILED`
- raw JDT binding key
- declaration binding key
- declaring type binding key
- type binding key
- stable semantic key computed by this module

This allows incomplete classpaths to be represented explicitly instead of silently degrading the index to text-only search.

## Main entry points

- `JavaJdtAnalyzer` analyzes one `JavaSourceSnapshot`
- `JavaAnalysisConfiguration` describes source level, classpath/sourcepath/modulepath hashes and binding mode
- `JavaAnalysisEntities.annotatedClasses()` returns the entity classes to register with Hibernate

## Current limitations

This is an MVP. It records declarations, imports, type references, annotations, constructor calls, method calls and field accesses. It does not yet build a full call graph, override graph or Maven/Gradle classpath resolver.
