# Bulk transfer preparation

The two spikes use jgit-storage-hibernate `f8c2328f831e1ea8447abd84e1146ed8f976663d`.
They are isolated from application defaults.

## PostgreSQL binary COPY

[The encoder](CopyBinaryEncoder.java) and [the schema test](verify-copy.mjs)
use the actual PostgreSQL Flyway DDL for the chunk table. PostgreSQL 18.6
in PGlite checked a 257-byte tail, SHA-256, legacy generated IDs, rollback
on truncation, uniqueness and foreign-key errors, rollback after a
successful load, and retry. See [the results](copy-result.txt). The
PGlite TCP bridge reset the pgjdbc COPY attempt. A native pgjdbc transfer
and a performance comparison remain open.

To replay, place the two sources in a scratch directory with Node.js and
`@electric-sql/pglite@0.5.15` installed, compile the encoder with Java 21,
and run `node verify-copy.mjs <postgresql-migration-directory>`. The
migration directory contains the PostgreSQL versions of
`V0_1_4__create_core_schema.sql` and
`V0_1_14_1__add_chunked_pack_storage.sql`.

The integration seam is `StagedPackExtensionStore.persistChunks`. Flush
the parent before writing. Stream binary rows through a pgjdbc `CopyIn`
on the same Hibernate transaction connection. Qualify and verify the
mapped table, check the returned row count, and abort the transaction
on partial transfer. Use the existing JDBC path when the configured
backend cannot use COPY. Compare the native opt-in with Stateless
batching on the existing staging and publication fixture, including a
128 MiB payload with a 257-byte tail at 0 and 20 ms added RTT.

## Pack stream transfer

[The patch](pack-stream-spike.patch) imports a non-thin pack containing
the exact missing object set. The pack is spooled to disk and the target
parser checks the IDs before the existing atomic ref update. The 15
existing H2 transfer tests passed. A separate test passed with a 20 MiB
plus 257-byte payload, source deletion, and a cold target SHA-256 check.
The patch includes that test.

To try it, apply the patch to a clean worktree at the base commit and
run the Core Maven tests for `RepositoryTransferH2Test`,
`RepositoryIncrementalTransferH2Test`, `RepositoryTransferFailureH2Test`,
and `PackStreamLargeH2Test`. Validate a native PostgreSQL clone,
incremental update and no-op retry before choosing a production
strategy or making a throughput claim.

Assisted by OpenAI Codex.
