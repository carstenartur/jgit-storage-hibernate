# ADR-0003: Add security as an optional principal-bound capability

## Status

Accepted and under staged implementation in issue #233.

The architecture decision is stable. The principal/group ACL model, direct-JGit enforcement,
authorization and identity audit, local password/access-token services, token revalidation, the
request-bound Smart HTTP adapter and real-client protocol coverage have now been implemented. PR
#256 adds reusable HTTP credential-header adapters. Bounded database-backed coarse write admission
and retained protocol-security overhead evidence remain follow-up work.

## Implementation status

| Increment | Result |
|---|---|
| PR #241 | Fixed the optional module split, explicit context propagation and trust boundary. |
| PR #242 | Added the Security schema, principals, groups, grants, protected-ref rules and evaluator. |
| PR #243 | Added the dependency-free Core policy SPI and principal-bound direct-JGit ref/deletion enforcement. |
| PR #244 | Added bounded durable repository/ref authorization audit. |
| PR #245 | Added local password and one-way access-token authentication plus identity audit. |
| PR #247/#248 | Added current security-version and access-token revalidation at sensitive Core boundaries. |
| PR #249 | Added the optional request-bound JGit Smart HTTP resolver and upload/receive factories. |
| PR #250 | Added retained real-JGit-client clone, fetch and exact push-authorization protocol contracts. |
| PR #256 | Adds strict UTF-8 Basic/Bearer adapters, trusted-TLS handling and bounded 401 challenges. |

This ADR describes the intended end state as well as the constraints that every increment must keep.

## Context

Core intentionally treats every caller that obtains a raw `HibernateRepositoryFactory` or
unrestricted JGit `Repository` as trusted infrastructure. That is the correct default for an
embedded storage library, but multi-user consumers otherwise have to reimplement repository
membership, protected refs, credentials, audit evidence and JGit protocol integration independently.

JGit does not own application identities. It does expose repository resolution, upload/receive
factories, hooks and ref-update extension points. A reusable security capability can therefore be
implemented without changing upstream JGit, provided that identity is propagated explicitly and
authorization is rechecked at the storage publication boundary.

The design preserves these invariants:

- Core remains usable without the Security schema, Servlet, Spring, password hashing or an identity
  provider.
- Enabling security never falls back to an implicit anonymous or process principal.
- A principal authorized for one `RepositoryName` cannot access another repository merely because
  Git object IDs are identical.
- Author and committer metadata are Git content and never authentication evidence.
- Protocol-level checks alone are insufficient because embedded consumers can call JGit directly.
- Long-running Git operations do not turn an old authorization or token result into an indefinite
  write lease.
- A database or authorization-service outage is never converted into an allow or disguised as a
  harmless missing-repository result.

## Options considered

### A. Put users, roles and authentication in Core

This gives one apparent entry point but couples storage to application identity, password policy,
HTTP/session concepts and consumer-specific roles. It would make every Core consumer pay for
capabilities it did not select and violate the enforced optional-module graph.

Rejected.

### B. Keep all authorization in application controllers

This avoids Core changes, but any code that obtains a raw repository handle can bypass controller
checks through `RefUpdate`, `BatchRefUpdate`, repository deletion or future storage-native
operations. Protocol and direct-JGit behavior can also diverge.

Rejected as the security boundary. Controller and protocol checks remain useful as early rejection
layers only.

### C. Optional Security and protocol modules plus a minimal Core enforcement SPI

Core owns only dependency-free repository access requests, a policy hook, the principal-bound generic
facade and the final publication checks. Security owns identity, credential, ACL and audit
persistence. Smart HTTP owns Servlet/JGit protocol wiring. Applications explicitly compose the
capabilities they need.

Accepted.

## Decision

### Module boundaries

