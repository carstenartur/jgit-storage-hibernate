# ADR 0004: Security persistence metadata and provenance

- Status: Accepted
- Date: 2026-08-18
- Issue: [#260](https://github.com/carstenartur/jgit-storage-hibernate/issues/260)

## Context

The Security module persists several fundamentally different kinds of data: mutable authorization
configuration, relationships, credential state, append-only audit evidence and technical invalidation
state. Treating all of them as generic mutable entities leads to inconsistent actor columns,
misleading timestamps, accidental audit updates and migrations that are difficult to review.

Actor identity is also different from a mutable login name, e-mail address or display label. A
persisted actor reference must use the stable Security principal identifier. Human-readable labels
may be retained only as bounded audit snapshots and must never replace the stable identifier.

Core Git storage remains transport- and identity-neutral. Security provenance belongs in the optional
Security module; Security migrations must not add identity columns to Core storage tables.

## Decision

Every Security entity/table is classified in the machine-readable
`jgit-storage-hibernate-security/src/main/resources/security-persistence-inventory.tsv` inventory.
The inventory is checked against `SecurityEntities.annotatedClasses()` so a newly added entity cannot
remain unclassified.

The categories and required lifecycle contracts are:

1. **Mutable configuration** — principals, groups, repository grants, ref rules and managed-policy
   synchronization state. New writes use stable `created_by_principal_id` and
   `updated_by_principal_id` provenance together with creation/update time and optimistic versioning.
2. **Relationship rows** — group membership and similar links. Creation/removal is attributed to a
   stable principal; removal may be represented by append-only identity audit when the row is
   physically deleted.
3. **Credential rows** — local password verifier state and access tokens. They keep specialized
   semantics such as password change actor, token issuer, token revoker, expiry and revocation. They
   never persist plaintext secrets.
4. **Audit rows** — access and identity audit. They are append-only. Actor and subject identifiers are
   immutable event evidence; optional labels are snapshots, not foreign identity keys.
5. **Technical state** — monotonic invalidation/version records. They are system-managed and are not
   presented as business configuration.

Public management operations continue to carry an authenticated `GitAccessContext` through
`SecurityManagementRequest`; this stable principal-bound context is the canonical actor input. APIs
must not accept a free-form username/e-mail string as authoritative actor provenance.

Database-native defaults may populate non-semantic creation timestamps for legacy imports, but they
must not invent an actor. Existing rows with unknown historical actors remain explicitly unknown
until a documented adoption process supplies trustworthy evidence.

## Enforcement

Architecture tests fail when:

- a Security entity is absent from or duplicated in the inventory;
- an inventory category uses an unsupported lifecycle or actor policy;
- mutable configuration omits the stable-principal provenance policy;
- audit tables are not classified append-only;
- actor naming uses mutable labels such as username, login or e-mail;
- a Security migration references a Core persistence table.

Migration tests and service tests remain responsible for proving concrete columns, backward-compatible
adoption and actor propagation for each write path. The inventory is the source of truth for deciding
which policy applies before schema work begins.

## Consequences

Security metadata changes become reviewable as category-specific contracts instead of ad hoc columns.
The same actor model can be propagated through Smart HTTP, application services and policy
synchronization without coupling Core to Servlet, OIDC or application-specific user tables.

The first implementation establishes classification and architecture gates. Concrete schema and
service changes may be delivered category by category, but cannot silently change a table's lifecycle
or actor policy without updating this ADR-backed inventory and its tests.
