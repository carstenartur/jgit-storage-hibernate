# Pack capacity and recovery

## Payload and publication model

Core separates binary construction from transactional publication:

- open JGit `PACK`, `IDX`, object-size-index, bitmap and Reftable writers use bounded temporary files;
- JGit can perform random reads through the still-open `DfsOutputStream` while constructing or resolving an extension;
- closing an extension stages the completed temporary file in the repository instance but creates no database row;
- logical packs whose extensions all remain at or below the 256-KiB inline threshold are persisted and published in one repository-locked Hibernate transaction;
- logical packs containing at least one chunked extension use an adaptive three-phase path: reserve invisible parents under a short lock, transfer payloads without the repository lock, then atomically publish the complete generation under a second short lock;
- `git_packs` owns repository, pack name, extension, declared size, writer lease and committed publication metadata;
- small committed extensions remain inline in `git_packs.data`;
- larger committed extensions use ordered **1 MiB** rows in `git_pack_chunks`;
- existing inline, chunked and legacy uncommitted rows remain compatible without a destructive migration.

Readers select only `committed=true` rows. A chunked logical pack can therefore have complete parent and payload rows before publication without exposing a partial generation to JGit.

### Adaptive chunked publication

For a fully local logical pack with at least one extension above the inline threshold, `commitPack()` performs:

1. a short `PACK_EXTENSION_WRITE` transaction that acquires the repository lock, reserves every expected extension with one UUID writer token and a current lease, and commits no payload bytes;
2. a lock-free `PACK_EXTENSION_WRITE` transaction that verifies the complete token-owned parent set, renews the lease, writes inline bytes and inserts bounded chunk rows;
3. a short `PACK_PUBLICATION` transaction that acquires the repository lock, removes replaced packs and changes every expected token-owned row to `committed=true` in one bulk update.

The final update count must match the complete expected extension set. A missing or changed row aborts the publication transaction, so replacement deletion and visibility change roll back together. Normal failure handling then deletes every remaining token-owned uncommitted parent; the database foreign-key cascade removes its chunks.

The first short lock prevents concurrent repository mutation from crossing parent reservation. Payload transfer is deliberately outside that lock. The final lock remains the sole visibility boundary and continues to serialize pack replacement with ref publication, repository deletion and maintenance.

A logical pack containing a legacy durable-uncommitted extension stays on the established single locked publication path. The adaptive path is used only when every expected extension is locally staged and can be reserved as one complete writer-owned group.

No partially persisted staged extension is visible to readers. A database rollback of payload transfer leaves only invisible reserved parents, which normal cleanup removes. A hard process termination may leave those parents and any already committed chunks for lease-aware maintenance.

This removes the requirement that the largest individual extension fit into one Java byte array and prevents large database transfer from occupying the repository publication lock. It is still not a claim that repository size is unlimited: database throughput, temporary-disk capacity, JGit caches, transaction log capacity and concurrent work remain deployment constraints.

## Sandbox predecessor review

The earlier implementation in `carstenartur/sandbox` buffered complete payloads in a `ByteArrayOutputStream`, loaded them as a single `byte[]` and contained an explicit TODO for direct database streaming. There was therefore no more advanced implementation to copy. The current design was implemented independently and retains database portability instead of depending on one vendor's large-object API.

## Memory and temporary-disk envelope

The production path uses:

- one temporary file per open or completed-but-unpublished pack extension;
- a 1 MiB transfer buffer while publishing chunked extensions;
- a bounded Hibernate batch of payload chunks;
- a bounded multi-chunk read-ahead window per open chunked reader.

Temporary files remain until the corresponding logical pack is committed or rolled back, not merely until each extension stream closes. The directory used by `Files.createTempFile(...)` must therefore have enough free space for all concurrent open writers plus every completed extension waiting for its `commitPack()` callback.

During adaptive publication, bytes temporarily exist both in the local staging file and in invisible database rows. Capacity planning must allow that overlap for every concurrent chunked writer. The database transaction log must also accommodate one payload-transfer transaction per logical pack; payload chunks are flushed in bounded ORM batches, but visibility remains transactional.

Normal publication and rollback delete temporary files explicitly. The implementation deliberately does not register every extension with the JVM-wide `deleteOnExit` registry, because that registry grows for the lifetime of a server process. A JVM crash or operating-system deletion failure can leave files with the `jgit-storage-pack-` prefix in the configured temporary directory. They are unpublished derived state and are never treated as durable or resumable Git data. Operators may remove stale prefixed files only after verifying that no matching JVM/process is active.

The chunk format uses a `long` declared file size and does not rely on Java array indexing for the complete payload. Individual chunk indexes are Java integers; with 1 MiB chunks this remains far beyond the capacity envelope currently exercised by the project.

## Capacity verification

Normal pull-request tests cover:

- no database row when an extension merely closes;
- direct atomic multi-extension publication for fully inline logical packs;
- invisible writer-token reservation and lock-free payload transfer for chunked logical packs;
- exact all-or-nothing publication after every expected chunked and inline extension is present;
- publication mismatch, transaction rollback and token-owned row/chunk cleanup;
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

- parents reserved by the adaptive chunked-publication path;
- databases containing uncommitted rows produced by an earlier library version;
- direct use of the base `HibernateObjDatabase` contract;
- safe maintenance and rollback of those durable rows.

Every persisted uncommitted extension has a UUID `write_token` and renewable `write_lease_until`. All extensions reserved for one adaptive logical pack share the same token. Payload transfer verifies the exact token-owned parent IDs and declared sizes before writing bytes, and renews the group lease before chunk insertion.

The base writer verifies ownership on persistence and close. Pack reservation, final publication, ref publication and maintenance use the repository-scoped pessimistic lock. The potentially long payload-transfer transaction deliberately does not hold that lock because its rows remain invisible and token-owned.

The staging path may publish a logical pack containing both local staged extensions and legacy durable uncommitted extensions. Such a mixed representation is validated and made committed in the established single locked transaction rather than being converted to the adaptive path.

## Recovering abandoned state

### Local staging files

A hard JVM termination can prevent normal rollback and leave temporary files. They contain no authoritative state. A chunked writer may also have corresponding invisible database rows, but the local files still must not be imported or treated as resumable Git packs.

Recover local files through operating-system temporary-directory policy or an operator cleanup that selects stale `jgit-storage-pack-` files after confirming the owning process is gone. The association with JGit's in-memory `DfsPackDescription`, expected extensions and publication callback was lost with the process.

### Uncommitted database rows

A crash can leave invisible `committed=false` rows in several states:

- reserved parents with no payload yet;
- a partially transferred group whose payload transaction rolled back;
- a complete inline/chunked group whose process stopped before final publication;
- an older/base-writer row.

Readers ignore all of them. They must never be promoted with direct SQL because the lost process can no longer prove the complete expected extension set or JGit publication state. Use the public maintenance service:

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

A normal all-inline publication does not appear in this query before commit. A normal chunked publication can appear briefly after parent reservation and until the atomic visibility update completes. A current lease means the group may belong to a live writer.

Do not schedule a raw `DELETE FROM git_packs WHERE committed = false`; it bypasses repository coordination, pack-extension grouping and lease checks implemented by `PackStorageMaintenance`.

## Legacy inline rows

Migration intentionally does not rewrite already published inline BLOBs. This keeps upgrades short and avoids a large data rewrite inside schema migration. New small extensions may also remain inline; larger extensions use chunks. Repository deletion and pack replacement remove both representations. Flyway schemas use foreign-key cascade, and disposable `create-drop` schemas generate equivalent behavior through the Hibernate mappings.
