# jgit-storage-hibernate-search

Add a rebuildable relational and Lucene query model over Git history.

The module moves revision traversal, first-parent diffing, changed-text extraction and index construction from every request to commit-ingestion or explicit reindex time. Request-time work is then handled by relational indexes and Hibernate Search/Lucene.

## Why indexing matters

Without a projection, repeated history requests repeatedly traverse commits and compare trees:

```text
query -> RevWalk -> parent diff -> path/content filtering -> result
query -> RevWalk -> parent diff -> path/content filtering -> result
```

With this module:

```text
commit/reindex -> RevWalk + first-parent diff + text extraction + index update
query          -> relational predicates and/or Lucene full-text search
query          -> relational predicates and/or Lucene full-text search
```

This is a deliberate read-optimized architecture. Indexing adds work when a commit enters the projection, but avoids recalculating the same history for each audit, support, reporting or search request.

Git/JGit does not normally provide a general full-text query engine over commit messages, actual changed paths and changed-file contents. Hibernate Search/Lucene adds analyzers, an inverted index and composable full-text queries over those fields.

## Compound history query

> Which changes mentioning a threshold did Alice make in the fraud subsystem at the end of Q1?

```java
CommitHistoryQuery query =
    CommitHistoryQuery.forRepository("payment-platform")
        .matchingText("threshold")
        .authoredBy("alice@example.com")
        .touchingPath("services payments fraud")
        .between(
            Instant.parse("2026-03-01T00:00:00Z"),
            Instant.parse("2026-03-31T23:59:59Z"))
        .limit(100)
        .build();

List<GitCommitIndex> hits =
    new GitHistorySearchService(sessionFactory).findChanges(query);
```

All supplied predicates are applied in one bounded server-side query. Full-text results retain relevance ordering; repository, author, path and time restrictions are filters. When no full-text expression is present, the existing relational query path remains newest-first and keeps literal, case-insensitive path-fragment matching.

`CommitIndexer` stores paths changed relative to the first parent. Root commits treat every path as changed; merge commits use first-parent semantics. The projection makes text, author, changed-path and time predicates jointly queryable without traversing and diffing history at request time.

This documented use case is executable in
[`CompoundCommitHistoryQueryH2Test`](src/test/java/io/github/carstenartur/jgit/storage/hibernate/search/CompoundCommitHistoryQueryH2Test.java).
The test creates commits by different authors, paths and times, then proves that only the commit satisfying all predicates is returned. It also verifies inclusive time bounds, first-parent changed-path semantics and literal handling of SQL wildcard characters in structured path-only queries.

Branch or ref reachability is intentionally not copied into this generic projection. A consumer that restricts results to one branch must enforce that repository/ref boundary through JGit or an application-owned projection.

## Analysis model

The generic projection deliberately assigns analyzers by field semantics:

- short and full commit messages use the configurable natural-language slot `GitTextAnalysis.NATURAL_LANGUAGE_ANALYZER`;
- changed-file content and the backward-compatible aggregate `changedPaths` field use the fixed language-neutral `standard` analyzer;
- compound path filters target the derived `changedPathTerms` field using the built-in `simple` analyzer;
- each path is also indexed separately as the exact keyword field `changedPathExact`.

The default natural-language slot is Hibernate Search's built-in `default` analyzer, whose Lucene implementation is `StandardAnalyzer`. This preserves the behavior of release 0.1.6: tokenization follows Unicode word boundaries and tokens are lowercased, but words are not stemmed.

The path analyzer splits at non-letter punctuation and lowercases terms. Thus `workflow` matches `workflow.dsl`, and `SERVICES payments` matches path components in `services/payments/...`. Numeric-only path components are not independent terms with this analyzer; use the exact path field or an application-specific projection when numeric identity is significant.

### Selecting a language profile

Stemming is index configuration, not a per-request option. Index-time and query-time analysis must remain compatible, and changing the profile requires recreating or rebuilding the derived Lucene index.

Hibernate Search 8.4 applies a Lucene analysis configurer to the backend. A consumer can replace the built-in `default` analyzer with a language profile while this module protects paths and changed source material through explicit analyzer mappings:

```java
public final class EnglishCommitMessageConfigurer
    implements LuceneAnalysisConfigurer {

  public EnglishCommitMessageConfigurer() {}

  @Override
  public void configure(LuceneAnalysisConfigurationContext context) {
    context
        .analyzer(AnalyzerNames.DEFAULT)
        .custom()
        .tokenizer("standard")
        .tokenFilter("lowercase")
        .tokenFilter("snowballPorter")
        .param("language", "English");
  }
}

Properties properties = new Properties();
GitTextAnalysis.configure(
    properties,
    EnglishCommitMessageConfigurer.class,
    "english-snowball-v1");
```

`GitTextAnalysis.configure(...)` writes a resolvable `class:` reference to `hibernate.search.backend.analysis.configurer` and stores a separate operator-visible profile identity. `GitTextAnalysis.profileId(properties)` reports that identity, or `neutral-standard-v1` when the built-in neutral profile is active. Consumers with a Hibernate Search bean resolver can instead pass a `bean:beanName` reference string.

