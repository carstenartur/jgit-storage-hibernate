# ADR-0003: Add security as an optional principal-bound capability

## Status

Accepted for staged implementation in issue #233. This ADR fixes the module, identity-propagation, authorization and enforcement boundaries before security entities or protocol adapters are added.

## Context

Core intentionally treats every caller that obtains a `HibernateRepositoryFactory` or unrestricted JGit `Repository` as trusted infrastructure. That is the correct default for an embedded storage library, but multi-user consumers otherwise have to reimplement repository membership, protected refs, credentials, audit evidence and JGit protocol integration independently.

JGit does not own application identities. It does, however, expose repository resolution, upload/receive factories, hooks and ref-update extension points. A reusable security capability can therefore be implemented without changing upstream JGit, provided that identity is propagated explicitly and authorization is rechecked at the storage publication boundary.

The design must preserve these invariants:

- Core remains usable without security, servlet, Spring, password-hashing or identity-provider dependencies.
- Enabling security must never fall back to an implicit anonymous or process principal.
- A principal authorized for one `RepositoryName` cannot access another repository merely because Git object IDs are identical.
- Author and committer metadata are Git content and never authentication evidence.
- Protocol-level checks alone are insufficient because embedded consumers can call JGit directly.
- Long-running Git operations must not turn an authorization result into an indefinite lease after grants are revoked.

## Options considered

### A. Put users, roles and authentication in Core

This gives one apparent entry point but couples storage to application identity, password policy, HTTP/session concepts and consumer-specific roles. It would make every Core consumer pay for capabilities it does not select and would violate the enforced optional-module graph.

Rejected.

### B. Keep all authorization in application controllers

This avoids Core changes, but any code that obtains a raw repository handle can bypass controller checks through `RefUpdate`, `BatchRefUpdate`, repository deletion or storage-native transfer. Protocol and direct-JGit behavior can also diverge.

Rejected as the security boundary. Controller checks remain useful as an early rejection layer only.

### C. Optional security module plus a minimal Core enforcement SPI

A framework-neutral security module owns principals, groups, grants, ref rules, credentials, audit and the secured facade. Core owns only a small dependency-free access-policy contract and invokes it at sensitive repository/ref/lifecycle publication points. Smart HTTP integration lives in another optional module.

Accepted.

## Decision

### Module boundaries

The capability is split as follows:

```text
jgit-storage-hibernate-core
  storage plus a no-op-by-default, dependency-free authorization SPI

jgit-storage-hibernate-security
  principal/group/grant/ref-rule entities and migrations
  authorization evaluator and cache invalidation
  secured repository/session facade
  optional local credential and token services
  framework-neutral audit service

jgit-storage-hibernate-smart-http
  optional later module using JGit GitServlet extension points
  request/principal adapter, repository resolver, upload/receive factories and hooks
```

`security` may depend on Core and Hibernate ORM. It must not depend on Hibernate Search, Spring Security, Jakarta Servlet or an HTTP server. `smart-http` may depend on Core, Security, JGit HTTP support and Jakarta Servlet, but those dependencies must never become transitive from Core or Security.

The raw `HibernateRepositoryFactory` remains available and is explicitly documented as a privileged infrastructure capability. Untrusted request or domain code in a security-enabled application receives only the secured facade.

### Explicit access context

Authenticated identity is passed explicitly through an immutable context. No global `ThreadLocal`, Spring-specific context or ambient process identity is part of the library contract.

The stable minimum is:

```java
public record GitAccessContext(
    String principalId,
    Set<String> groupIds,
    String authenticationMethod,
    String correlationId,
    Map<String, String> attributes) {}
```

Authorization uses `principalId` and group IDs, never mutable login or display names. Context construction validates bounded, immutable values and rejects missing principal IDs. A separately named privileged system context may exist for migrations and repair, but it must be created explicitly and audited.

### Git-generic permissions

The library persists permissions rather than consumer roles:

```text
DISCOVER
READ
CREATE_REF
UPDATE_REF
DELETE_REF
FORCE_UPDATE
ADMINISTER
```

Repository creation and deletion are separate lifecycle decisions; deletion is never implied by `UPDATE_REF`. Applications such as Taxonomy map viewer/contributor/maintainer/owner roles to these generic permissions and ref rules outside the library.

The initial evaluator uses additive repository grants plus rejecting protected-ref rules. If explicit deny grants are added, deny wins over allow. Ref rule precedence is deterministic: higher numeric priority first, then the more specific pattern, then stable rule ID. Ambiguous rules with equal precedence and conflicting outcomes are rejected when configured.

### Principal-bound facade

The security module exposes a facade similar to:

```java
public interface SecuredHibernateRepositoryFactory {
  AuthorizedRepositorySession open(
      RepositoryName repository,
      GitAccessContext accessContext,
      GitRepositoryPermission requiredPermission);
}

public interface AuthorizedRepositorySession extends AutoCloseable {
  RepositoryName repositoryName();
  GitAccessContext accessContext();
  Repository repository();
  void require(GitRepositoryPermission permission);
}
```

Opening requires `DISCOVER`/`READ` as appropriate before an unrestricted object reader can be obtained. The session carries an immutable access context and policy snapshot identifier into the repository instance.

### Core enforcement SPI

Core contains no principals or ACL tables. It accepts an optional policy object with a no-op trusted default. The policy is asked to authorize:

