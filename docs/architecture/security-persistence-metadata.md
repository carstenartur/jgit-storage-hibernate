# Security persistence metadata and provenance

The authoritative machine-readable inventory is
[`security-persistence-inventory.tsv`](../../jgit-storage-hibernate-security/src/main/resources/security-persistence-inventory.tsv).
It classifies every JPA entity registered by `SecurityEntities` and records its lifecycle, actor and
version policy. [ADR 0004](../adrs/0004-security-persistence-metadata-and-provenance.md) explains the
decision.

## Why classification comes first

The same columns do not mean the same thing for every table:

- `updated_at` is appropriate for mutable grants but misleading for append-only audit events;
- token `issued_by` and `revoked_by` are more precise than generic creator/updater names;
- deleting a membership requires removal evidence even though the relationship row no longer exists;
- a technical invalidation version is not a user-visible configuration revision.

The inventory therefore prevents blanket schema conventions from weakening semantics.

## Stable actor identity

Authoritative actor metadata uses the immutable Security principal ID. Login names, e-mail addresses
and display labels may change and are not accepted as actor keys. Audit records may retain a bounded
label snapshot for human interpretation, but the stable principal ID remains authoritative.

Management APIs carry the actor through `SecurityManagementRequest.actor()`. Smart HTTP or an
application authentication adapter produces the principal-bound context; current repository and ref
authorization still runs independently of authentication.

## Core boundary

Core tables contain Git repository, pack, ref, reflog and transaction state. They do not gain user,
username, e-mail or authentication-provider columns. Security provenance lives only in the optional
Security module and its own migrations. An architecture test scans every Security migration against
the Core entity table names to keep that boundary enforceable.

## Adoption sequence

1. classify the table and intended lifecycle in the inventory;
2. add dialect migrations with explicit nullability and legacy-row semantics;
3. update the management service so every productive mutation propagates the stable actor;
4. add migration, persistence and authorization tests;
5. only then make stronger nullability constraints mandatory for newly provisioned schemas.

Historical rows must not be assigned a fabricated actor. Unknown legacy provenance remains unknown or
is populated only by a documented, trustworthy adoption process.
