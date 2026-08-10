# jgit-storage-hibernate-search

Add a rebuildable relational and Lucene query model over Git history.

The module moves revision traversal, first-parent diffing, profile-selected changed-path/content extraction and index construction from every request to commit-ingestion or explicit rebuild time. Request-time work is then handled by relational indexes and Hibernate Search/Lucene.

## Why indexing matters

Without a projection, repeated history requests repeatedly traverse commits and compare trees:

```text
query -> RevWalk -> parent diff -> path/content filtering -> result
query -> RevWalk -> parent diff -> path/content filtering -> result
```

With this module:

```text
commit/rebuild -> RevWalk + first-parent diff + profile-selected extraction + index update
query          -> relational predicates and/or Lucene full-text search
query          -> relational predicates and/or Lucene full-text search
```

This is a deliberate read-optimized architecture. Indexing adds work when a commit enters the projection, but avoids recalculating the same history for each audit, support, reporting or search request.

Git/JGit does not normally provide a general full-text query engine over commit messages, actual changed paths and changed-file contents. Hibernate Search/Lucene adds analyzers, an inverted index and composable full-text queries over those fields.

## Database support

| Database | Search migrations | Automated evidence | Intended use |
|---|---|---|---|
| H2 2.4.x | Yes | Ordinary Maven tests | Tests, demos and disposable development |
| PostgreSQL 17.10 | Yes | Testcontainers migration, restart and query tests | Persistent development, staging and production |
| Microsoft SQL Server 2022 | Yes from 0.1.16 | Testcontainers migration, indexing, rebuild, deletion, query and persistent-Lucene restart tests | Persistent deployments and Sandbox cut-over |
| HSQLDB 2.7.x | No | Core only | Do not enable this Search module on HSQLDB |

Support means the module ships dialect-specific Flyway migrations and real-container evidence for schema validation and public Search behavior. Core database support alone does not imply Search support.

Apply Core migrations first, then the matching Search location with the separate Search history table. SQL Server deployment and copied-projection replacement are documented in [SQL Server Search operations](../docs/operations/sql-server-search.md).

## Compound history query

> Which changes mentioning a threshold did Alice author and the release bot commit in the fraud subsystem at the end of Q1?

```java
CommitHistoryQuery query =
    CommitHistoryQuery.forRepository("payment-platform")
        .matchingText("threshold")
        .authoredBy("alice@example.com")
        .committedBy("release-bot@example.com")
        .touchingPath("services payments fraud")
        .committedBetween(
            Instant.parse("2026-03-01T00:00:00Z"),
            Instant.parse("2026-03-31T23:59:59Z"))
        .offset(0)
        .limit(100)
        .build();

List<GitCommitIndex> hits =
    new GitHistorySearchService(sessionFactory).findChanges(query);
```

All supplied predicates are applied in one bounded server-side query. Full-text results retain relevance ordering; repository, author, committer, path and time restrictions are filters. When no full-text expression is present, the relational query path remains newest-first and keeps literal, case-insensitive path-fragment matching.

Structured query ordering is deterministic: selected timestamp descending, then object ID. `offset(...)` and `limit(...)` therefore provide stable bounded pages. Full-text pages are relevance-ranked.

### Author time versus committer time

Git preserves two time dimensions:

- **author time** records when the original author produced the change;
- **committer time** records when the commit entered the current history, for example after a rebase, cherry-pick or import.

`GitCommitIndex` stores both identities and both timestamps. `CommitHistoryQuery` uses committer time for ranges and newest-first ordering by default. Use `authoredBetween(...)` or `usingAuthorTime()` when the question is explicitly about original authorship.

```java
CommitHistoryQuery authoredInJanuary =
    CommitHistoryQuery.forRepository("payment-platform")
        .authoredBetween(januaryStart, januaryEnd)
        .build();
```

The migration to 0.1.14 copies the old `commit_time` value into `author_time`. Existing projections must then be rebuilt to populate authoritative committer name, email and time.

`CommitIndexer` stores paths changed relative to the first parent. Root commits treat every path as changed; merge commits use first-parent semantics. The projection makes text, author, committer, changed-path and timestamp predicates jointly queryable without traversing and diffing history at request time.

The documented use cases are executable in [`CompoundCommitHistoryQueryH2Test`](src/test/java/io/github/carstenartur/jgit/storage/hibernate/search/CompoundCommitHistoryQueryH2Test.java), [`CommitTimestampSemanticsH2Test`](src/test/java/io/github/carstenartur/jgit/storage/hibernate/search/CommitTimestampSemanticsH2Test.java) and the SQL Server Testcontainers suite.

