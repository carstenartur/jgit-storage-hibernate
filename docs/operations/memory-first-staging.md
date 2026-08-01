# Memory-first pack-extension staging

Small JGit PACK, IDX and Reftable extensions begin in random-readable heap memory instead of creating a temporary file immediately.

- one extension retains at most 256 KiB;
- all repository instances share a 32 MiB process budget;
- a narrower owner budget can force an earlier spill in tests or specialized integrations;
- memory grows from a 16-KiB initial reservation, reducing global-budget contention for ordinary small extensions;
- when either bound is exceeded, the written prefix is copied once to a temporary file and all later writes remain file-backed;
- positional reads retain the same `DfsOutputStream` behavior before and after spill;
- publication and rollback release the reservation or delete the temporary file exactly once.

The merged `StorageByteMetrics` make the intended effect observable. A memory-only inline publication records database payload bytes but zero temporary-file bytes. A spilled extension records the exact file bytes written and reread during publication.

Memory-first staging changes only derived unpublished state. Database visibility, the adaptive one-MiB two-phase selector, writer leases, repository locks and replacement race protection remain unchanged.