- repository discovery/open for a requested operation;
- `RefUpdate` and every `ReceiveCommand` in `BatchRefUpdate`;
- create, fast-forward update, delete and force-update classification;
- repository deletion;
- logical repository clone/fork/incremental transfer source read and target mutation.

The policy input contains only Core/JGit values: access-context identifier, `RepositoryName`, operation, ref name, old/new `ObjectId` and whether the mutation is forced. Core does not query security tables itself.

A security-enabled repository without an access context fails closed. The final ref decision is rechecked immediately before the atomic database/ref publication boundary. Early controller or receive-pack checks may reject sooner, but they do not replace this check.

Core must not invoke the authorizer per object, blob, pack or chunk. Read authorization is established when opening a principal-bound read session; mutation authorization is evaluated per repository/ref command batch.

### Database ownership

The Security module owns independent entities, migrations and Flyway history:

```text
jgit_storage_hibernate_security_schema_history
```

Initial tables cover:

- stable principals and external issuer/subject identity;
- groups and membership;
- repository grants;
- deterministic ref rules;
- optional local credential hashes;
- one-way access-token hashes and non-secret lookup prefixes;
- bounded audit events.

Security tables use `RepositoryName.value()` as the authorization key but do not require a foreign key to a pack row or first commit. Grants may be provisioned before a repository has Git objects. Generic Hibernate Search indexing must never include credential, token, grant or audit secret material.

Dialect-specific migrations and restart/upgrade tests follow the Core database set: H2, HSQLDB, PostgreSQL and Microsoft SQL Server. Every migrated schema is validated with `hbm2ddl.auto=validate`.

### Authentication

Authentication is pluggable and separate from authorization. External OIDC/LDAP/application adapters may supply an already authenticated stable subject. Local password and personal-access-token support is optional inside Security:

- only salted modern password hashes are stored through a `PasswordHasher` SPI;
- only one-way token hashes plus non-secret prefixes are stored;
- token plaintext is returned once at creation and never logged;
- comparisons are constant-time where applicable;
- Basic authentication is documented as TLS-only;
- disabled principals and revoked/expired tokens fail before a secured repository session is opened.

### Audit

Repository resolution, clone/fetch authorization, ref decisions, ACL changes, token lifecycle, repository deletion and denied access emit bounded audit records with stable reason codes and correlation IDs. Audit payloads never contain Git content or credentials.

Denied operations can occur outside a storage transaction. The implementation therefore defines an explicit audit failure policy; audit failure may make an allowed administrative mutation fail closed, but it can never convert a denied operation into an allowed operation.

### Caching and revocation

Effective permission caches are keyed by principal, repository and monotone security-version data covering principal status, group membership, grants and ref rules. Management mutations increment or replace the relevant version and invalidate local caches. A bounded TTL is a secondary cross-instance safety mechanism, not the sole invalidation strategy.

A ref mutation performs a final authorization check against current version data before publication. Long-lived read sessions have a documented bounded validity interval and do not authorize new writes after their policy version becomes stale.

### Smart HTTP

A later optional `smart-http` module uses existing JGit extension points:

```text
HTTP/external authentication adapter
        -> GitAccessContext
        -> RepositoryResolver
        -> UploadPackFactory / ReceivePackFactory
        -> pre-receive checks and advertised-ref filtering
        -> principal-bound Hibernate repository
```

Private repository discovery does not reveal existence without `DISCOVER`. Clone/fetch requires `READ`. Pushes with no write capability are rejected before pack acceptance where possible, and every exact receive command is rechecked at publication. Mixed permitted and forbidden command batches follow the atomic batch policy and are rejected as a unit.

SSH can reuse the same context and authorizer in a later transport module.

## Delivery order

Implementation is staged so each step is independently testable:

1. **Framework-neutral API and schema:** context, permissions, decisions, entities, migrations, evaluator and database matrix.
2. **Principal-bound direct-JGit enforcement:** secured factory/session, Core SPI, ref update/batch/delete/transfer checks, revocation and concurrency tests.
3. **Audit and local credentials/tokens:** management services, hash SPIs, lifecycle and privacy contracts.
4. **Smart HTTP adapter:** repository resolver, upload/receive factories, hooks and protocol tests.
5. **Consumer adoption:** Taxonomy role mapping and negative bypass tests; other consumers remain Core-only unless they explicitly select Security.

Each phase preserves a buildable Core-only capability profile. The module-boundary verifier must fail if Core acquires Security/Servlet/Spring dependencies or if Security acquires Smart HTTP dependencies.

## Consequences

Positive consequences:

- Core remains small and trusted-by-default for existing embedded consumers.
- Multi-user applications share one authorization and audit model.
- Protocol and direct JGit mutations converge on the same final enforcement point.
- Stable principal IDs and explicit contexts make decisions testable and auditable.
- Smart HTTP can be added without modifying upstream JGit.

Costs and risks:

- final publication-time checks require careful integration with JGit ref update paths;
- cache invalidation and multi-instance revocation need database-backed version evidence;
- the raw factory remains a privileged bypass and must be kept out of untrusted dependency injection scopes;
- credentials and audit introduce operational retention, privacy and incident-response responsibilities;
- a complete database and protocol matrix is substantial and will be delivered incrementally under #233.
