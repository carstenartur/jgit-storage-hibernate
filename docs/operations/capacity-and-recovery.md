# Pack capacity and recovery

## Payload and publication model

Core separates binary construction from transactional publication:

- an open JGit `PACK`, `IDX`, object-size-index, bitmap or Reftable writer starts in random-readable memory;
- at most **256 KiB** is retained by one extension, and all repository instances in the class loader share a **32 MiB** staging budget;
- when the extension crosses the threshold or the shared budget has no room, its written prefix is copied once to a temporary file and all later writes continue there;
- JGit can perform positional reads through the same `DfsOutputStream` before and after a spill;
- closing an extension stages the completed memory payload or file in the repository instance but creates no database row;
- logical packs whose extensions all remain at or below the inline threshold are persisted and published in one repository-locked Hibernate transaction;
- additive logical packs containing a chunked extension use two transactions: persist the complete invisible parent/payload set without the repository lock, then atomically publish the generation under one short lock;
- pack replacement and compaction remain on the established single locked transaction path so no ref update can cross JGit's race check before source-pack deletion;
- small committed extensions remain inline in `git_packs.data`;
- larger committed extensions use ordered **1 MiB** rows in `git_pack_chunks`.

Readers select only `committed=true` rows. An additive chunked logical pack can therefore be complete in the database before publication without exposing a partial generation to JGit.

### Memory-first staging

The memory path exists to avoid this sequence for small extensions:

```text
write temporary file
read temporary file
insert inline database value
delete temporary file
```

Instead, the exact closed payload array is handed directly to inline publication. The staging budget is released after publication or rollback; the separate bounded inline handoff cache accounts for any array intentionally retained for a subsequent JGit read.

Memory growth is geometric while a stream is open. Closing trims the final array to its exact logical size, so unused capacity is returned to the staging budget. Cleanup is idempotent: duplicate close, publication cleanup and a later JGit rollback cannot release the same reservation twice.

Spill is one-way. Once an extension becomes file-backed, it does not move back into memory even if later writes are small. This avoids repeated heap/disk transitions and preserves the previous large-payload memory envelope.

The **32 MiB** value is a process-local staging safeguard, not a total application heap limit. JGit block caches, Hibernate, JDBC drivers, committed inline payload handoff and application objects have independent memory budgets.

### Adaptive chunked publication

For a fully local additive logical pack containing at least one extension above the inline threshold, `commitPack()` performs:

1. one lock-free `PACK_EXTENSION_WRITE` transaction that creates every expected parent, assigns one UUID writer token and lease, writes inline payloads and inserts bounded chunk rows;
2. one short `PACK_PUBLICATION` transaction that acquires the repository lock and changes every expected token-owned row to `committed=true` in one bulk update.

The first transaction is all-or-nothing. A failure before it commits leaves no durable parent or chunk rows. After it commits, the complete logical pack is durable but invisible. Its lease is renewed at the end of the transaction so a long transfer does not produce an already-expired prepared group.

The final update count must match the expected extension count. A missing or changed row aborts publication, after which token-owned uncommitted parents are removed and the database cascade deletes their chunks.

A logical pack containing a legacy durable-uncommitted extension stays on the established single locked path. Pack replacement, compaction and read-optimized garbage collection also remain direct: JGit may validate refs before invoking `commitPack()`, and an unlocked interval before source-pack deletion would make that race check stale.

## Memory, temporary-disk and transaction-log envelope

The production path uses:

- up to 256 KiB of retained staging memory per memory-backed extension;
- at most 32 MiB of memory-backed staging across the class loader;
- one temporary file for each spilled open or completed-but-unpublished extension;
- exact final chunk arrays of up to 1 MiB, retained only until the bounded Hibernate batch flush;
- a bounded multi-chunk read-ahead window per open chunked reader.

Temporary files remain until the corresponding logical pack commits or rolls back. Capacity planning must cover every concurrently spilled writer and every completed file awaiting `commitPack()`.

During adaptive publication, a chunked payload temporarily exists both in its local staging file and in invisible database rows. The database transaction log must accommodate one complete pre-persistence transaction per additive logical pack. Hibernate flushes payload chunks in bounded batches, but visibility is not committed until every expected extension is present.

Normal publication and rollback delete temporary files explicitly. The implementation deliberately does not register every file with the JVM-wide `deleteOnExit` registry. A hard JVM termination or operating-system deletion failure can leave files with the `jgit-storage-pack-` prefix. They are unpublished derived state and are never interpreted as durable or resumable Git data.

