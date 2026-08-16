# Security capability

`jgit-storage-hibernate-security` is the optional, framework-neutral security capability described by
[ADR-0003](../docs/adrs/0003-optional-principal-bound-security.md).

It provides:

- immutable, explicitly propagated `GitAccessContext` values;
- Git-generic repository permissions;
- deterministic principal/group grant and protected-ref evaluation;
- module-owned Hibernate entities for principals, groups, memberships, grants, ref rules, credentials,
  access tokens, security versions and append-only authorization/identity audit;
- independent Flyway migrations for H2, HSQLDB, PostgreSQL and Microsoft SQL Server;
- a database-backed adapter for Core's dependency-free repository access SPI;
- principal-bound repository sessions that enforce direct JGit ref mutations and repository deletion;
- bounded persistent evidence for allowed, denied and failed authorization evaluations;
- framework-neutral local password and one-way access-token lifecycle services.

The evaluator fails closed. Repository grants are a prerequisite for access, explicit deny grants win
over allows, and the highest-precedence matching ref rule can further allow or deny a ref mutation.
Rule precedence is higher numeric priority, then more literal pattern characters, then stable rule ID.
Grant and ref-rule IDs are globally unique within one evaluator snapshot so every emitted evidence ID
identifies exactly one policy entry. Principal, group, grant and rule IDs are limited to 128 characters,
matching the Hibernate mappings and database migrations.
Glob semantics are independent of database collation: `*` matches within one ref segment, `**` crosses
`/`, and `?` matches one non-`/` character.

## Principal-bound direct JGit access

Use `SecuredHibernateRepositoryFactory` for application or request code that must not receive an
unrestricted repository factory. The database-backed policy reloads the current principal, active
group memberships, repository grants and enabled ref rules for every sensitive check. Group IDs in
the caller-supplied context are not trusted as authorization evidence.

```java
GitAccessContext accessContext =
    new GitAccessContext(
        "principal-123",
        Set.of(),
        "oidc",
        "session-456",
        "correlation-789",
        Map.of());

HibernateSecurityRepositoryAccessPolicy accessPolicy =
    new HibernateSecurityRepositoryAccessPolicy(sessionFactory);
SecuredHibernateRepositoryFactory<GitAccessContext> repositories =
    new SecuredHibernateRepositoryFactory<>(sessionFactory, accessPolicy);

try (AuthorizedRepositorySession<GitAccessContext> session =
    repositories.open(new RepositoryName("demo"), accessContext)) {
  Repository repository = session.repository();

  RefUpdate update = repository.updateRef("refs/heads/topic");
  update.setExpectedOldObjectId(oldObjectId);
  update.setNewObjectId(newObjectId);
  RefUpdate.Result result = update.update();
}
```

Opening checks `DISCOVER` and `READ` before repository existence is inspected. The returned repository
is guarded: direct `RefUpdate`, symbolic-ref updates and `BatchRefUpdate` commands are classified as
create, fast-forward update, force update or delete and are checked again while Core holds the
repository publication lock. A denied atomic batch publishes none of its commands. Repository deletion
uses a separate operation mapped to `ADMINISTER` and is rechecked after the database repository lock is
acquired:

```java
RepositoryDeletionResult result =
    repositories.deleteRepository(new RepositoryName("demo"), accessContext);
```

The initial `READ` decision is an open-session decision. Long-lived read handles are not automatically
revoked while they remain open, so applications must bound session lifetime and reopen when their
security policy requires prompt read revocation. Ref mutations and repository deletion do not inherit
that initial decision: they reload and re-evaluate current database policy at their publication
boundary.

The raw `HibernateRepositoryFactory`, repository creation, repository transfer and any repository
opened without an access guard remain explicitly privileged infrastructure capabilities. Do not inject
them into untrusted request or domain code. Persisting loose/unreachable Git objects does not publish a
branch or tag; authority-changing ref publication is enforced separately.

## Local passwords and one-way access tokens

