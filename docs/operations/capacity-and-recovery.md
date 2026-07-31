# Pack capacity and recovery

## Payload and publication model

Core separates binary construction from transactional publication:

- open JGit `PACK`, `IDX`, object-size-index, bitmap and Reftable writers use bounded temporary files;
- JGit can perform random reads through the still-open `DfsOutputStream` while constructing or resolving an extension;
- closing an extension stages the completed temporary file in the repository instance but creates no database row;
- logical packs whose extensions all remain at or below the 256-KiB inline threshold are persisted and published in one repository-locked Hibernate transaction;
- additive logical packs containing at least one chunked extension use an adaptive two-transaction path: persist the complete invisible parent/payload set without the repository lock, then atomically publish the complete generation under one short lock;
- pack replacement and compaction remain on the established single locked transaction path so no ref update can cross JGit's race check before source-pack deletion;
- `git_packs` owns repository, pack name, extension, declared size, writer lease and committed publication metadata;
- small committed extensions remain inline in `git_packs.data`;
- larger committed extensions use ordered **1 MiB** rows in `git_pack_chunks`;
- existing inline, chunked and legacy uncommitted rows remain compatible without a destructive migration.

Readers select only `committed=true` rows. An additive chunked logical pack can therefore have complete parent and payload rows in the database before publication without exposing a partial generation to JGit.

### Adaptive chunked publication

For a fully local additive logical pack with at least one extension above the inline threshold, `commitPack()` performs:

1. one lock-free `PACK_EXTENSION_WRITE` transaction that creates every expected parent, assigns one UUID writer token and lease, writes inline payloads and inserts bounded chunk rows;
2. one short `PACK_PUBLICATION` transaction that acquires the repository lock and changes every expected token-owned row to `committed=true` in one bulk update.

The first transaction is all-or-nothing. A process or database failure before it commits leaves no durable parent or chunk rows. After it commits, the complete logical pack is durable but still invisible. The lease is renewed at the end of this transaction so a large transfer does not publish an already-expired prepared group.

The final update count must match the complete expected extension set. A missing or changed row aborts the visibility transaction. Normal failure handling then deletes every remaining token-owned uncommitted parent; the database foreign-key cascade removes its chunks.

The final repository lock remains the sole committed visibility boundary and serializes the new generation with ref publication, repository deletion, pack replacement and maintenance. Parent identity conflicts are handled by the existing unique constraint rather than by a preliminary repository lock.

A logical pack containing a legacy durable-uncommitted extension stays on the established single locked publication path. The adaptive path is used only when every expected extension is locally staged and the operation is additive.

Pack replacement, compaction and read-optimized garbage collection also stay direct even when the new payload is chunked. JGit can validate refs before invoking `commitPack()`. Releasing the repository lock between that validation and source-pack replacement would allow a conflicting ref update to cross the race check and could make the replacement decision stale.

No partially persisted staged extension is visible to readers. A hard process termination after lock-free pre-persistence can leave only a complete invisible token-owned group, never a committed subset. Lease-aware maintenance removes such groups after the operator cutoff and lease expiry.

This removes the requirement that the largest individual extension fit into one Java byte array and prevents large additive database transfer from occupying the repository publication lock. It is still not a claim that repository size is unlimited: database throughput, temporary-disk capacity, JGit caches, transaction log capacity and concurrent work remain deployment constraints.

## Sandbox predecessor review

The earlier implementation in `carstenartur/sandbox` buffered complete payloads in a `ByteArrayOutputStream`, loaded them as a single `byte[]` and contained an explicit TODO for direct database streaming. There was therefore no more advanced implementation to copy. The current design was implemented independently and retains database portability instead of depending on one vendor's large-object API.

## Memory and temporary-disk envelope

The production path uses:

- one temporary file per open or completed-but-unpublished pack extension;
- a 1 MiB transfer buffer while publishing chunked extensions;
- a bounded Hibernate batch of payload chunks;
- a bounded multi-chunk read-ahead window per open chunked reader.

Temporary files remain until the corresponding logical pack is committed or rolled back, not merely until each extension stream closes. The directory used by `Files.createTempFile(...)` must therefore have enough free space for all concurrent open writers plus every completed extension waiting for its `commitPack()` callback.

During adaptive publication, bytes temporarily exist both in the local staging file and in invisible database rows. Capacity planning must allow that overlap for every concurrent chunked writer. The database transaction log must also accommodate one complete pre-persistence transaction per additive logical pack; payload chunks are flushed in bounded ORM batches, but the transaction commits only after every expected extension is complete.

Normal publication and rollback delete temporary files explicitly. The implementation deliberately does not register every extension with the JVM-wide `deleteOnExit` registry, because that registry grows for the lifetime of a server process. A JVM crash or operating-system deletion failure can leave files with the `jgit-storage-pack-` prefix in the configured temporary directory. They are unpublished derived state and are never treated as durable or resumable Git data. Operators may remove stale prefixed files only after verifying that no matching JVM/process is active.

The chunk format uses a `long` declared file size and does not rely on Java array indexing for the complete payload. Individual chunk indexes are Java integers; with 1 MiB chunks this remains far beyond the capacity envelope currently exercised by the project.