Branch or ref reachability is intentionally not copied into this generic projection. A consumer that restricts results to one branch must enforce that repository/ref boundary through JGit or an application-owned projection.

## Rebuilding from authoritative Git history

`CommitProjectionRebuilder` removes the selected logical repository's existing SQL and Lucene projection, resolves every commit-valued ref, deduplicates reachable commits and indexes them oldest-first without modifying Git:

```java
CommitProjectionRebuilder rebuilder =
    new CommitProjectionRebuilder(sessionFactory);

RebuildResult result =
    rebuilder.rebuild(
        repository,
        new RepositoryName("payment-platform"),
        progress -> maintenanceLog.write(progress));
```

The progress contract reports:

- repository name and lifecycle state;
- discovered ref-tip count;
- visited, indexed and skipped commit counts;
- removed projection count;
- current commit object ID;
- exception type and message for `FAILED` or `INTERRUPTED` events.

A failed or interrupted rebuild can leave partial derived state. The next invocation clears that state before starting again, making retries deterministic. Concurrent projection writers for the same repository must be stopped during rebuild.

## Indexing profiles

Search work and recall can be selected explicitly:

```properties
jgit.storage.hibernate.search.index_profile=content-v1
```

| Stable profile | Paths | Changed-file text | Typical purpose |
|---|---|---|---|
| `metadata-v1` | no | no | commit-message/person/time audit |
| `paths-v1` | yes | no | path-aware history without blob extraction |
| `content-v1` | yes | bounded current text | backward-compatible general profile |
| `diff-hunks-v1` | yes | added/modified lines | experimental reduced-repetition content profile |

Omitting the property remains equivalent to `content-v1`. Existing projection rows are migrated to that stable profile ID because it represents the pre-profile semantics.

A profile change is not mixed silently with existing projections. The stable profile is persisted relationally and indexed in Lucene; a mismatch (including a change back to the implicit default) raises `SearchIndexProfileMismatchException` and requires `CommitProjectionRebuilder` for that repository.

The content profile can additionally bound/filter changed blobs by extension, deterministic path-derived MIME type, per-file/per-commit size, binary/UTF-8 validity, generated paths and minified content. See [Search indexing profiles](../docs/operations/search-indexing-profiles.md) for the complete configuration and the retained performance/quality charts.

## Analysis model

The generic projection deliberately assigns analyzers by field semantics:

- short and full commit messages use the configurable natural-language slot `GitTextAnalysis.NATURAL_LANGUAGE_ANALYZER`;
- changed-file content, when enabled by the profile, uses the fixed language-neutral `standard` analyzer;
- analyzed path search targets the derived `changedPathTerms` field using the built-in `simple` analyzer;
- each path is also indexed separately as the exact keyword field `changedPathExact`;
- the aggregate relational `changed_paths` value is **not** stored as a third Lucene path representation;
- author/committer names and email addresses use keyword semantics;
- author and committer timestamps are generic sortable/range fields.

The default natural-language slot is Hibernate Search's built-in `default` analyzer, whose Lucene implementation is `StandardAnalyzer`. Tokenization follows Unicode word boundaries and tokens are lowercased, but words are not stemmed.

The path analyzer splits at non-letter punctuation and lowercases terms. Thus `workflow` matches `workflow.dsl`, and `SERVICES payments` matches path components in `services/payments/...`. Numeric-only path components are not independent terms with this analyzer; use the exact path field or an application-specific projection when numeric identity is significant.

### Selecting a language profile

Stemming is index configuration, not a per-request option. Index-time and query-time analysis must remain compatible, and changing the profile requires recreating or rebuilding the derived Lucene index.

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

`GitTextAnalysis.configure(...)` writes a resolvable `class:` reference to `hibernate.search.backend.analysis.configurer` and stores a separate operator-visible profile identity. `GitTextAnalysis.profileId(properties)` reports that identity, or `neutral-standard-v1` when the built-in neutral profile is active.

The configurer is backend-wide. Applications sharing the default backend must assign explicit analyzers to unrelated full-text fields when those fields must not inherit the overridden `default` analyzer. This module already protects changed source material and paths through explicit mappings.

