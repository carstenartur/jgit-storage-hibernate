# Adaptive pack publication

Core chooses the publication transaction shape from the complete logical pack rather than from one extension in isolation.

## Direct path

The original single repository-locked transaction remains in use when any of these conditions applies:

- every extension fits the inline payload column;
- the publication replaces existing packs, including repack and compaction;
- an expected extension comes from the legacy durable-uncommitted compatibility path;
- the logical pack contains a chunked extension, but the sum of all locally staged extension bytes is below 1 MiB.

This avoids paying for a second database transaction when the payload is small and the lock interval it could remove is correspondingly short. Replacement stays direct because JGit can validate refs before `commitPack()`; deleting source packs after an unlocked interval would allow a conflicting ref update to cross that validation boundary.

## Two-phase path

An additive, fully JVM-local logical pack uses pre-persistence when:

- at least one extension exceeds the 256 KiB inline limit; and
- total staged payload is at least 1 MiB.

The first transaction writes the complete logical extension set as `committed=false`, using one writer token and renewable lease. It does not acquire the repository publication lock. The second, short transaction acquires that lock, validates the prepared group and atomically changes the complete generation to `committed=true`.

Readers continue to select only committed rows. A crash after the first transaction can therefore leave durable but invisible state; token- and lease-based maintenance may reclaim it after expiry. A failed final publication rolls back visibility and performs exact token-scoped cleanup.

## Why one MiB

The threshold is intentionally conservative rather than a universal database constant. Four-thread measurements showed that two-phase publication improves shared-repository contention, while the extra transaction can reduce throughput for independent small writes. One MiB keeps just-over-inline packs on the direct path while retaining lock-free payload transfer for the established 12 MiB large-pack workload.

Future tuning should use observed byte volume and repository-lock contention. It must preserve the following invariants:

- no successful acknowledgement before the authoritative database commit;
- no partial logical-pack visibility;
- no unlocked replacement interval;
- bounded unpublished-state cleanup;
- independent repositories remain able to write concurrently.