```text
jgit-storage-hibernate-core
  storage and repository lifecycle
  dependency-free RepositoryAccessPolicy SPI
  generic SecuredHibernateRepositoryFactory<C>
  final direct-JGit ref/deletion checks

jgit-storage-hibernate-security
  principal/group/grant/ref-rule entities and migrations
  database-backed authorization policy
  local password and one-way access-token services
  credential-scope and token-revalidation policies
  authorization and identity audit

jgit-storage-hibernate-smart-http
  request authentication adapter boundary
  optional Security Basic/Bearer bridge and challenge filter
  strict repository-name mapper
  RepositoryResolver
  UploadPackFactory / ReceivePackFactory
  GitServlet configuration
```

`security` depends on Core and Hibernate ORM. It does not depend on Hibernate Search, Spring
Security, Jakarta Servlet, JGit HTTP support or an HTTP server.

`smart-http` depends on Core, JGit HTTP support and Jakarta Servlet. It may additionally depend on
Security through exactly one **optional** Maven edge. This keeps the protocol adapter reusable with
OIDC, LDAP or an existing application session and prevents Servlet dependencies from leaking through
Security. Applications using local database passwords or one-way access tokens declare both modules
explicitly; consumers of Smart HTTP do not receive Security transitively.

The module-boundary verifier enforces these directions and rejects a non-optional or duplicate Smart
HTTP-to-Security edge. A later SSH adapter can be another optional module using the same Core
context/policy boundary.

The raw `HibernateRepositoryFactory` remains available and is explicitly privileged infrastructure.
Untrusted request or domain code in a security-enabled application receives only a configured
`SecuredHibernateRepositoryFactory<C>`.

### Explicit access context

Authenticated identity is passed explicitly through an immutable context. No global `ThreadLocal`,
Spring-specific context or ambient process identity is part of the library contract.

The Security context provides stable principal, authentication, session and correlation evidence.
Authorization uses the stable principal ID and current database group membership, never mutable login
or display names. Session and correlation identifiers link audit evidence but never grant authority.
A separately named privileged system context may be used for migrations and repair only when it is
created explicitly and audited.

The generic Core facade remains independent of the concrete context type:

```java
SecuredHibernateRepositoryFactory<MyContext> repositories =
    new SecuredHibernateRepositoryFactory<>(sessionFactory, accessPolicy);

try (AuthorizedRepositorySession<MyContext> session =
        repositories.open(repositoryName, authenticatedContext)) {
  Repository repository = session.repository();
}
```

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

Repository deletion is a separate Core lifecycle operation and is never implied by `UPDATE_REF`.
Applications such as Taxonomy map viewer/contributor/maintainer/owner roles to these generic
permissions and ref rules outside the library.

Repository grants use deterministic explicit-deny precedence. The highest-precedence matching ref
rule can further allow or deny the exact mutation. Pattern semantics, priority and stable evidence IDs
are database-collation-independent and validated before use.

### Principal-bound facade and missing-repository semantics

Opening through the secured facade performs `DISCOVER` and `READ` before returning an object reader.
The returned repository carries the same immutable context and policy into direct JGit mutations.

Core distinguishes an authorized but absent repository with
`RepositoryDoesNotExistException` at the authoritative `repository.exists()` check. It does not
infer absence later from a secondary database query. This lets protocol adapters safely map both
absence and ACL denial to not-found while preserving authorization/database failures as server
errors.

The raw factory may create a repository. The secured facade opens existing repositories only; creation
and transfer remain explicitly privileged until separate lifecycle permissions and contracts are
introduced.

### Core enforcement SPI

Core contains no principals or ACL tables. It invokes a `RepositoryAccessPolicy<C>` with immutable
Core/JGit evidence for:

- repository discovery and read;
- every `RefUpdate`;
- every command in `BatchRefUpdate`;
- create, fast-forward update, delete and force-update classification;
- repository deletion, including a final lock-bound recheck.

A security-enabled path without a context fails closed. The final ref decision is rechecked
immediately before atomic database/ref publication. Early HTTP or application checks may reject
sooner, but cannot approve or replace this check.

