# Committed pack metadata catalog and local payload handoff

## Purpose

JGit first asks the object database to list committed packs and later opens individual PACK, IDX or Reftable extensions. The pack-list query already discovers the stable identity and size of every extension. Repeating that metadata lookup adds a read transaction, connection checkout and prepared statement.

`ReadAheadHibernateObjDatabase` therefore publishes an immutable catalog after each successful `listPacks()` scan. Each entry contains only the logical extension key, `git_packs.id`, persisted size, inline/chunked mode and the local JGit `PackSource` where known. It never retains Hibernate entities, sessions, JDBC connections or payload arrays.

## Chunked reads

A chunked extension found in the current catalog opens `ReadAheadChunkedReadableChannel` directly. Chunk bytes remain loaded lazily in bounded ordered windows. Opening the channel therefore needs no repeated metadata transaction and does not hold a database connection for the channel lifetime.

## Local inline payload handoff

Issue #143 attributed every remaining standard-protocol database file read. They were locally published small Reftables plus one locally published inline PACK in incremental fetch. No IDX, chunked, other or missing read dominated the workload.

Core therefore retains only bytes that are already available during successful local publication:

- eligible extensions are inline PACK and Reftable only;
- IDX and all other extensions always use the authoritative fallback;
- arrays returned by Hibernate are never inserted;
- repository reopen and independent instances begin without retained payloads;
- the database remains the sole durable and cross-instance authority.

The publication transaction already reads an inline payload from the temporary staging file in order to persist it. Its result returns the generated row ID, persisted size, storage mode and that same byte array. After the transaction commits, Core defensively copies eligible bytes into the local handoff without another file or database read.

## Hard memory bound

One repository instance may retain at most **512 KiB** across committed generations. The handoff evicts the oldest locally published payloads first. An individual payload must also satisfy the existing 256-KiB inline-storage threshold.

The bound is deliberately fixed rather than application-configurable. This keeps the memory contract small, predictable and independent of repository history. Metadata catalog memory remains proportional to committed extension count; payload memory never grows beyond the hard limit.

## Authoritative revalidation

A retained payload is keyed by its complete immutable committed identity:

- pack name;
- extension;
- generated database row ID;
- persisted file size.

Every authoritative database catalog scan keeps an entry only when that identity is still present and the row is still inline. Missing rows, replacements, changed IDs, changed sizes and changed storage modes remove the local payload immediately. The scan does not load replacement bytes.

This also makes independent-instance behavior explicit: another instance may change the database, but the local bytes remain usable only until the owning repository reaches its established DFS refresh boundary and performs an authoritative scan.

## Publication, replacement and rollback

JGit's native lifecycle is preserved:

1. all expected extensions are published in the repository-locked Hibernate transaction;
2. Core merges exact committed metadata into the immutable catalog;
3. eligible local bytes enter the bounded handoff;
4. `clearCache()` and the packs-changed event still form JGit's normal invalidation boundary;
5. `DfsInserter`, `DfsPackParser` or Reftable publication continues through `addPack()` or `addReftable()`.

When the previous catalog is complete, the first post-publication `listPacks()` consumes a one-shot local snapshot without a database transaction. A later authoritative scan revalidates retained payload identities.

Replacement removes old catalog entries before the transaction starts, but removes their payload identities only after publication commits. Failed publication restores the previous complete generation and leaves its valid local payloads intact. Successful replacement cannot serve old bytes because the deleted row ID no longer matches.

Legacy durable-uncommitted publication continues through its compatibility `UPDATE` path. It does not synthesize payload state or issue an additional metadata query. Incomplete metadata disables the one-shot and forces one authoritative refresh scan.

## Database indexes

The same performance review found no missing pack-read index. Existing keys already cover the hot paths:

- unique `(repository_name, pack_name, pack_extension)` covers point and pack-name lookups;
- `(repository_name, committed)` covers committed catalog scans;
- `(repository_name, committed, write_lease_until)` covers lease cleanup;
- chunk identity `(pack_id, chunk_index)` covers chunk range/order reads.

Flyway migration 0.1.17 removes redundant secondary indexes that duplicate those leading keys. It also replaces two reflog append indexes with one newest-first access path:

- H2, HSQLDB and PostgreSQL: `(repository_name, ref_name, id DESC)`;
- SQL Server: `(repository_name, id DESC) INCLUDE (ref_name)`, because `nvarchar(1024)` cannot be a portable SQL Server key column.

This lowers index maintenance on pack extensions, chunks and reflog appends while preserving the measured read paths.

## Why not in-memory database tables

Git objects, refs, reflogs and repository locks remain in durable ordinary tables. Database-specific memory-only, unlogged or memory-optimized tables would weaken restart recovery, complicate multi-instance correctness and fragment the supported H2, HSQLDB, PostgreSQL and SQL Server contract. The local handoff is safe precisely because it contains only reproducible bytes from already committed rows and is never authoritative.

## Verification

Core tests prove that:

- catalogued chunked extensions avoid repeated metadata reads;
- locally published inline PACK and Reftable payloads open repeatedly without `PACK_FILE_READ` transactions while retained;
- an exact authoritative scan preserves matching local bytes;
- historical database-loaded payloads and IDX bytes are never retained;
- total retained bytes never exceed 512 KiB and oldest entries are evicted first;
- replacement cannot serve a removed row's bytes and rollback preserves the prior generation;
- fresh, legacy and adopted schemas contain the intended index set on all four databases;
- every supported JGit line retains normal push, clone, fetch, restart and publication behavior.

The standard JMH benchmark reconciles attributed successful fallback reads with `PACK_FILE_READ` transactions. The final pull-request measurements are the source of truth for deterministic statement, connection and transaction reductions; latency percentages are claimed only when they exceed reported JMH uncertainty.
