# Pack capacity and recovery

## Payload model

Core separates transactional publication metadata from binary payload storage:

- `git_packs` owns the repository, pack name, extension, declared size, publication state and writer lease;
- `git_pack_chunks` stores new payloads in ordered **1 MiB chunks**;
- existing rows with an inline `git_packs.data` value remain readable after migration;
- new writes leave `git_packs.data` null and use the chunk table.

The writer no longer accumulates a complete pack-related file in a `ByteArrayOutputStream`. It writes to a temporary file, preserving the random-read behavior JGit needs while constructing packs, and transfers bounded chunks to Hibernate during flush. The reader loads at most one payload chunk at a time through the JGit `ReadableChannel`.

This removes the previous requirement that the largest individual `PACK`, `IDX`, bitmap or Reftable file fit into one Java byte array. It is still not a claim that repository size is unlimited: database throughput, transaction duration, temporary-disk capacity, JGit caches and concurrent work remain deployment constraints.

## Sandbox predecessor review

The earlier implementation in the `carstenartur/sandbox` repository was reviewed before this design was added. It also buffers complete payloads in a `ByteArrayOutputStream`, loads them as a single `byte[]` and contains an explicit TODO for direct database streaming. There was therefore no more advanced implementation to copy. The current design was implemented independently in this repository and retains compatibility with H2, HSQLDB and PostgreSQL instead of depending on one database vendor's large-object API.

## Memory and temporary-disk envelope

The production path uses:

- one temporary file per open pack-extension writer;
- a 1 MiB transfer buffer;
- a bounded Hibernate batch of payload chunks;
- one cached 1 MiB chunk per open chunked reader.

Temporary files exist until the corresponding JGit output stream closes. The directory used by `Files.createTempFile(...)` must therefore have enough free space for concurrent in-progress pack extensions. Monitor both JVM memory and temporary-disk space.

The chunk format uses a `long` declared file size and does not rely on Java array indexing for the complete payload. Individual chunk indexes are still Java integers; with 1 MiB chunks this limit is far beyond the capacity envelope currently exercised by the project.

## Capacity verification

Normal pull-request tests cover:

- writes spanning several chunks;
- write → flush → write → close behavior;
- random reads across chunk boundaries;
- close/reopen and `SessionFactory` restart;
- H2, file-backed/in-memory HSQLDB and PostgreSQL migrations;
- legacy inline payload compatibility;
- repository deletion and rollback of chunk rows.

A separate Maven profile verifies **1 MiB, 16 MiB and 128 MiB** payloads without making every pull request pay the 128 MiB test cost:

```bash
mvn -B -pl jgit-storage-hibernate-core -Ppack-capacity verify
```

The existing performance workflow executes this profile for manual and weekly scheduled runs. These sizes demonstrate bounded chunk behavior and random access; they are not a universal maximum-pack certification.

Before production use, also test an import or repack representative of the largest expected repository, concurrent readers and writers, backup/restore and the actual database connection-pool and disk configuration.

## Writer leases

Every persisted uncommitted pack extension has:

- a UUID `write_token` identifying the writer;
- a renewable `write_lease_until` timestamp.

The lease is renewed while a persisted writer continues working. Flush and close verify that the row is still owned by the same token. Pack flush, publication, rollback, ref publication and cleanup use the same repository-scoped pessimistic database lock.

A lease protects a slow active writer, but it is not a substitute for reasonable operational limits. A process paused longer than the lease duration can lose ownership after an operator cleanup. When it resumes, its next persistence attempt fails instead of silently overwriting another writer's state.

## Recovering abandoned uncommitted packs

Normal JGit rollback removes uncommitted rows. A process crash can prevent that callback and leave invisible storage behind. Readers continue to ignore those rows because `committed=false`.

Use the public maintenance service rather than direct SQL:

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
2. obtains the same pessimistic repository lock used by writers and ref publication;
3. considers only pack names for which **every persisted extension** is old, uncommitted and has no valid lease;
4. excludes any group containing a published, recent or actively leased extension;
5. deletes chunk rows and metadata in one transaction;
6. returns deleted pack rows, chunk rows and declared payload bytes.

Choose `createdBefore` conservatively. It is an operator policy cutoff in addition to the writer lease, not merely a technical default.

## Inspection queries

Read-only inspection remains useful:

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

Do not schedule a raw `DELETE FROM git_packs WHERE committed = false`. It bypasses the repository lock, pack-extension grouping and lease checks implemented by `PackStorageMaintenance`.

## Legacy inline rows

Migration intentionally does not rewrite already published inline BLOBs. This keeps upgrades short and avoids a large data rewrite inside schema migration. New writes are chunked; old rows are read through the compatibility channel until a future explicit repack replaces them.

Repository deletion and pack replacement remove both inline rows and chunk rows. The Flyway schemas use foreign-key cascade, and the Hibernate mappings generate equivalent cascade behavior for disposable `create-drop` test schemas.
