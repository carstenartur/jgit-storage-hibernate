# Committed pack metadata catalog and inline payload cache

## Purpose

JGit first asks the object database to list committed packs and later opens individual PACK, IDX or Reftable extensions. The pack-list query already discovers the stable identity and size of every extension. Repeating the same metadata lookup in `openFile()` adds a read transaction, connection checkout and prepared statement for every opened chunked extension.

`ReadAheadHibernateObjDatabase` therefore publishes an immutable metadata catalog after each successful `listPacks()` scan. A catalog entry contains only:

- pack name and extension;
- the narrow `git_packs.id` value;
- persisted file size;
- whether the extension is inline or chunked;
- the local JGit `PackSource` when the row was created by this repository instance.

It does not retain Hibernate entities, sessions, JDBC connections or payload byte arrays.

## Read behavior

A chunked extension found in the current catalog opens a `ReadAheadChunkedReadableChannel` directly. Chunk bytes remain loaded lazily in bounded ordered windows, so opening the channel requires no metadata transaction.

Inline bytes remain outside the immutable catalog. A separate repository-instance-local LRU is keyed by the immutable committed row ID:

- newly and locally published inline payloads are handed directly from the publication result into the cache;
- a historical inline payload enters the cache only after its authoritative database fallback commits successfully;
- later opens use the cached bytes without a `PACK_FILE_READ` transaction;
- a catalog miss still uses the database fallback, so descriptions not produced by the current scan retain the previous behavior.

The database row is always authoritative. The cache is a bounded acceleration layer and is never used to publish, recover or coordinate repository state.

## Local publication handoff

JGit's normal post-commit lifecycle is preserved:

1. Core publishes all expected extensions in the repository-locked Hibernate transaction.
2. `commitPack()` fires JGit's packs-changed event.
3. Core still calls `clearCache()` so event listeners and JGit observe the normal invalidation boundary.
4. `DfsInserter`, `DfsPackParser` or Reftable publication subsequently calls `addPack()` or `addReftable()`.

Without a handoff, step 4 sees JGit's `NO_PACKS` marker and calls the backend `listPacks()` again. This repeated scan was responsible for the two remaining `PACK_METADATA_READ` transactions in each measured push.

When the pre-commit catalog is complete, the publication transaction returns the exact generated row ID, persisted file size and storage mode for every newly staged extension. For inline rows it also returns the byte array that was already read from the temporary staging file for persistence; no additional file or database read is introduced. Core merges the row metadata into the immutable catalog, inserts the bounded inline payloads into the LRU, removes every replaced pack, and exposes the result as a one-shot local pack-list scan. The first JGit scan after `clearCache()` consumes the snapshot through compare-and-set without opening a Hibernate transaction. JGit then continues its native `addPack()` or `addReftable()` logic.

The snapshot is safe whether the first consumer is the normal writer path or a packs-changed event listener. A second consumer sees JGit's already reconstructed atomic pack list rather than reusing the one-shot marker.

## Concurrency, replacement, legacy data and rollback

A fair repository-instance-local read/write lifecycle lock prevents `listPacks()` from querying the database while the same instance has an uncommitted pack replacement in progress. It does not replace the cross-instance repository row lock used for database writes.

Pack replacements remove their old catalog entries before the transaction starts. Cached payloads for replaced row IDs are removed only after the database publication commits successfully. A failed commit therefore restores the previous complete generation and retains its still-valid cached payloads. A successful replacement cannot serve stale bytes because the cache key is the deleted row ID rather than the reused logical pack name.

Legacy durable-uncommitted extensions are published through the compatibility `UPDATE` path. That path deliberately does not issue another query merely to obtain handoff metadata. If any extension in the publication lacks exact returned metadata, the resulting catalog is marked incomplete, the one-shot is disabled, and the next JGit scan loads the complete committed view once from the database.

If the previous catalog was incomplete, Core likewise does not claim to know the complete pack set. Independent repository instances continue to observe each other's commits at their existing DFS cache refresh boundary; no cross-instance cache-coherence protocol is introduced.

## Memory bound and configuration

Catalog memory remains proportional to committed extension count, not payload size. Inline payload memory is separately bounded by least-recently-used eviction.

The SessionFactory property

```properties
jgit.storage.hibernate.inline_payload_cache.max_bytes=8388608
```

sets the maximum retained inline bytes for each repository instance. The default is 8 MiB. Set it to `0` to disable payload caching. Negative or non-numeric values are rejected during repository construction. No entry larger than the existing 256 KiB inline-storage threshold is admitted.

The bound is per open `HibernateRepository`, not global and not cluster-wide. Applications that keep many repository instances open should choose a lower value according to their aggregate memory budget.

## Why not in-memory database tables

Git objects, refs, reflogs and repository locks remain in durable ordinary tables. Database-specific in-memory, unlogged or memory-optimized tables would weaken restart recovery, complicate multi-instance correctness and fragment the supported H2, HSQLDB, PostgreSQL and SQL Server contract. The bounded JVM cache avoids those trade-offs because it contains only reproducible bytes from already committed rows.

## Verification

Core tests prove that:

- one database pack-list query builds a complete immutable catalog;
- opening a catalogued chunked extension performs no repeated metadata query or `PACK_FILE_READ` transaction;
- chunk data still uses the existing bounded read-ahead query;
- a historical inline payload reads once and subsequent opens use the row-ID cache;
- local inline publication is handed directly to readers without a database fallback;
- setting the cache bound to zero preserves the authoritative fallback behavior;
- successful replacement cannot serve removed row bytes and failed publication preserves the prior generation;
- direct successful publication exposes and consumes a zero-query local pack-list handoff;
- `ObjectInserter.flush()` reaches JGit's `addPack()` without a `PACK_METADATA_READ` transaction;
- legacy publication disables the handoff and performs exactly one authoritative refresh scan;
- H2, HSQLDB, PostgreSQL, SQL Server and every supported JGit line retain normal repository behavior.

## Previously measured PostgreSQL effect

Counters below compare the post-publication pack-list handoff with the earlier metadata-catalog implementation from PR #141. They are retained as the baseline for the inline-cache benchmark.

| Workload | Prepared statements | Connections | Storage transactions | `PACK_METADATA_READ` | `PACK_FILE_READ` | Publication transactions | Repository locks |
|---|---:|---:|---:|---:|---:|---:|---:|
| Initial push | 13 → 11 | 6 → 4 | 6 → 4 | 2 → 0 | 2 → 2 | 2 → 2 | 2 → 2 |
| Incremental push | 13 → 11 | 7 → 5 | 6 → 4 | 2 → 0 | 2 → 2 | 2 → 2 | 2 → 2 |
| Initial clone | 2 → 2 | 2 → 2 | 1 → 1 | 0 → 0 | 1 → 1 | 0 → 0 | 0 → 0 |
| Incremental fetch | 3 → 3 | 3 → 3 | 2 → 2 | 0 → 0 | 2 → 2 | 0 → 0 | 0 → 0 |

The focused twelve-MiB publication also lost its single post-commit metadata scan: batching-disabled statements/prepared statements decreased from 17/17 to 16/16, while portable batching decreased from 4/6 to 3/5. Chunk inserts, pack inserts, batch executions, flushes and publication locks remained unchanged.

Elapsed JMH values moved in both directions but remained inside the reported uncertainty ranges. The supported claim is therefore the deterministic removal of database work, not an unqualified latency percentage. The final inline-cache measurements are recorded in the pull request and will replace this baseline table after verification.
