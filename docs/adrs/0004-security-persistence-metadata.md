# ADR-0004: Security persistence metadata and provenance categories

- **Status:** Accepted
- **Date:** 2026-08-19
- **Issue:** #260
- **Related:** ADR-0003, Taxonomy#788

## Context

The Security capability persists several semantically different kinds of data: mutable principals and policy configuration, current relationship rows, credential lifecycle state, append-only audit evidence, and technical policy generations. Core separately owns Git packs, object indexes, refs, locks, leases and staging/publication state.

One generic `lastChangedBy`/`lastChangedAt` convention would incorrectly treat authentication usage, technical cache refresh and Git storage maintenance as human business changes. It would also invite mutable actor columns on pack/object/chunk rows that cannot be attributed meaningfully to one person.

Authorization identity is the stable Security `principalId`. Login name, display name, email, Git author and Git committer are presentation or content metadata and must not be used as actor identity.

## Decision

Every Security-owned Hibernate entity is classified in the machine-readable inventory:

`jgit-storage-hibernate-security/src/main/resources/security-persistence-metadata.tsv`

The inventory is part of the public module artifact and is enforced by a build-time contract test.

### A. Mutable security configuration

Principals, groups, repository grants, ref rules and managed-policy heads use stable actor evidence and UTC timestamps:

```text
created_at
created_by_principal_id
updated_at
updated_by_principal_id
entity_version
```

The creator is immutable. A productive mutation updates modifier, timestamp and optimistic version atomically. Legacy rows whose actor cannot be proven remain explicitly unknown; migrations must not invent a migration account or infer identity from username/email.

### B. Relationship rows

Group membership and similar current relationships are replace/delete records. Creation stores a stable creator principal. Removal and source/role transitions require append-only management audit because the current row no longer exists after deletion.

### C. Credential lifecycle

Passwords and access tokens use purpose-specific fields such as `changed_at`, `issued_at`, `last_used_at`, `expires_at`, `revoked_at`, lockout counters and security versions. Operational use must not masquerade as an administrative edit. Issuance, rotation, unlock and revocation are correlated through immutable identity audit.

### D. Append-only audit

Authorization and identity audit entities are Hibernate `@Immutable`, have no generic modifier columns and expose no update API. Events contain bounded, non-secret actor/subject, operation, outcome, reason, policy and correlation evidence. Credentials, Authorization headers, password/token verifiers, raw JWTs/claims and Git content are excluded.

### E. Technical policy state

Security generations and invalidation checkpoints use monotonic technical fields. Automated observation/refresh does not acquire a human `updated_by` value. A human actor is recorded only on the authoritative configuration mutation that caused the generation change.

### F. Core Git storage exclusion

Security migrations must not add human actor columns to Core-owned pack, chunk, object/index, ref coordination, lock, lease, staging or publication tables. Cross-layer attribution uses Git commit/reflog evidence plus Security audit and operation/correlation IDs at the secured boundary.

## Naming

- timestamps: `*_at`, mapped to UTC `Instant` semantics;
- stable actor: `*_by_principal_id` or `actor_principal_id`;
- affected identity: `subject_principal_id` where distinct;
- concurrency: `entity_version`;
- authorization invalidation: `security_version` or `policy_generation`;
- cross-layer tracing: bounded `operation_id` and `correlation_id`.

New synonyms such as `lastTimeChanged`, `modifiedOn`, `changedBy`, username- or email-based actor columns require an explicit compatibility decision.

## Enforcement

`SecurityPersistenceMetadataContractTest` verifies that:

1. every class returned by `SecurityEntities.annotatedClasses()` occurs exactly once in the inventory and maps to the declared table;
2. append-only audit entities remain immutable and unversioned as events;
3. ambiguous legacy actor columns are recorded as migration gaps rather than silently accepted;
4. the known migration-gap set changes only together with schema/API work;
5. Security Flyway migrations reference only Security-owned `git_security_*` objects.

Future migrations must support H2, HSQLDB, PostgreSQL and SQL Server, preserve historical timestamps, and leave unknowable legacy actors unknown.

## Consequences

The inventory makes remaining provenance gaps explicit instead of pretending the current schema is already complete. Follow-up changes can remove one `migration_required` marker only when the entity mapping, all dialect migrations, mutation API, audit behavior and upgrade tests are delivered together.

Core-only consumers remain unaffected, and disabling the optional Security module creates no Security metadata or migration work.
