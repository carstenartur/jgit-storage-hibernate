# Bounded consumer publication follow-up

Root authorized this implementation after PR #463 merged. Base: `495653b9939c8b9cc7280d73cfedbb4ac2b8bb3f`, tree `974c3c0589f0067f0135dbd65e3e6f205591902b`.

## Intent and constraints

Complete the explicit missing workload shapes from #211: concurrent Taxonomy workspace publication and native database-log evidence for Audio. Keep all production APIs, schemas and defaults unchanged. Preserve historical operation names and evidence. Local execution is functional verification only; performance measurements run on dedicated hosted runners. Root publishes and dispatches after independent review.

## Selected approach

Add a separate benchmark-only publication harness using the existing deterministic fixture primitives, JDBC/native telemetry and public repository/Search APIs. This leaves the existing query/rebuild measurement shapes and their manifests unchanged. Modifying the existing Taxonomy state would add parameters to all historical operation coordinates; a standalone harness avoids that unrelated change. Full application execution is unnecessary because #211 explicitly asks for synthetic library fixtures.

Taxonomy starts with the same four workspace histories and publishes one deterministic delta in each, with one or four workers. Total payload, commits, refs and projections are identical; only dispatch parallelism changes. Synchronous start and worker-local JDBC aggregation establish real concurrent work. Verify all old/new refs, complete reachable Git membership and exact projection membership, including after provider reopen. Failures can leave individual workspace commits, but return no successful result. Join workers before closing their repository/provider resources.

Audio publishes the existing deterministic binary artifact plus metadata refs on native PostgreSQL with Search disabled. The narrow pair uses 2 MiB and 16 MiB payloads, one repository, the existing bounded background pressure, and current writer/chunk defaults. Capture native WAL/LSN alongside allocation/GC and ORM/JDBC/storage metrics. Verify blob IDs, content length, refs and reopened readback.

Existing native workflow gains an explicit manual publication-follow-up mode. Freeze release `v0.12.0` and merged-main commit references, overlay the exact same benchmark harness, and record harness/library SHAs plus production-module trees. Proposed production trees equal to main are stated as identical, not counted as an independent implementation. Existing real-consumer workflow_dispatch runs on the same immutable branch commit as the proposed measurement.

## Scope and measurements

- Native Taxonomy: 1k base commits, four active workspaces, workers 1/4, RTT 0/20 ms, one warmup and three measured shots per coordinate.
- Native Audio: 2/16 MiB, one repository, bounded 32 MiB background buffer with one worker. WAL evidence is scoped to the dedicated PostgreSQL instance; cumulative statistics lag and whole-iteration GC remain explicit.
- Larger manual matrices already exist. No full cross-product, general semantic invalidation framework or production optimization is added.

## Verification

Functional tests first demonstrate absent delta publication, same-work equivalence, stale-ref rejection, interrupted/failing worker cleanup and native configuration selection. Existing fixture tests remain green. A release overlay compile/function check verifies the shared harness before publication. Hosted artifacts must be complete, hash-verified and paired with successful real-consumer contracts on the exact proposed commit before claiming completion.