The chunk format uses a `long` declared file size and does not require the complete payload to fit in a Java array. Individual chunk indexes are Java integers; with 1 MiB chunks this remains far beyond the capacity currently exercised by the project.

## Capacity verification

Normal pull-request tests cover:

- memory-backed append and positional reads;
- positional reads across the memory-to-file spill boundary;
- threshold-driven and budget-driven spill;
- exact staging-budget release and over-release rejection;
- no database row when an extension merely closes;
- direct inline publication from the staged memory array;
- chunked publication from spilled files;
- direct atomic multi-extension publication for fully inline logical packs;
- complete invisible pre-persistence and short locked publication for additive chunked packs;
- publication mismatch, transaction rollback and token-owned row/chunk cleanup;
- direct locked publication for pack replacement and compaction;
- close/reopen and `SessionFactory` restart;
- H2, HSQLDB, PostgreSQL and SQL Server behavior;
- legacy inline and durable-uncommitted-row compatibility;
- repository deletion, replacement and chunk-row rollback.

A separate profile verifies **1 MiB, 16 MiB and 128 MiB** payloads:

```bash
mvn -B -pl jgit-storage-hibernate-core -Ppack-capacity verify
```

The performance workflow executes this profile for manual and weekly scheduled runs. These sizes demonstrate bounded chunk behavior and random access; they are not a universal maximum-pack certification.

Before production use, also test an import or repack representative of the largest expected repository, concurrent readers and writers, backup/restore, actual connection-pool limits, transaction-log capacity and temporary-disk placement.

## Durable writer tokens and leases

Writer tokens and leases protect every durable uncommitted row, including:

- complete groups pre-persisted by adaptive chunked publication;
- rows produced by an earlier library version;
- direct use of the base `HibernateObjDatabase` contract;
- safe maintenance and rollback of abandoned state.

All extensions pre-persisted for one logical pack share a UUID `write_token`. The pre-persistence transaction renews `write_lease_until` immediately before commit. The potentially long payload transaction deliberately does not hold the repository publication lock because its rows remain invisible and token-owned.

Final publication, direct replacement, ref publication and maintenance use the repository-scoped pessimistic lock. Mixed local/legacy publication remains on the direct path rather than being partially converted.

## Recovering abandoned state

### Local staging files

A hard JVM termination can leave spilled `jgit-storage-pack-` files. They contain no authoritative publication decision. Remove stale files only through operating-system policy or an operator cleanup after confirming that no matching process is active. Do not import them as packs.

Memory-backed staging disappears with the process and therefore leaves no local cleanup artifact. If adaptive database pre-persistence already committed, the durable invisible group is recovered independently through its token and lease.

### Uncommitted database rows

A crash can leave `committed=false` rows in two forms:

- a complete additive logical pack whose pre-persistence committed but whose final publication did not run;
- an older or base-writer row.

A failure during pre-persistence itself rolls back the complete database transaction and leaves no partial group. Readers ignore every uncommitted row, and operators must never promote one with direct SQL.

Use the public maintenance service:

```java
PackCleanupResult result =
    new PackStorageMaintenance(sessionFactory)
        .deleteExpiredUncommittedPacks(
            new RepositoryName("domain-history"),
            Instant.now().minus(Duration.ofHours(24)),
            Instant.now());
```

The operation obtains the repository lock, considers only pack names for which every extension is old, uncommitted and has no valid lease, counts payload rows and bytes, and deletes parents while the foreign-key cascade removes chunks.

Choose `createdBefore` conservatively. It is an operator policy cutoff in addition to the writer lease.

## Inspection query

```sql
select repository_name,
       pack_name,
       pack_extension,
       file_size,
       created_at,
       write_token,
       write_lease_until
from git_packs
where committed = false
order by repository_name, pack_name, pack_extension;
```

An all-inline publication does not appear before commit. An additive chunked publication appears only after its complete pre-persistence transaction commits and remains until the short visibility update completes. A current lease means the group may belong to a live writer.

Do not schedule raw deletion of all uncommitted rows; it bypasses repository coordination, logical-pack grouping and lease checks.

## Legacy inline rows

Migration intentionally does not rewrite already published inline BLOBs. New small extensions may remain inline; larger extensions use chunks. Repository deletion and pack replacement remove both representations. Flyway schemas use foreign-key cascade, and disposable `create-drop` schemas generate equivalent behavior through the Hibernate mappings.