Apply the Security Flyway stream through version `0.11.2` and register every class returned by
`SecurityEntities.annotatedClasses()`. Construct the service with an application-owned management
policy, the persistent identity-audit service and an access-token pepper obtained from a secret manager:

```java
byte[] tokenPepper = secretManager.read("jgit-access-token-pepper");
try {
  HibernateSecurityIdentityAuditService identityAudit =
      new HibernateSecurityIdentityAuditService(sessionFactory);
  HibernateSecurityCredentialService credentials =
      new HibernateSecurityCredentialService(
          sessionFactory,
          new Pbkdf2PasswordHasher(),
          new HmacSha256AccessTokenHasher(tokenPepper),
          managementPolicy,
          identityAudit);
} finally {
  Arrays.fill(tokenPepper, (byte) 0);
}
```

`managementPolicy` is mandatory and receives an explicit `SecurityManagementRequest` for every
password or token mutation. The library does not infer administrative authority from a global process
user, Spring context or thread-local identity.

Set or replace a password with a caller-owned character array and clear that array after the call:

```java
char[] password = suppliedPassword.toCharArray();
try {
  credentials.setPassword(
      SecurityManagementRequest.password(
          actor,
          SecurityManagementOperation.SET_PASSWORD,
          "principal-123"),
      password);
} finally {
  Arrays.fill(password, '\0');
}
```

The default hasher uses PBKDF2-HMAC-SHA-256 with 600,000 iterations, a random 16-byte salt and a
32-byte verifier. A successful login transparently replaces a verifier when the configured hasher says
that it needs rehashing. Failed attempts, timed lockout, successful reset and identity audit are updated
transactionally. Every externally visible password denial uses the same `INVALID_CREDENTIALS` reason;
more specific reasons such as lockout, inactive principal or missing credential remain available only
in bounded audit evidence.

```java
AuthenticatedGitAccess authenticated =
    credentials.authenticatePassword(
        loginName,
        password,
        SecurityAuthenticationTrace.withoutRemoteAddress(
            "session-456", "correlation-789"));
```

Issue an access token with scopes that can only reduce repository ACL permissions:

```java
IssuedAccessToken issued =
    credentials.issueAccessToken(
        SecurityManagementRequest.issueToken(actor, "principal-123"),
        Set.of(GitRepositoryPermission.DISCOVER, GitRepositoryPermission.READ),
        Instant.now().plus(Duration.ofDays(30)));
String plaintextToken = issued.tokenValue(); // returned only by this issuance result
```

The database stores only a bounded non-secret lookup prefix, an HMAC-SHA-256 verifier and lifecycle
metadata. The plaintext token is never readable again and is excluded from `toString()`, exceptions and
audit records. `lastUsedAt`, expiry and revocation are persisted; a normal use timestamp does not
change the credential security version. Repeated revocation is idempotent.

Bind the authenticated credential boundary before the authoritative repository ACL policy:

```java
HibernateSecurityAccessAuditService repositoryAudit =
    new HibernateSecurityAccessAuditService(sessionFactory);
HibernateSecurityRepositoryAccessPolicy repositoryAcl =
    new HibernateSecurityRepositoryAccessPolicy(sessionFactory, repositoryAudit);
CredentialScopedRepositoryAccessPolicy credentialPolicy =
    new CredentialScopedRepositoryAccessPolicy(repositoryAcl, repositoryAudit);
SecuredHibernateRepositoryFactory<AuthenticatedGitAccess> repositories =
    new SecuredHibernateRepositoryFactory<>(sessionFactory, credentialPolicy);
```

An `ADMINISTER` credential scope includes the Git-generic permissions below it, but no scope can grant a
permission denied by repository grants or protected-ref rules. Bearer-token access contexts represent
one successful authentication snapshot. Reauthenticate and reopen them for every HTTP request; do not
retain a token-authenticated repository session across requests when prompt expiry or revocation is
required. A database-backed publication-time token-version check remains part of the Smart HTTP and
bounded-revocation follow-up in issue #233.

