# Pack capacity and recovery

## Payload and publication model

Core separates binary construction from transactional publication:

- open JGit `PACK`, `IDX`, object-size-index, bitmap and Reftable writers use bounded temporary files;
- JGit can perform random reads through the still-open `DfsOutputStream` while constructing or resolving an extension;
- closing an extension stages the completed temporary file in the repository instance but creates no database row;
- `commitPack()` acquires the repository lock once for the logical pack, persists every expected extension and marks all rows committed in the same Hibernate transaction;
- `git_packs` owns repository, pack name, extension, declared size and committed publication metadata;
- small committed extensions remain inline in `git_packs.data`;
- larger committed extensions use ordered **1 MiB** rows in `git_pack_chunks`;
- existing inline, chunked and legacy uncommitted rows remain compatible without a destructive migration.

No partially persisted staged extension is visible to readers. A database rollback removes every row and chunk inserted by that publication attempt while leaving the local staging files available for JGit's subsequent rollback callback.

This removes the requirement that the largest individual extension fit into one Java byte array. It is still not a claim that repository size is unlimited: database throughput, publication-transaction duration, temporary-disk capacity, JGit caches and concurrent work remain deployment constraints.

## Sandbox predecessor review

The earlier implementation in `carstenartur/sandbox` buffered complete payloads in a `ByteArrayOutputStream`, loaded them as a single `byte[]` and contained an explicit TODO for direct database streaming. There was therefore no more advanced implementation to copy. The current design was implemented independently and retains database portability instead of depending on one vendor's large-object API.

## Memory and temporary-disk envelope

The production path uses:

- one temporary file per open or completed-but-unpublished pack extension;
- a 1 MiB transfer buffer while publishing chunked extensions;
- a bounded Hibernate batch of payload chunks;
- a bounded multi-chunk read-ahead window per open chunked reader.

Temporary files remain until the corresponding logical pack is committed or rolled back, not merely until each extension stream closes. The directory used by `Files.createTempFile(...)` must therefore have enough free space for all concurrent open writers plus every completed extension waiting for its `commitPack()` callback.

Normal publication and rollback delete temporary files explicitly. The implementation deliberately does not register every extension with the JVM-wide `deleteOnExit` registry, because that registry grows for the lifetime of a server process. A JVM crash or operating-system deletion failure can leave files with the `jgit-storage-pack-` prefix in the configured temporary directory. They are unpublished derived state and are never treated as durable or resumable Git data. Operators may remove stale prefixed files only after verifying that no matching JVM/process is active.

The chunk format uses a `long` declared file size and does not rely on Java array indexing for the complete payload. Individual chunk indexes are Java integers; with 1 MiB chunks this remains far beyond the capacity envelope currently exercised by the project.

## Capacity verification

Normal pull-request tests cover:

- no database row before logical-pack publication;
- atomic multi-extension publication with one repository lock;
- transaction rollback after a later extension fails;
- database-free rollback of purely local staging;
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

Before production use, also test an import or repack representative of the largest expected repository, concurrent readers and writers, backup/restore and the actual database connection-pool and temporary-disk configuration.

## Legacy durable writer leases

The normal ReadAhead backend no longer creates durable uncommitted rows while an extension is being built. Writer tokens and leases remain part of the published schema and compatibility path for:

- databases containing uncommitted rows produced by an earlier library version;
- direct use of the base `HibernateObjDatabase` contract;
- safe maintenance and rollback of those durable rows.

Every persisted uncommitted extension has a UUID `write_token` and renewable `write_lease_until`. The base writer verifies ownership on persistence and close. Publication, rollback, ref publication and maintenance use the same repository-scoped pessimistic lock.

The new staging path may publish a logical pack containing both local staged extensions and legacy durable uncommitted extensions. Both representations are validated and made committed in the same transaction. Rollback deletes local files without database work when every expected extension was staged locally, and falls back to locked database cleanup when a legacy extension is involved.

## Recovering abandoned state

### Local staging files

A hard JVM termination can prevent normal rollback and leave temporary files. They contain no authoritative state and have no corresponding committed row. Recover them through operating-system temporary-directory policy or an operator cleanup that selects stale `jgit-storage-pack-` files after confirming the owning process is gone.

Do not attempt to import such files as packs. The association with JGit's in-memory `DfsPackDescription`, expected extensions and publication transaction was lost with the process.

### Legacy uncommitted database rows

A crash of an older/base writer can leave invisible `committed=false` rows. Readers ignore them. Use the public maintenance service rather than direct SQL:

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
5. deletes chunk rows and metadata in one transaction;
6. returns deleted pack rows, chunk rows and declared payload bytes.

Choose `createdBefore` conservatively. It is an operator policy cutoff in addition to the writer lease, not merely a technical default.

## Inspection queries

Read-only inspection of legacy durable staging remains useful:

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

A normal new publication should not appear in this query between extension close and `commitPack()`, because its bytes are still JVM-local. Do not schedule a raw `DELETE FROM git_packs WHERE committed = false`; it bypasses the repository lock, pack-extension grouping and lease checks implemented by `PackStorageMaintenance`.

## Legacy inline rows

Migration intentionally does not rewrite already published inline BLOBs. This keeps upgrades short and avoids a large data rewrite inside schema migration. New small extensions may also remain inline; larger extensions use chunks. Repository deletion and pack replacement remove both representations. Flyway schemas use foreign-key cascade, and disposable `create-drop` schemas generate equivalent behavior through the Hibernate mappings.
