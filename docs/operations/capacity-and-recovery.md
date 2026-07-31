# Pack capacity and recovery

## Payload and publication model

Core separates binary construction, payload persistence and transactional visibility:

- open JGit `PACK`, `IDX`, object-size-index, bitmap and Reftable writers use bounded temporary files;
- JGit can perform random reads through the still-open `DfsOutputStream` while constructing or resolving an extension;
- closing an extension stages the completed temporary file in the repository instance but creates no database row;
- `commitPack()` keeps extensions up to 256 KiB on the existing single-transaction path;
- larger extensions are grouped into one payload transaction and persisted as leased `committed=false` rows before the repository publication lock is acquired;
- the short locked transaction validates prepared row identity, writer token, declared size, chunked representation and active lease;
- that same locked transaction deletes replaced packs, writes remaining inline extensions and switches the complete new generation to `committed=true` atomically;
- `git_packs` owns repository, pack name, extension, declared size, writer lease and committed publication metadata;
- committed small extensions remain inline in `git_packs.data`;
- committed larger extensions use ordered **1 MiB** rows in `git_pack_chunks`;
- existing inline, chunked and legacy uncommitted rows remain compatible without a destructive data migration.

Readers filter on `committed=true`. They therefore see neither a partly transferred extension nor a partly published logical pack. A failure in the payload transaction rolls back all prepared rows and chunks. A failure in the final publication transaction leaves the prepared rows invisible and triggers a token-scoped cleanup transaction; if the process terminates before cleanup, the active lease protects those rows until operator maintenance may safely reclaim them.

The repository lifecycle foreign key is separate from the publication-lock row. A concurrent repository deletion either removes a pre-persisted generation through the database cascade or causes a later insert/publication to fail atomically. Payload transfer does not need to hold or reference the pessimistically locked coordination row.

This removes the requirement that the largest individual extension fit into one Java byte array and removes large chunk transfer from the repository-scoped lock interval. It is still not a claim that repository size is unlimited: database throughput, temporary-disk capacity, transaction-log capacity, JGit caches and concurrent work remain deployment constraints.

## Sandbox predecessor review

The earlier implementation in `carstenartur/sandbox` buffered complete payloads in a `ByteArrayOutputStream`, loaded them as a single `byte[]` and contained an explicit TODO for direct database streaming. There was therefore no more advanced implementation to copy. The current design was implemented independently and retains database portability instead of depending on one vendor's large-object API.

## Memory and temporary-disk envelope

The production path uses:

- one temporary file per open or completed-but-unpublished pack extension;
- a 1 MiB transfer buffer while persisting chunked extensions;
- a bounded Hibernate batch of payload chunks;
- a bounded multi-chunk read-ahead window per open chunked reader.

Temporary files remain until the corresponding logical pack is committed or rolled back, not merely until each extension stream closes. The directory used by `Files.createTempFile(...)` must therefore have enough free space for all concurrent open writers plus every completed extension waiting for its `commitPack()` callback.

Normal publication and rollback delete temporary files explicitly. The implementation deliberately does not register every extension with the JVM-wide `deleteOnExit` registry, because that registry grows for the lifetime of a server process. A JVM crash or operating-system deletion failure can leave files with the `jgit-storage-pack-` prefix in the configured temporary directory. They are unpublished derived state and are never treated as durable or resumable Git data. Operators may remove stale prefixed files only after verifying that no matching JVM/process is active.

The chunk format uses a `long` declared file size and does not rely on Java array indexing for the complete payload. Individual chunk indexes are Java integers; with 1 MiB chunks this remains far beyond the capacity envelope currently exercised by the project.

## Capacity and transaction verification

Normal pull-request tests cover:

- no database row before logical-pack publication begins;
- one locked transaction for a purely inline logical pack;
- invisible pre-persistence of chunked extensions before repository-lock acquisition;
- atomic mixed inline/chunked publication;
- active writer token and lease assignment for prepared rows;
- transaction rollback after a later extension fails;
- token-scoped removal of prepared rows and payload chunks after final publication failure;
- database-free rollback of purely local staging;
- random reads through an open staging stream;
- close/reopen and `SessionFactory` restart;
- H2, file-backed/in-memory HSQLDB, PostgreSQL and SQL Server behavior;
- repository deletion, replacement and chunk-row cascade;
- independent repository handles contending on one database lock row.