Core does not invoke the authorizer per object, blob, pack or chunk. Read authorization is established
when opening a principal-bound session and rechecked by the Smart HTTP service factories; mutation
authorization is evaluated per exact repository/ref command at publication.

### Database ownership

The Security capability owns independent entities, migrations and Flyway history:

```text
jgit_storage_hibernate_security_schema_history
```

Its schema covers:

- stable principals and external issuer/subject identity;
- groups and membership;
- repository grants and deterministic ref rules;
- local password verifiers and lockout state;
- one-way access-token verifiers, scopes, expiry, revocation and security versions;
- bounded repository/ref authorization audit;
- bounded credential-management and authentication identity audit.

Security tables use `RepositoryName.value()` as the authorization key but do not require a first Git
object or commit. Grants may be provisioned before repository creation. Credential, token, grant and
audit secret material is never included in generic Hibernate Search indexes.

Dialect-specific migrations and restart/upgrade tests cover H2, HSQLDB, PostgreSQL and Microsoft SQL
Server. Migrated schemas are checked with `hbm2ddl.auto=validate`.

Smart HTTP owns no database schema.

### Authentication

Authentication remains separate from authorization. External OIDC/LDAP/application adapters may
supply an already authenticated stable subject. Security additionally provides optional local
password and personal/service access-token support:

- PBKDF2-HMAC-SHA-256 password verification through a pluggable `PasswordHasher`;
- bounded failed-attempt accounting and timed lockout;
- one-way HMAC-SHA-256 token verification using an application-owned pepper;
- at least 256 bits of random token secret material;
- token plaintext returned exactly once and never persisted or audited;
- constant-time comparison after strict digest-length validation;
- expiry, revocation, last-used time and monotonically changing credential versions;
- scopes that can only restrict repository/ref authority and never create an ACL permission.

Smart HTTP continues to accept a generic application-supplied `SmartHttpAccessContextProvider<C>`.
The optional Security bridge additionally provides strict local Basic/Bearer handling without moving
HTTP parsing into Core or Security:

- exactly one bounded `Authorization` header;
- trusted TLS state required by default;
- UTF-8 Basic decoding with cleared decoded byte/password buffers;
- dummy password verification for malformed Basic payloads;
- one-way access-token verification through the existing credential service;
- one generic client-facing 401 for all credential and principal/token-state denials;
- HTTP 500 for credential-store, required identity-audit or adapter infrastructure failures;
- Basic-only, Bearer-only or combined bounded `WWW-Authenticate` challenges emitted only on 401.

The explicit secure-transport predicate is for integrations whose servlet container has already
validated trusted proxy state. It must never trust a caller-controlled forwarding header directly.
Trace providers retain only bounded non-secret identifiers and never copy credentials, Authorization
headers or raw remote addresses.

### Audit

Repository/ref decisions and authentication/credential-management outcomes emit bounded audit records
with stable reason and correlation evidence. Audit payloads exclude Git content, passwords, token
plaintext, hashes and arbitrary request attributes.

Allowed authority-changing decisions participate in the protected storage transaction where the
current Core contract permits it. Denied and failed decisions remain durable through separate bounded
transactions. Audit failure never turns a denial into an allow; configured required audit can make an
otherwise allowed action fail closed.

The HTTP bridge deliberately does not attach the persisted internal denial reason as a protocol
exception cause. The credential service owns the durable bounded detail; the network result remains a
uniform authentication challenge.

### Caching and revocation

The current database-backed authorization policy reloads active principal, group, grant and ref-rule
state for sensitive checks rather than relying on an indefinite permission cache. Access-token
contexts additionally carry a credential version and stored scope snapshot.

`HibernateCredentialScopedRepositoryAccessPolicy` reloads the token before every sensitive Core
operation and rejects missing, revoked, expired, principal-mismatched, version-changed or scope-changed
tokens. A token revoked after repository resolution therefore cannot publish a later ref update using
the old handle.