## Capacity verification

Normal pull-request tests cover:

- no database row when an extension merely closes;
- direct atomic multi-extension publication for fully inline logical packs;
- complete invisible writer-token pre-persistence for additive chunked logical packs;
- one lock-free pre-persistence transaction and one short locked visibility transaction;
- exact all-or-nothing publication after every expected chunked and inline extension is present;
- publication mismatch, transaction rollback and token-owned row/chunk cleanup;
- direct locked publication for chunked pack replacement and compaction;
- database-free rollback of purely local staging;
- lease-aware rollback and cleanup of durable prepared rows;
- random reads through an open staging stream;
- inline and multi-chunk staged publication;
- close/reopen and `SessionFactory` restart;
- H2, file-backed/in-memory HSQLDB, PostgreSQL and SQL Server behavior;
- legacy inline and durable-uncommitted-row compatibility;
- repository deletion, replacement and chunk-row rollback.

A separate Maven profile verifies **1 MiB, 16 MiB and 128 MiB** payloads without making every pull request pay the 128 MiB test cost:

```bash
mvn -B -pl jgit-storage-hibernate-core -Ppack-capacity verify
```

The performance workflow executes this profile for manual and weekly scheduled runs. These sizes demonstrate bounded chunk behavior and random access; they are not a universal maximum-pack certification.

Before production use, also test an import or repack representative of the largest expected repository, concurrent readers and writers, backup/restore and the actual database connection-pool, transaction-log and temporary-disk configuration.

## Durable writer tokens and leases

Writer tokens and leases protect every durable uncommitted row, including:

- complete groups pre-persisted by the adaptive chunked-publication path;
- databases containing uncommitted rows produced by an earlier library version;
- direct use of the base `HibernateObjDatabase` contract;
- safe maintenance and rollback of those durable rows.

Every persisted uncommitted extension has a UUID `write_token` and renewable `write_lease_until`. All extensions pre-persisted for one adaptive logical pack share the same token. The pre-persistence transaction owns the complete parent and payload set and renews the group lease immediately before commit.

The base writer verifies ownership on persistence and close. Final publication, direct replacement, ref publication and maintenance use the repository-scoped pessimistic lock. The potentially long additive pre-persistence transaction deliberately does not hold that lock because its rows remain invisible and token-owned.

The staging path may publish a logical pack containing both local staged extensions and legacy durable uncommitted extensions. Such a mixed representation is validated and made committed in the established single locked transaction rather than being converted to the adaptive path.

## Recovering abandoned state

### Local staging files

A hard JVM termination can prevent normal rollback and leave temporary files. They contain no authoritative state. A chunked writer may also have a corresponding complete invisible database group, but the local files still must not be imported or treated as resumable Git packs.

Recover local files through operating-system temporary-directory policy or an operator cleanup that selects stale `jgit-storage-pack-` files after confirming the owning process is gone. The association with JGit's in-memory `DfsPackDescription`, expected extensions and publication callback was lost with the process.

### Uncommitted database rows

A crash can leave invisible `committed=false` rows in two normal forms:

- a complete additive logical pack whose pre-persistence transaction committed but whose final publication did not run;
- an older/base-writer row.

A failure during adaptive pre-persistence itself rolls back the complete database transaction and leaves no durable partial group.

Readers ignore all uncommitted rows. They must never be promoted with direct SQL because the lost process can no longer prove JGit publication state. Use the public maintenance service:

```java
PackCleanupResult result =
    new PackStorageMaintenance(sessionFactory)
        .deleteExpiredUncommittedPacks(
            new RepositoryName("domain-history"),
            Instant.now().minus(Duration.ofHours(24)),
            Instant.now());
```

The operation:

1. ensures the repository coordination row exists;
2. obtains the same pessimistic repository lock used by publication and ref updates;
3. considers only pack names for which **every persisted extension** is old, uncommitted and has no valid lease;
4. excludes any group containing a published, recent or actively leased extension;
5. counts payload rows and bytes, then deletes parent rows in one transaction while the database cascade removes chunks;
6. returns deleted pack rows, chunk rows and declared payload bytes.

Choose `createdBefore` conservatively. It is an operator policy cutoff in addition to the writer lease, not merely a technical default.

## Inspection queries

Read-only inspection of durable staging is useful during incident response:

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

A normal all-inline publication does not appear in this query before commit. A normal additive chunked publication appears only after its complete pre-persistence transaction commits and remains until the short atomic visibility update completes. A current lease means the group may belong to a live writer.

Do not schedule a raw `DELETE FROM git_packs WHERE committed = false`; it bypasses repository coordination, pack-extension grouping and lease checks implemented by `PackStorageMaintenance`.

## Legacy inline rows

Migration intentionally does not rewrite already published inline BLOBs. This keeps upgrades short and avoids a large data rewrite inside schema migration. New small extensions may also remain inline; larger extensions use chunks. Repository deletion and pack replacement remove both representations. Flyway schemas use foreign-key cascade, and disposable `create-drop` schemas generate equivalent behavior through the Hibernate mappings.