Basic authentication, when an application maps it to `authenticatePassword`, must be accepted only over
TLS. The token HMAC key is not stored by this library. To rotate it without invalidating every existing
token, supply an application-owned multi-key `AccessTokenHasher` that verifies old keys while issuing
with the new key; otherwise revoke and reissue tokens during rotation.

Successful credential state changes and successful authentication audit share one transaction when
`HibernateSecurityIdentityAuditService` is injected. If required audit cannot be appended, the success
is rolled back and fails closed. A failed authentication remains denied when its audit append also
fails, with the audit failure retained only as suppressed diagnostic evidence.

## Persistent authorization audit

Register the audit entity through `SecurityEntities.annotatedClasses()` and apply the Security Flyway
stream through version `0.11.1`. Then pass one persistent recorder to the database-backed policy:

```java
HibernateSecurityAccessAuditService audit =
    new HibernateSecurityAccessAuditService(sessionFactory);
HibernateSecurityRepositoryAccessPolicy accessPolicy =
    new HibernateSecurityRepositoryAccessPolicy(sessionFactory, audit);
SecuredHibernateRepositoryFactory<GitAccessContext> repositories =
    new SecuredHibernateRepositoryFactory<>(sessionFactory, accessPolicy);
```

Every policy check now appends a bounded event with the stable principal ID, authentication method,
session and correlation IDs, logical repository, exact operation, optional ref and old/new object IDs,
outcome, reason code, optional policy evidence ID and policy version. The record deliberately excludes:

- caller-supplied group lists and arbitrary context attributes;
- commit, tree, blob, message or changed-file content;
- passwords, tokens, hashes or other credential material;
- display names, login names and author/committer metadata.

Queries are intentionally scoped and bounded:

```java
List<SecurityAccessAuditEvent> repositoryEvents =
    audit.findByRepository(new RepositoryName("demo"), 100);
List<SecurityAccessAuditEvent> requestEvents =
    audit.findByCorrelationId("correlation-789", 100);
```

There is no unbounded `findAll()` and no update API. The Hibernate entity is also mapped `@Immutable`,
so a loaded audit row is not rewritten through normal ORM dirty checking. Audit IDs are opaque.
Consumers should expose these queries only behind an explicit administrative permission and should
apply database backup, retention, legal-hold and deletion policies appropriate to their jurisdiction.

### Failure and transaction semantics

An audit row records an **authorization evaluation**, not proof that the subsequent Git/storage
operation committed. For example, an allowed ref decision can still be followed by an optimistic-lock,
I/O or transaction failure. Storage success remains observable through the JGit result, reflog and
storage transaction outcome.

Audit rows use their own short transaction. This makes denials durable even when they occur before or
outside a storage transaction. When audit and storage share one `SessionFactory`, the connection pool
must provide an independent audit connection in addition to a connection already held by a ref or
repository-lifecycle transaction. A deployment may instead give the audit service its own compatible
`SessionFactory` targeting the same migrated Security schema.

The resulting rules are deterministic:

- an allowed decision is not returned when its required audit append fails; the operation fails closed
  with `SecurityAuditPersistenceException`;
- a denied decision remains denied when audit persistence also fails; the audit error is attached as a
  suppressed exception and can never turn the denial into an allow;
- an evaluator/database failure remains the primary failure; a simultaneous audit failure is attached
  as suppressed evidence;
- repository deletion may produce two allowed checks with the same correlation ID because it is
  deliberately checked before lifecycle reservation and again after acquiring the database lock.

The default one-argument policy constructors retain the explicit no-op recorder for compatibility.
Security-sensitive deployments that require durable audit must construct and inject
`HibernateSecurityAccessAuditService`; there is no ambient global recorder.

This module deliberately has no Hibernate Search, Spring, Servlet or HTTP-server dependency. Smart HTTP
integration, request-bound authentication adapters and bounded revocation for long-lived sessions
remain later stages of issue #233.