Any future effective-permission cache must be keyed by principal, repository and monotonically
changing Security versions, support deterministic local invalidation and use a bounded TTL only as a
secondary cross-instance safeguard.

### Smart HTTP

The optional adapter uses existing JGit extension points:

```text
HTTP/application or Security credential adapter
        -> immutable access context
        -> SecuredSmartHttpRepositoryResolver
        -> SecuredHibernateRepositoryFactory
        -> UploadPackFactory / ReceivePackFactory
        -> Core publication-boundary authorization
```

The implemented adapter provides:

- strict logical repository-name mapping with one optional `.git` suffix;
- request-bound context/repository/session identity;
- concealment of both absent and undiscoverable repositories;
- preservation of infrastructure failures as server errors;
- a fresh `READ` check before upload-pack and receive-pack creation;
- fetch-only defaults and optional coarse receive admission before protocol processing;
- JGit atomic receive support;
- disabled dumb/as-is HTTP file service;
- repository close propagation back to Core's logical open-handle lifecycle;
- real JGit-client clone, fetch, allowed push and rejection contracts across supported JGit lines;
- optional strict local Basic/Bearer authentication and bounded challenge emission.

The coarse receive callback is an optimization only. Every exact receive command is still classified
and rechecked by Core. Mixed permitted and forbidden command batches are rejected atomically by the
Core batch publication contract.

Repository-level `READ` currently exposes advertised refs and reachable objects. Protected-ref rules
are write rules, not per-ref read visibility. The adapter does not pretend to provide path-level or
branch-level content confidentiality.

Remaining Smart HTTP work includes an evidence-backed database coarse-write admission helper,
retained authorization/identity-audit overhead measurements and explicit advertised-ref filtering
only if a future permission model adds ref-read visibility.

## Delivery order

1. **Framework-neutral API and schema — implemented:** context, permissions, entities, migrations,
   evaluator and database matrix.
2. **Principal-bound direct-JGit enforcement — implemented:** Core SPI, secured factory/session,
   ref update/batch/delete checks and revocation contracts.
3. **Audit and local credentials/tokens — implemented:** authorization/identity audit, password and
   access-token lifecycle, token scopes and publication-boundary revalidation.
4. **Smart HTTP adapter — in progress:** PR #249 added the request-bound adapter, PR #250 added real
   protocol-client coverage and PR #256 adds reusable local Basic/Bearer adapters. Bounded coarse
   write admission and retained overhead evidence remain.
5. **Consumer adoption — pending:** Taxonomy role mapping and server integration; other consumers
   remain Core-only unless they explicitly select Security or Smart HTTP.

Every phase preserves a buildable Core-only capability profile. The module-boundary verifier fails if
Core acquires Security/Servlet/Spring dependencies, if Security acquires Smart HTTP dependencies or if
Smart HTTP's optional Security integration becomes transitive.

## Consequences

Positive consequences:

- Core remains small and trusted-by-default for existing embedded consumers.
- Multi-user applications share one authorization, credential and audit model.
- Protocol and direct JGit mutations converge on the same final enforcement point.
- Stable principal IDs and explicit contexts make decisions testable and auditable.
- Smart HTTP is available without modifying upstream JGit or forcing one authentication framework.
- Security-only consumers do not inherit Servlet or JGit HTTP dependencies.
- Applications using local credentials share one strict header, TLS, challenge and failure-mapping
  implementation instead of duplicating it.

Costs and risks:

- final publication-time checks require careful integration with JGit ref update paths;
- the raw factory remains a privileged bypass and must stay out of untrusted dependency-injection
  scopes;
- credentials and audit introduce operational retention, privacy and incident-response duties;
- repository-level `READ` is not per-path or per-ref content confidentiality;
- each deployment container still needs trusted proxy/TLS configuration and integration tests;
- representative multi-instance authentication/authorization overhead evidence remains staged under
  issue #233.