The executable [`ChangedPathAnalysisH2Test`](src/test/java/io/github/carstenartur/jgit/storage/hibernate/search/ChangedPathAnalysisH2Test.java) verifies component matching across punctuation and case. [`ConfigurableGitTextAnalysisH2Test`](src/test/java/io/github/carstenartur/jgit/storage/hibernate/search/ConfigurableGitTextAnalysisH2Test.java) verifies neutral backward behavior, English message stemming, field isolation and profile diagnostics.

## Full-text query

```java
List<GitCommitIndex> hits =
    search.searchCommitText(
        "payment-platform",
        "\"dual control\" OR fraud OR cve",
        50);
```

Full-text search covers:

- short and full commit messages;
- actual first-parent changed paths when the active indexing profile includes paths;
- selected changed-file content when the active indexing profile includes content.

Deleted files remain represented by path in path-enabled profiles. Large, filtered or non-blob content is intentionally not loaded into the text projection.

## What it adds

- materialize repository, object ID, messages, author, committer, both timestamps and profile-selected changed history;
- choose explicit `metadata-v1`, `paths-v1`, `content-v1` or experimental `diff-hunks-v1` indexing semantics;
- bound/filter changed-file extraction before expensive blob materialization where possible;
- combine full text, author email, committer email, changed path and inclusive time bounds through `CommitHistoryQuery`;
- provide deterministic offset/limit pages for structured history queries;
- choose author or committer time deliberately;
- apply field-specific analysis to path terms and configurable analysis to natural-language messages;
- run full-text queries through Hibernate Search/Lucene;
- retain `findByAuthorEmail`, `findByCommitterEmail`, `findByPath`, committer-based `findBetween`, author-based `findAuthoredBetween` and full-text convenience methods;
- share the Core database configuration while keeping Search optional;
- delete and rebuild projections because Git objects and refs remain authoritative;
- provision the projection table through its own versioned Flyway history.

## Dependency

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-search</artifactId>
  <version>0.10.0</version>
</dependency>
```

The documented released version remains 0.1.15 until the next release is published. SQL Server Search requires 0.1.16 or later. The Search artifact depends on Core. Apply Core migrations first, then Search migrations with its separate history table.

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

For persistent Lucene storage:

```properties
hibernate.search.backend.type=lucene
hibernate.search.backend.directory.type=local-filesystem
hibernate.search.backend.directory.root=/srv/jgit-storage-hibernate/lucene
```

The consuming application owns that directory, must prevent uncoordinated concurrent writers and must retain a repeatable rebuild procedure.

## Update and consistency model

- Git/Core remains authoritative.
- The Search projection may temporarily lag behind Git when indexing is asynchronous.
- `CommitIndexer` upserts each `GitCommitIndex` row in an explicit Hibernate transaction.
- Search indexing is not the same transaction as Core pack publication or a JGit ref update.
- A failed index update is retried or rebuilt; it does not invalidate a successfully published commit.
- Rebuild after analyzer-profile changes, Search indexing-profile changes, changed-path semantic changes, or the 0.1.14 author/committer metadata migration.
- Register `SearchRepositoryDeletionParticipant` when Core repository deletion must remove Search rows and Lucene documents in the same deletion transaction.

## Database ownership

Search owns `git_commit_index` and `jgit_storage_hibernate_search_schema_history`. Domain-specific projections remain in the consuming application even when they share one `SessionFactory`.

## Verification

H2 integration tests exercise compound query, analyzer, timestamp, rebuild progress, indexing-profile/content-policy semantics, fail-closed profile migration and interrupted-retry semantics on every build.

With Docker available, Testcontainers starts PostgreSQL 17.10 and SQL Server 2022. SQL Server evidence covers Core-plus-Search migration, Hibernate `validate`, Unicode root/normal/merge indexing, first-parent added/modified/deleted path semantics, author/committer/path/time/compound queries, stable pagination, two logical repositories, interrupted rebuild retry, transactional repository deletion, projection-failure isolation and full-text search after a persistent Lucene restart.

The dedicated Hibernate Search performance workflow compares all four indexing profiles on PostgreSQL plus local-filesystem Lucene and retains indexing/rebuild time, query time, GC/ORM evidence, Lucene/PostgreSQL footprint, segment count and content/path miss rates. See [Hibernate Search performance](../docs/operations/hibernate-search-performance.md).

See the [change-audit and Java-usage use case](../docs/use-cases/change-audit-and-java-usage.md) for the complete architectural comparison and [SQL Server Search operations](../docs/operations/sql-server-search.md) for deployment and rollback.
