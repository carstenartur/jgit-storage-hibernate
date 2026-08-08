# Hibernate Search persistent-index upgrades

The relational `git_commit_index` table is a rebuildable projection of authoritative Git history, and the Lucene index is derived from that relational projection. Schema migrations and Lucene document-identifier compatibility therefore have different upgrade rules.

## Assigned projection keys from 0.9.1

Search projection inserts use the assigned `projection_key` as the ORM and Hibernate Search document identifier. This enables real JDBC insert batching because the identifier exists before the Hibernate flush. The historical numeric `id` column remains readable compatibility metadata.

A database migrated from an older release receives stable `legacy-<id>` projection keys. An already existing persistent Lucene index, however, can still contain the old numeric document identifiers. A Flyway migration cannot rewrite those Lucene identifiers.

## Automatic compatibility rebuild

For the Lucene `local-filesystem` backend, `GitHistorySearchService` and `CommitIndexer` perform a one-time compatibility check for each `SessionFactory` before the first Search read or projection write.

The check is intentionally narrow:

1. it looks for a migrated relational projection whose key starts with `legacy-`;
2. it asks the current Lucene index whether that assigned projection key is addressable as a document identifier;
3. when the document is missing under the current identifier contract, Hibernate Search MassIndexer rebuilds the complete `GitCommitIndex` index from the relational projection;
4. construction does not return until that rebuild has completed successfully.

This avoids a mixed index containing both historical numeric document identifiers and new assigned identifiers. The rebuild does not rewrite Git objects, refs or reflogs.

The compatibility check is not run for `local-heap`: an in-memory index cannot survive an application restart or mapping upgrade and therefore has no historical document identifiers to repair. Core-only consumers do not load this Search module and are unaffected.

## Failure and retry

The compatibility check marks a `SessionFactory` as verified only after the check and any required MassIndexer run complete. If the rebuild is interrupted, the thread interrupt flag is restored and service/indexer construction fails. A subsequent application start or new `SessionFactory` retries the compatibility check and rebuild instead of treating the partial derived index as current.

Because the rebuild covers the complete `GitCommitIndex` index, all logical repositories are rebuilt together. This deliberately favors a simple all-or-nothing upgrade boundary over exposing one repository from a mixed document-ID generation while another is being upgraded.

## Operational consequences

For an upgrade from a release that persisted numeric Search document identifiers:

- apply the Core and Search Flyway migrations before Hibernate validation as usual;
- retain the relational `git_commit_index` rows: they are the input to the compatibility rebuild;
- retain or back up the old Lucene directory only as derived data; it is not authoritative;
- allow enough startup time and temporary I/O capacity for one MassIndexer rebuild when a mismatch is detected;
- do not serve Search requests from the affected `SessionFactory` until its construction succeeds;
- after a successful rebuild, subsequent Search service/indexer construction on the same `SessionFactory` does not repeat the probe or rebuild.

The retained `PersistentNumericDocumentIdCompatibilityH2Test` creates an old-style on-disk index with two logical repositories, reopens it using the current mapping, verifies queries after the compatibility rebuild, deletes one repository's projection without hiding the other, and verifies the result again after restart.
