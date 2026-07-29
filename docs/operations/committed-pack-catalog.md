# Committed pack metadata catalog

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

Inline extensions deliberately keep the existing database fallback. This prevents repository memory from growing with every historical inline PACK, IDX or Reftable payload. A catalog miss also uses the fallback so descriptions not produced by the current scan retain the previous behavior.

## Local publication handoff

JGit's normal post-commit lifecycle is preserved:

1. Core publishes all expected extensions in the repository-locked Hibernate transaction.
2. `commitPack()` fires JGit's packs-changed event.
3. Core still calls `clearCache()` so event listeners and JGit observe the normal invalidation boundary.
4. `DfsInserter`, `DfsPackParser` or Reftable publication subsequently calls `addPack()` or `addReftable()`.

Without a handoff, step 4 sees JGit's `NO_PACKS` marker and calls the backend `listPacks()` again. This repeated scan was responsible for the two remaining `PACK_METADATA_READ` transactions in each measured push.

When the pre-commit catalog is complete, the publication transaction now returns the exact generated row ID, persisted file size and storage mode for every newly staged extension. Core merges those rows into the immutable catalog, removes every replaced pack, and exposes the result as a one-shot local pack-list scan. The first JGit scan after `clearCache()` consumes the snapshot through compare-and-set without opening a Hibernate transaction. JGit then continues its native `addPack()` or `addReftable()` logic.

The snapshot is safe whether the first consumer is the normal writer path or a packs-changed event listener. A second consumer sees JGit's already reconstructed atomic pack list rather than reusing the one-shot marker.

## Concurrency, legacy data and rollback

A fair repository-instance-local read/write lifecycle lock prevents `listPacks()` from querying the database while the same instance has an uncommitted pack replacement in progress. It does not replace the cross-instance repository row lock used for database writes.

Pack replacements remove their old catalog entries before the transaction starts. Successful commit adds the exact new rows and enables one local scan. Failed commit restores the previous complete generation and leaves no local handoff for uncommitted data.

Legacy durable-uncommitted extensions are published through the compatibility `UPDATE` path. That path deliberately does not issue another query merely to obtain handoff metadata. If any extension in the publication lacks exact returned metadata, the resulting catalog is marked incomplete, the one-shot is disabled, and the next JGit scan loads the complete committed view once from the database.

If the previous catalog was incomplete, Core likewise does not claim to know the complete pack set. Independent repository instances continue to observe each other's commits at their existing DFS cache refresh boundary; no cross-instance cache-coherence protocol is introduced.

## Memory bound

Retained state is proportional to committed extension count, not payload size. Each entry contains two string keys, one `Long`, one `long`, one boolean and one enum reference plus immutable-map overhead. Inline and chunk payload arrays are not retained by the catalog.

## Verification

Core tests prove that:

- one database pack-list query builds a complete immutable catalog;
- opening a catalogued chunked extension performs no repeated metadata query or `PACK_FILE_READ` transaction;
- chunk data still uses the existing bounded read-ahead query;
- inline payloads retain the database fallback;
- direct successful publication exposes and consumes a zero-query local handoff;
- `ObjectInserter.flush()` reaches JGit's `addPack()` without a `PACK_METADATA_READ` transaction;
- replacement removes old catalog entries before the handoff;
- legacy publication disables the handoff and performs exactly one authoritative refresh scan;
- failed publication restores the prior complete generation;
- H2, HSQLDB, PostgreSQL, SQL Server and every supported JGit line retain normal repository behavior.

## Measured PostgreSQL effect

Counters are per protocol invocation, comparing this handoff with the merged catalog implementation from PR #141.

| Workload | Prepared statements | Connections | Storage transactions | `PACK_METADATA_READ` | `PACK_FILE_READ` | Publication transactions | Repository locks |
|---|---:|---:|---:|---:|---:|---:|---:|
| Initial push | 13 → 11 | 6 → 4 | 6 → 4 | 2 → 0 | 2 → 2 | 2 → 2 | 2 → 2 |
| Incremental push | 13 → 11 | 7 → 5 | 6 → 4 | 2 → 0 | 2 → 2 | 2 → 2 | 2 → 2 |
| Initial clone | 2 → 2 | 2 → 2 | 1 → 1 | 0 → 0 | 1 → 1 | 0 → 0 | 0 → 0 |
| Incremental fetch | 3 → 3 | 3 → 3 | 2 → 2 | 0 → 0 | 2 → 2 | 0 → 0 | 0 → 0 |

The focused twelve-MiB publication also loses its single post-commit metadata scan: batching-disabled statements/prepared statements decrease from 17/17 to 16/16, while portable batching decreases from 4/6 to 3/5. Chunk inserts, pack inserts, batch executions, flushes and publication locks remain unchanged.

Elapsed JMH values moved in both directions but remained far inside the reported uncertainty ranges. The supported performance claim is therefore the deterministic removal of the metadata transactions and statements, not a latency percentage.