The configurer is backend-wide. Applications sharing the default backend must therefore assign explicit analyzers to unrelated full-text fields when those fields must not inherit the overridden `default` analyzer. This module already does so for changed source material and paths; repository names, object IDs, exact paths and author emails use keyword semantics.

Stempel is specifically a Polish stemmer. A Polish application may provide an explicit profile using Lucene's optional Stempel analysis module, but the Search artifact does not make Stempel a mandatory dependency or a generic default. Mixed-language repositories should use separate application-owned language fields or a semantic projection rather than one global stemmer.

The executable
[`ChangedPathAnalysisH2Test`](src/test/java/io/github/carstenartur/jgit/storage/hibernate/search/ChangedPathAnalysisH2Test.java)
verifies component matching across punctuation and case. [`ConfigurableGitTextAnalysisH2Test`](src/test/java/io/github/carstenartur/jgit/storage/hibernate/search/ConfigurableGitTextAnalysisH2Test.java) verifies neutral backward behavior, English message stemming, field isolation and profile diagnostics.

## Full-text query

```java
List<GitCommitIndex> hits =
    search.searchCommitText(
        "payment-platform",
        "\"dual control\" OR fraud OR cve",
        50);
```

The query uses terms and a phrase compatible with the configured field analyzers. Identifiers containing punctuation, such as `CVE-2026-1234`, are normally tokenized into analyzed terms; callers needing exact identifier matching should add a dedicated keyword field/analyzer rather than relying on a hyphenated wildcard expression.

Full-text search covers:

- short and full commit messages;
- actual first-parent changed paths;
- indexed content of added or modified changed files.

Deleted files remain represented by path. Large or non-blob content is intentionally not loaded into the text projection.

The full-text index is not a wrapper around `git log` or `git grep`. It is a maintained Lucene inverted index over historical metadata and selected changed content, optimized for repeated search queries.

The executable
[`GitHistorySearchH2Test`](src/test/java/io/github/carstenartur/jgit/storage/hibernate/search/GitHistorySearchH2Test.java)
indexes one commit, closes the JGit repository and then successfully searches the indexed commit message, path and changed-file content through Hibernate Search. Closing the repository before querying ensures the documented request path uses the projection rather than repository traversal.

## What it adds

- materialize repository, object ID, messages, author, commit time and actual changed paths;
- extract selected changed-file text during indexing rather than during every query;
- combine full text, author email, changed path and inclusive time bounds through `CommitHistoryQuery`;
- apply field-specific analysis to path terms and configurable analysis to natural-language messages;
- run full-text queries through Hibernate Search/Lucene;
- retain `findByAuthorEmail`, `findByPath`, `findBetween` and full-text convenience methods;
- share the Core database configuration while keeping Search optional;
- delete and rebuild projections because Git objects and refs remain authoritative;
- provision the projection table through its own versioned Flyway history.

Choose this module when history is an application query workload, not only something inspected occasionally with repository traversal.

## Dependency

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-search</artifactId>
  <version>0.1.8</version>
</dependency>
```

The Search artifact depends on Core. Apply Core migrations first, then Search migrations with its separate history table.

## Registration

```java
List<Class<?>> annotatedClasses = new ArrayList<>();
annotatedClasses.addAll(SearchEntities.annotatedClasses());
annotatedClasses.add(MyApplicationEntity.class);

try (HibernateSessionFactoryProvider provider =
    new HibernateSessionFactoryProvider(properties, annotatedClasses)) {
  CommitIndexer indexer =
      new CommitIndexer(provider.getSessionFactory(), repositoryName);
  GitHistorySearchService search =
      new GitHistorySearchService(provider.getSessionFactory());
}
```

## Update and consistency model

The index can be maintained synchronously after ref publication, asynchronously, or from an application outbox.

- Git/Core remains authoritative.
- The Search projection may temporarily lag behind Git when indexing is asynchronous.
- `CommitIndexer` upserts each `GitCommitIndex` row in an explicit Hibernate transaction.
- Search indexing is not the same transaction as Core pack publication or a JGit ref update.
- A failed index update is retried or rebuilt; it does not invalidate a successfully published commit.
- Reindexing is the explicit operation that pays the derivation cost again after loss, analyzer-profile changes or projection-semantics changes.

## Database ownership

Search owns `git_commit_index` and `jgit_storage_hibernate_search_schema_history`. Domain-specific projections remain in the consuming application even when they share one `SessionFactory`.

## Verification

H2 integration tests exercise the documented query and analysis use cases on every build. With Docker available, Testcontainers additionally starts PostgreSQL 17.10 and verifies Core-plus-Search installation, immutable 0.1.4 fixture adoption, projection persistence and Hibernate validation across a `SessionFactory` restart.

See the [change-audit and Java-usage use case](../docs/use-cases/change-audit-and-java-usage.md) for the complete architectural comparison.
