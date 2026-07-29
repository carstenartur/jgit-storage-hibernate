# Committed pack metadata catalog

## Purpose

JGit first asks the object database to list committed packs and later opens individual PACK, IDX or Reftable extensions. The pack-list query already discovers the stable identity and size of every extension. Repeating the same metadata lookup in `openFile()` adds a read transaction, connection checkout and prepared statement for every opened chunked extension.

`ReadAheadHibernateObjDatabase` therefore publishes an immutable metadata catalog after each successful `listPacks()` scan. A catalog entry contains only:

- pack name and extension;
- the narrow `git_packs.id` value;
- persisted file size;
- whether the extension is inline or chunked.

It does not retain Hibernate entities, sessions, JDBC connections or payload byte arrays.

## Read behavior

A chunked extension found in the current catalog opens a `ReadAheadChunkedReadableChannel` directly. Chunk bytes remain loaded lazily in bounded ordered windows, so opening the channel requires no metadata transaction.

Inline extensions deliberately keep the existing database fallback. This prevents repository memory from growing with every historical inline PACK, IDX or Reftable payload. A catalog miss also uses the fallback so descriptions not produced by the current scan retain the previous behavior.

## Publication and invalidation

A successful pack publication performs these steps in order:

1. persist and publish all staged extensions in the repository-locked Hibernate transaction;
2. replace the catalog with an empty immutable map;
3. clear JGit's DFS pack cache.

The next pack-list scan rebuilds both views from committed database state. A failed publication does not replace the last complete catalog generation because the database transaction rolls back and the previous committed snapshot remains valid.

The catalog is repository-instance local. Independent repository instances continue to observe each other's commits at their normal DFS cache refresh boundary; the optimization does not introduce a cross-instance cache-coherence protocol.

## Memory bound

The retained state is proportional to the number of committed extensions, not their payload size. Each entry contains two existing string references, one `Long`, one `long` and one boolean plus immutable map overhead. Inline and chunk data are not retained by the catalog.

## Verification

Core tests prove that:

- one pack-list query builds a complete immutable catalog;
- opening a catalogued chunked extension performs no repeated metadata query or `PACK_FILE_READ` transaction;
- chunk data still uses the existing bounded read-ahead query;
- inline payloads retain the database fallback;
- successful publication invalidates the catalog and the next scan rebuilds it;
- failed publication leaves the prior complete generation usable.

The standard Maven/JUnit/Testcontainers JMH comparison is the performance authority. The target is fewer PostgreSQL `PACK_FILE_READ` transactions and prepared statements without changing write transactions, locks or persisted data.
