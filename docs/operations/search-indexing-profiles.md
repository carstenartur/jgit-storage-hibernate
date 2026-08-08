# Hibernate Search indexing profiles

The Search module can trade indexing work, Lucene footprint and query recall deliberately instead of applying one expensive changed-content policy to every repository.

The profile is selected with:

```properties
jgit.storage.hibernate.search.index_profile=content-v1
```

If the property is omitted, `content-v1` remains the default and preserves the pre-profile behavior.

## Profiles and semantic guarantees

| Stable ID | Commit metadata | Changed paths in Lucene | Changed-file text in Lucene | Intended use |
|---|---|---|---|---|
| `metadata-v1` | yes | no | no | audit/history questions that only need commit messages, people and time |
| `paths-v1` | yes | yes | no | file/path-aware history without blob extraction |
| `content-v1` | yes | yes | bounded current changed-file text | backward-compatible general Search profile |
| `diff-hunks-v1` | yes | yes | added/modified textual lines | experimental content profile with less repeated text |

`diff-hunks-v1` uses first-parent semantics, like the rest of the generic commit projection. A root commit treats the complete current file as added text. Deleted files contribute their changed path but no deleted blob text.

These profiles are **not semantically interchangeable**. A lower indexing time or smaller Lucene directory is not automatically better if the application needs a field that the profile intentionally omits. The retained benchmark therefore records both cost and miss-rate evidence.

## Stable profile identity and rebuild boundary

Every `git_commit_index` row stores `index_profile`. Flyway migration `0.9.1.1` backfills existing rows to `content-v1`, because that is the semantic profile used by releases before profiles existed.

When an application explicitly selects a profile, Search reads and incremental writes fail closed if an existing repository contains another profile or a mixture of profiles. `SearchIndexProfileMismatchException` reports:

- the logical repository;
- the configured stable profile ID;
- the persisted profile IDs;
- the required `CommitProjectionRebuilder` action.

A profile migration is therefore performed by rebuilding that logical repository from authoritative Git history:

```java
new CommitProjectionRebuilder(sessionFactory)
    .rebuild(repository, new RepositoryName(repositoryName));
```

The rebuilder purges the derived relational/Lucene projection before constructing the new profile-specific projection. Git objects, refs and reflogs remain authoritative and are not rewritten.

The document-identifier upgrade described in [search-index-upgrades.md](search-index-upgrades.md) is a different boundary: old numeric Lucene document IDs can be detected and rebuilt automatically. A semantic profile change is operator-controlled and remains explicit so that an application cannot silently lose path/content recall.

## Bounded changed-content policy

`content-v1` keeps the previous defaults: at most 256 KiB per changed blob and 250,000 indexed characters per commit. The limits can be narrowed or raised within hard safety ceilings.

| Hibernate property | Default | Meaning |
|---|---:|---|
| `jgit.storage.hibernate.search.content.max_file_bytes` | `262144` | maximum candidate blob size; hard maximum 16 MiB |
| `jgit.storage.hibernate.search.content.max_commit_chars` | `250000` | maximum accumulated changed text; hard maximum 4,000,000 characters |
| `jgit.storage.hibernate.search.content.allow_extensions` | empty | comma-separated extension allowlist, e.g. `java,md,xml` |
| `jgit.storage.hibernate.search.content.deny_extensions` | empty | comma-separated extension denylist; deny wins |
| `jgit.storage.hibernate.search.content.allow_mime_types` | empty | path-derived MIME allowlist; subtype wildcard such as `text/*` is supported |
| `jgit.storage.hibernate.search.content.deny_mime_types` | empty | MIME denylist; deny wins |
| `jgit.storage.hibernate.search.content.reject_binary` | `false` | reject blobs containing a NUL byte in the bounded binary sample |
| `jgit.storage.hibernate.search.content.reject_invalid_utf8` | `false` | reject malformed/unmappable UTF-8 instead of replacement decoding |
| `jgit.storage.hibernate.search.content.skip_generated` | `false` | skip common generated/build output paths |
| `jgit.storage.hibernate.search.content.skip_minified` | `false` | skip `.min.js`/`.min.css` and extreme long-line minified candidates |

Extension and MIME rules are evaluated before a blob is loaded. Size is checked from JGit's `ObjectLoader` before bytes are materialized. This keeps the content policy useful as an I/O policy rather than only as a post-read indexing filter.

MIME matching is deliberately deterministic and path-derived. It does not invoke platform MIME databases, external processes or content sniffers whose result could change between hosts. Unknown extensions use `application/octet-stream`.

## Path mapping cleanup

The relational `changed_paths` column is retained for compatibility, result detail and the backward-compatible literal SQL fragment query.

Lucene no longer stores a third aggregate `changedPaths` representation. Path-enabled profiles retain only:

- `changedPathTerms`: analyzed path components for full-text/path-term matching;
- `changedPathExact`: one keyword value per complete changed path.

Free-text commit search uses `changedPathTerms`. Exact path search uses `changedPathExact`. Literal case-insensitive substring matching remains relational. This removes redundant Lucene path storage without changing those three public query modes.

`shortMessage` and `fullMessage` remain separate on purpose: the short message is a directly projectable result field, while the full message retains body search. Combining or removing either would alter returned data/relevance and is not the same redundancy as indexing identical changed-path data multiple ways.

## Measured evidence

The `Hibernate Search Performance` workflow runs PostgreSQL plus local-filesystem Lucene for all four profiles. The fixture retains raw JMH JSON, ORM/GC counters and grouped history for:

- incremental indexing time;
- complete projection purge/rebuild time;
- entity-hydrating and direct-Lucene full-text query time;
- content-only query time;
- SQL literal and Lucene analyzed path query time;
- Lucene directory bytes after a complete projection;
- PostgreSQL `git_commit_index` relation bytes;
- Lucene segment count;
- content and path miss rates against deterministic relevant-result sets.

Stable chart groups:

- `#benchmark-hibernate-search-indexing`
- `#benchmark-hibernate-search-rebuild`
- `#benchmark-hibernate-search-full-text-query`
- `#benchmark-hibernate-search-content-query`
- `#benchmark-hibernate-search-path-query`
- `#benchmark-hibernate-search-index-footprint`
- `#benchmark-hibernate-search-sql-projection-footprint`
- `#benchmark-hibernate-search-segment-count`
- `#benchmark-hibernate-search-content-quality`
- `#benchmark-hibernate-search-path-quality`

A miss rate of `0%` is best. `metadata-v1` is expected to miss path/content-only fixtures by design; `paths-v1` is expected to miss content-only fixtures. Those points make the cost/semantics trade-off visible instead of comparing unlike profiles only by elapsed time.

Hibernate Search does not expose a stable public per-Lucene-merge timer through the APIs used by this library. The workflow therefore records end-to-end indexing/rebuild latency, GC/ORM counters, final directory bytes and segment count rather than inventing an approximate "merge time" metric.

## Module isolation

All profile types, policies, migrations and compatibility checks live in `jgit-storage-hibernate-search`. Core-only consumers do not load them and keep the Core storage behavior unchanged.
