# Adaptive pack publication

Core chooses the publication transaction shape from the complete logical pack. The default `size`
selector uses a fixed threshold of 1 MiB of total staged payload.

## Direct path

The original single repository-locked transaction remains in use when any of these conditions applies:

- every extension fits the inline payload column;
- the publication replaces existing packs, including repack and compaction;
- an expected extension comes from the legacy durable-uncommitted compatibility path;
- under the default size rule, the logical pack contains a chunked extension but its total locally staged payload is below 1 MiB.

This avoids paying for a second database transaction when the payload is small and the lock interval it could remove is correspondingly short. Replacement stays direct because JGit can validate refs before `commitPack()`; deleting source packs after an unlocked interval would allow a conflicting ref update to cross that validation boundary.

## Two-phase path

Under the default size rule, an additive, fully JVM-local logical pack uses pre-persistence when:

- at least one extension exceeds the 256 KiB inline limit; and
- total staged payload is at least 1 MiB.

The first transaction writes the complete logical extension set as `committed=false`, using one writer token and renewable lease. It does not acquire the repository publication lock. The second, short transaction acquires that lock, validates the prepared group and atomically changes the complete generation to `committed=true`.

Readers continue to select only committed rows. A crash after the first transaction can therefore leave durable but invisible state; token- and lease-based maintenance may reclaim it after expiry. A failed final publication rolls back visibility and performs exact token-scoped cleanup.

## Optional contention policy

Set these Hibernate `SessionFactory` properties to try the contention selector:

```properties
jgit.storage.hibernate.pack.publication_selector=contention-aware
jgit.storage.hibernate.metrics.enabled=true
jgit.storage.hibernate.pack.prepersist_min_payload_bytes=1048576
```

The size threshold is optional and must be at least 262,145 bytes, the first chunked
extension size. The selector uses the configured size threshold when it starts, when
metrics are disabled, while the repository is quiet, and inside an enclosing transaction.
`publication_selector=size` always uses that rule. All-inline, replacement, and
legacy/mixed publications remain direct under either policy.

With metrics enabled, three consecutive high-contention completions can lower the threshold to
the first chunked extension size. A high sample requires another eligible local pack writer overlapping
the final lock attempt, a smoothed overlap count of at least 0.5, and both the observed and
smoothed lock acquisition time to reach the larger of 2 ms and one eighth of the corresponding
publication duration. The smoothed averages give each new sample 25% weight. The overlap check
prevents high database RTT alone from promoting independent writers. The threshold returns to the
size rule after eight quiet completions once smoothed overlap is below 0.5, or after one minute
since the last completion when there is no in-flight eligible publication. Failed publications do
not train the policy.

The policy keeps one tagged repository entry in each of 256 stripes per JVM. It shares observations
between repository handles whose JDBC endpoint, driver, user, schema and catalog settings match,
including explicit Hikari overrides. With a data source, custom connection provider, driver-specific
connection properties or connection initialization SQL, observations are scoped to that session
factory: those settings can change the effective database or schema without changing the URL.
A stripe collision falls back to the size rule while the other repository
has work in flight; an idle entry may be replaced. It never contributes samples to the colliding
repository. The signal does not attempt to count writers in other JVMs.

`HibernateRepository.getPackPublicationPolicySnapshot()` exposes the last path and reason on the
handle, both the fallback and current thresholds, and the recent lock acquisition, publication
duration, overlapping writer and payload averages. The current threshold applies to the next
eligible top-level publication. `getPackPublicationSelectionMetrics()` still reports cumulative
selection and payload byte counters. Both stop recording selections when metrics are disabled.

## Why one MiB remains the default

The threshold is intentionally conservative rather than a universal database constant. Four-thread measurements showed that two-phase publication improves shared-repository contention, while the extra transaction can reduce throughput for independent small writes. One MiB keeps just-over-inline packs on the direct path while retaining lock-free payload transfer for the established 12 MiB large-pack workload.

The [PostgreSQL policy comparison](../evidence/pack-publication-2026-11-07/README.md) measured
a shared-repository gain at eight writers, 768 KiB and roughly 5.8 ms RTT. It did not establish
non-regression across independent repositories or the full payload and RTT matrix.

Any tuning must preserve these invariants:

- no successful acknowledgement before the authoritative database commit;
- no partial logical-pack visibility;
- no unlocked replacement interval;
- bounded unpublished-state cleanup;
- independent repositories remain able to write concurrently.
