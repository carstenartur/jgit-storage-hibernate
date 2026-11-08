# Ranked scroll memory: supported integration review

Issue [#230](https://github.com/carstenartur/jgit-storage-hibernate/issues/230) remains open because the indexed export does not yet satisfy its bounded-memory acceptance criterion. The retained 10k/100k/1m PostgreSQL and SQL Server evidence is useful for ordering, cleanup and sampled crossover decisions; finishing a million-entry export within a 2 GiB process budget does not establish constant retained cursor memory.

This review does not change production code, add a paging API or relabel any measurement. It clarifies the concrete implementation boundary and checks whether the newly published Hibernate Search maintenance release already removes it.

## Public access exists, but is not the complete execution context

The pinned Hibernate Search **8.4.0.Final** backend exposes public scope-reader access, a native-sort accessor and predicate conversion through a public Lucene SPI. A fixed-reader `searchAfter` traversal is therefore technically possible. The missing implementation cannot accurately be described as an absence of public reader access.

| Available entry point | Relevant boundary |
| --- | --- |
| `LuceneIndexScope.openIndexReader(Set<String>)` | Selects routed shards and retains a reader; the documented reader exposes all tenants. |
| `LuceneSearchQuery.luceneSort()` | Exposes the native sort, not the fully configured query/searcher/projection execution context. |
| `LuceneMigrationUtils.toLuceneQuery(SearchPredicate)` | Converts the supplied predicate using a context without a session; it does not supply the implicit tenant/session filter. |
| Lucene `IndexSearcher.searchAfter(...)` | Can continue on the same reader with bounded result pages, provided the complete query, configured scoring and projection semantics are preserved. |

Signatures and predicate-conversion bytecode were checked against the installed 8.4.0.Final backend JAR, SHA-256 `eb75f5466c29bdc80d45fa554cff3178d52a7f45a9c0cca4568c325f4c78a243`. See the [Hibernate Search reader documentation](https://docs.hibernate.org/search/8.4/reference/en-US/html_single/), [Lucene migration SPI](https://docs.hibernate.org/stable/search/api/org/hibernate/search/backend/lucene/search/spi/LuceneMigrationUtils.html) and [Lucene 9.12.3 IndexSearcher](https://lucene.apache.org/core/9_12_3/core/org/apache/lucene/search/IndexSearcher.html).

The inspected public backend, index-manager, scope and query interfaces do not expose the active scoring `Similarity` or the complete configured searcher. `GitTextAnalysis.configure` accepts user-provided `LuceneAnalysisConfigurer` instances, which can configure a different Similarity. A fresh searcher using default BM25 could therefore change the existing score/time/object-ID ordering. Re-running arbitrary configurers to guess their state is not equivalent to using the actual configured instance.

The all-tenant reader and the session-free predicate conversion also do not preserve implicit tenant isolation by themselves. Routing selects shards and does not replace those filters. A native compact-hit decoder would additionally need to preserve projectable null values and full `Instant` precision; decoding a timestamp solely from its millisecond sort value would lose projection precision.

## The 8.4.1 maintenance release retains the expanding scroll page

The official [8.4 release page](https://hibernate.org/search/releases/8.4/) lists **8.4.1.Final, released 2026-11-02**. The two relevant source files fetched from the official 8.4.0.Final and 8.4.1.Final tags on 2026-11-08 are byte-identical:

| Source | SHA-256, identical in both tags |
| --- | --- |
| [`LuceneSearchScrollImpl.java`](https://github.com/hibernate/hibernate-search/blob/8.4.1.Final/backend/lucene/src/main/java/org/hibernate/search/backend/lucene/search/query/impl/LuceneSearchScrollImpl.java) | `07fa3ce01b09e3da4baa71f5e9fd927193d47b122bbc97e3c7ab3634b1346fdf` |
| [`LuceneSearcher.java`](https://github.com/hibernate/hibernate-search/blob/8.4.1.Final/backend/lucene/src/main/java/org/hibernate/search/backend/lucene/work/impl/LuceneSearcher.java) | `b03dac123a411c969a4f4e36bb3aed4fe861c5d0e9ede67f415fd88c705e506d` |

The scroll still initially requests four chunks and doubles its internal page limit as traversal advances. It submits the increasing page request with the existing offset rather than retaining only the current `searchAfter` page. An upgrade to 8.4.1 alone therefore does not remove the specific expanding-page behavior measured here. This is a source comparison, not a new benchmark of 8.4.1 or a claim about every change in that release.

## Completion path and required regression tests

The narrowest contract-preserving route is a supported Hibernate Search fix/backport that performs bounded continuation inside its existing query execution context. A supported hook exposing the complete configured query/searcher/projection context would also enable a local adapter. No reflection, implementation-package dependency or default-Similarity fallback has been added to this project.

Before such a replacement can satisfy #230, tests must prove stable membership and score/time/ID ordering while the live index changes, default and custom Similarity parity, routing and tenant isolation, compact-field/null/Instant parity without entity hydration, bounded retained page state as result count increases, and reader/session/transaction cleanup on normal exhaustion, early close, interruption and injected failure. The dependency versions must remain explicit: [Hibernate's compatibility policy](https://hibernate.org/support/compatibility-policy/) excludes Lucene-specific extensions from its general SPI compatibility promise.

The existing operational guide separately lists the still-requested native query-plan/wait evidence and database-restart trials. Those missing matrix dimensions and the bounded-memory failure remain visible; the completed runs are not being reclassified as full acceptance.