A separate Maven profile verifies **1 MiB, 16 MiB and 128 MiB** payloads without making every pull request pay the 128 MiB test cost:

```bash
mvn -B -pl jgit-storage-hibernate-core -Ppack-capacity verify
```

The performance workflow executes this profile for manual and weekly scheduled runs. These sizes demonstrate bounded chunk behavior and random access; they are not a universal maximum-pack certification.

Before production use, also test an import or repack representative of the largest expected repository, concurrent readers and writers, backup/restore and the actual database connection-pool, transaction-log and temporary-disk configuration.

## Leased uncommitted rows

Every pre-persisted extension has a UUID `write_token` and renewable `write_lease_until`. All chunked extensions prepared by one logical `commitPack()` call share a token. The final publication transaction accepts a prepared row only when all of the following still match:

- repository, pack name and extension;
- generated row ID;
- declared file size;
- chunked representation (`data is null`);
- `committed=false`;
- writer token;
- non-expired lease.

The normal ReadAhead backend now creates such rows only after every selected temporary extension file has closed and JGit has entered logical-pack publication. Inline extensions stay JVM-local until the final locked transaction. Legacy rows produced by the base `HibernateObjDatabase` or an earlier release remain supported by the same schema and maintenance rules.

A logical pack may contain all three supported representations during publication:

1. prepared chunked extensions owned by the current writer token;
2. local inline extensions written inside the final transaction;
3. compatible legacy durable uncommitted extensions.

They become visible together or not at all.

## Recovering abandoned state

### Local staging files

A hard JVM termination can prevent normal rollback and leave temporary files. They contain no authoritative state. Recover them through operating-system temporary-directory policy or an operator cleanup that selects stale `jgit-storage-pack-` files after confirming the owning process is gone.

Do not attempt to import such files as packs. The association with JGit's in-memory `DfsPackDescription`, expected extensions and publication transaction was lost with the process.

### Uncommitted database rows

A crash between payload persistence and final publication can leave invisible `committed=false` rows and chunks. Readers ignore them. Use the public maintenance service rather than direct SQL:

```java
PackCleanupResult result =
    new PackStorageMaintenance(sessionFactory)
        .deleteExpiredUncommittedPacks(
            new RepositoryName("domain-history"),
            Instant.now().minus(Duration.ofHours(24)),
            Instant.now());
```

The operation:

1. ensures the repository coordination state exists;
2. obtains the same pessimistic repository lock used by publication and ref updates;
3. considers only pack names for which **every persisted extension** is old, uncommitted and has no valid lease;
4. excludes any group containing a published, recent or actively leased extension;
5. counts payload chunks before deleting parent rows through the database cascade;
6. returns deleted pack rows, chunk rows and declared payload bytes.

Choose `createdBefore` conservatively. It is an operator policy cutoff in addition to the writer lease, not merely a technical default. A normal publication may appear briefly as `committed=false`; an active lease means it must not be deleted.

## Inspection queries

Read-only inspection of prepared or abandoned staging:

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

Interpret this query together with `write_lease_until` and application activity. Chunked publication intentionally creates short-lived matching rows between payload persistence and final visibility. Do not schedule a raw `DELETE FROM git_packs WHERE committed = false`; it bypasses repository locking, pack-extension grouping, writer ownership and lease checks implemented by `PackStorageMaintenance`.

## Legacy inline rows

Migration intentionally does not rewrite already published inline BLOBs. This keeps upgrades short and avoids a large data rewrite inside schema migration. New small extensions may also remain inline; larger extensions use chunks. Repository deletion and pack replacement remove both representations. Flyway schemas use foreign-key cascade, and disposable `create-drop` schemas generate equivalent behavior through the Hibernate mappings.
