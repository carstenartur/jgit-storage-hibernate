# Secured JGit Smart HTTP

The optional `jgit-storage-hibernate-smart-http` module binds JGit's Smart HTTP protocol to a
principal-bound Hibernate repository without making Servlet, an HTTP server or authentication
framework part of Core or Security.

> **Release status:** this module is part of the `0.11.0-SNAPSHOT` development line and is not
> contained in the current public `0.10.0` release.

## Capability boundary

```text
HTTP request
    -> application-owned or Security credential adapter
    -> immutable access context
    -> SecuredSmartHttpRepositoryResolver
    -> SecuredHibernateRepositoryFactory
    -> repository READ policy
    -> UploadPack / explicitly admitted ReceivePack
    -> Core publication-boundary ref authorization
    -> Hibernate transaction
```

The responsibilities are deliberately separated:

| Layer | Responsibility |
|---|---|
| Servlet container / reverse proxy | TLS, connection and body limits, trusted proxy headers, deployment lifecycle |
| Application authentication adapter | Basic/Bearer/OIDC/session parsing and conversion to one immutable access context |
| Security module | principals, credentials, token revocation, repository grants, protected-ref rules and audit |
| Smart HTTP module | safe repository-name mapping, optional Security header bridge, request binding and JGit protocol factories |
| Core | final exact-ref authorization and atomic database/ref publication |

The raw `DefaultHibernateRepositoryFactory` remains privileged infrastructure. Request-handling code
must receive a `SecuredHibernateRepositoryFactory`, never the raw factory.

## Dependency selection

Use the module only in the server application that exposes Git HTTP traffic. Until the first public
release that contains this module, source/reactor builds use the aligned project version:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-smart-http</artifactId>
  <version>${project.version}</version>
</dependency>
```

Smart HTTP depends on Core, JGit HTTP support and the Servlet API. Its direct integration with local
Security passwords and tokens is an **optional** Maven dependency. Applications using that bridge
must therefore declare both modules explicitly:

```xml
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-security</artifactId>
  <version>${project.version}</version>
</dependency>
<dependency>
  <groupId>io.github.carstenartur</groupId>
  <artifactId>jgit-storage-hibernate-smart-http</artifactId>
  <version>${project.version}</version>
</dependency>
```

OIDC, LDAP and existing-session deployments can use the generic provider without selecting Security.
Core-only consumers that do not select Smart HTTP retain their existing dependency surface, and the
Security module itself remains free of Servlet and JGit HTTP types.

## Fetch-only servlet wiring

```java
SecuredHibernateRepositoryFactory<MyAccessContext> repositories =
    new SecuredHibernateRepositoryFactory<>(sessionFactory, accessPolicy);

GitServlet servlet =
    SecuredSmartHttp.servlet(
        repositories,
        request -> authentication.requireAccessContext(request));
```

Register the returned servlet at an application-owned mapping such as `/git/*`. The servlet is not
started by the library and no embedded server is selected transitively.

The two-argument `SecuredSmartHttp.servlet(...)` overload is deliberately **fetch-only**. It enables
repository discovery, clone and fetch, but leaves receive-pack disabled. A deployment that only needs
read access therefore cannot accidentally accept pack data and rely on a later ref rejection to undo
the resource cost.

The helper configures all of these as one unit:

- `SecuredSmartHttpRepositoryResolver`;
- `SecuredSmartHttpUploadPackFactory`;
- a `SecuredSmartHttpReceivePackFactory` with disabled admission;
- disabled dumb-HTTP/as-is file service.

Disabling the as-is file service is security relevant: static repository-file serving must not become
an alternate path around the secured resolver.

## Authentication adapter contract

`SmartHttpAccessContextProvider<C>` is called once when JGit resolves the repository. It must either:

1. return one non-null immutable context accepted by the configured secured factory;
2. throw `ServiceNotAuthorizedException` for missing or invalid authentication;
3. throw `ServiceMayNotContinueException` with a deliberate non-authentication status when a transport
   precondition fails; or
4. throw `ServiceMayNotContinueException` with a server-error status when authentication
   infrastructure failed.

External OIDC, LDAP or application-session integrations implement this boundary directly. For the
local Security credential service, use the reusable strict adapter instead of parsing headers in each
application:

```java
SecuritySmartHttpAccessContextProvider authentication =
    new SecuritySmartHttpAccessContextProvider(
        credentialService,
        Set.of(
            SecuritySmartHttpAuthenticationMethod.BASIC,
            SecuritySmartHttpAuthenticationMethod.BEARER),
        request ->
            SecurityAuthenticationTrace.withoutRemoteAddress(
                trustedSessionId(request), trustedCorrelationId(request)));

GitServlet servlet = SecuredSmartHttp.servlet(repositories, authentication);
```

The adapter first establishes trusted secure transport. When the request is not secure it returns HTTP
403 before reading or parsing an Authorization value. This is intentionally not a 401: a Basic or
Bearer challenge over plaintext HTTP could cause a Git client to transmit a password or token on the
next request.

On secure transport the adapter accepts exactly one bounded `Authorization` header. Basic payloads
are decoded as UTF-8. Malformed Base64, missing separators, invalid UTF-8 and oversized decoded
credentials execute the credential service's dummy password-verification path before the request is
rejected. Decoded Basic byte and password buffers are cleared after the authentication call. Bearer
values are passed directly to the one-way access-token service.

Credential, inactive-principal, lockout, token-format, expiry and revocation denials are all mapped to
the same generic JGit 401 result without attaching the internal reason as a protocol exception cause.
The credential service has already persisted the bounded non-secret reason. Authentication-store,
required identity-audit and unexpected adapter failures remain HTTP 500 responses.

The default transport decision is `request.isSecure()`. Behind a trusted reverse proxy, configure the
servlet container so this value reflects the original HTTPS connection. The explicit transport
predicate constructor exists for container integrations that have already authenticated proxy state;
it must never read a caller-controlled `X-Forwarded-Proto` header directly. A predicate failure is an
infrastructure error and is returned as HTTP 500 rather than silently treating the request as secure.

`SecuritySmartHttpTraceProvider.opaquePerRequest()` creates opaque identifiers without retaining a
remote address. Applications with trusted request/session identifiers should provide them explicitly.
Never copy credentials, Authorization headers, raw forwarded addresses or unbounded request
attributes into trace or audit evidence.

## Authentication challenges

Most Git clients discover Basic authentication only after a standards-compliant challenge. Register a
challenge filter before the Git servlet mapping:

```java
SmartHttpAuthenticationChallengeFilter challenge =
    SmartHttpAuthenticationChallengeFilter.basicAndBearer("Git");
```

Factories are also available for Basic-only and Bearer-only deployments. Realms are restricted to a
bounded printable-ASCII quoted value so they cannot inject response headers. The filter adds its
configured `WWW-Authenticate` fields only immediately before a final 401 status or error is committed.
A transient 401 that is replaced by a successful status does not leak a challenge. The filter does not
advertise challenges on successful, not-found, forbidden or server-error responses, and it adds each
configured challenge only once per response unless the response is reset. Because insecure requests
from the provided Security adapter are 403, this filter never solicits those credentials over
plaintext HTTP.

For access tokens, pair the servlet with
`HibernateCredentialScopedRepositoryAccessPolicy`. It revalidates token existence, principal,
revocation, expiry, security version and stored scopes at every sensitive Core operation. A token
revoked after repository resolution therefore cannot publish a later ref update through the same
handle.

## Repository-name mapping

The default strict mapper accepts logical namespaces such as:

```text
team/demo.git -> RepositoryName("team/demo")
team/demo     -> RepositoryName("team/demo")
```

It removes exactly one trailing `.git` and rejects:

- blank names and surrounding whitespace;
- leading, trailing or repeated `/` separators;
- `.` and `..` path segments;
- backslashes and control characters;
- names longer than Core's 255-character persisted key bound.

The mapper never creates a repository. Applications with a catalog or tenant mapping can supply a
custom `SmartHttpRepositoryNameMapper`, but the result must remain an immutable `RepositoryName` and
must not depend on mutable display names.

## Discovery and failure semantics

The resolver validates the request repository name, authenticates the request and then opens through
`SecuredHibernateRepositoryFactory`:

| Condition | Protocol result |
|---|---|
| Insecure transport for the Security Basic/Bearer adapter | forbidden; no authentication challenge |
| Missing/invalid authentication on secure transport | not authorized |
| Invalid URL repository name | not found |
| Principal lacks `DISCOVER` or `READ` | not found |
| Authorized repository does not exist | not found |
| Authentication, authorization or storage infrastructure failure | server error |

Missing and undiscoverable repositories intentionally share one result so a caller without
`DISCOVER` cannot test whether a private repository exists. Infrastructure failures are deliberately
not mislabeled as 404 responses; doing so would hide outages and break operational monitoring.

An authorized lookup for a missing repository does not create or repair lifecycle/lock rows. Partial
metadata and metadata without initialized Git refs are treated as infrastructure failures, not as a
normal 404.

## Fetch and clone

Repository resolution requires `DISCOVER` and `READ`. `SecuredSmartHttpUploadPackFactory` performs a
fresh `READ` check before JGit creates `UploadPack`, so policy or credential revocation between
resolution and service creation fails closed.

Repository-level `READ` currently exposes the repository's advertised refs and reachable Git objects.
Protected-ref rules are write rules, not per-ref read filters. Applications must not present this as
path-level or branch-level content confidentiality.

## Enabling push and exact ref authorization

Push requires the explicit overload with an application-owned coarse receive admission:

```java
GitServlet servlet =
    SecuredSmartHttp.servlet(
        repositories,
        authentication,
        SmartHttpRepositoryNameMapper.strict(),
        (request, repository, access) ->
            applicationWriteAdmission.requireAnyWriteCapability(repository, access));
```

`SecuredSmartHttpReceivePackFactory` performs a fresh `READ` check and invokes this callback before it
creates JGit's atomic `ReceivePack`. The callback should reject a principal with no repository-level
write capability before request pack data is accepted.

This is still only an early rejection boundary. It must not approve an exact ref command or replace
Core enforcement. Core classifies and rechecks every command at the transactional publication
boundary:

- `CREATE_REF`;
- fast-forward `UPDATE_REF`;
- `DELETE_REF`;
- non-fast-forward `FORCE_UPDATE`.

A mixed batch containing an unauthorized command is rejected atomically. The identity in commit
`author` or `committer` fields is Git content and never influences this decision.

`SmartHttpReceiveAdmission.allowAuthenticatedRequests()` is available for controlled deployments
that deliberately accept receiving pack data before the exact Core decision. It is not the default
and is generally unsuitable for untrusted or resource-constrained servers.

## Repository/session lifetime

JGit closes the `Repository` returned by its resolver. The Hibernate repository carries an idempotent
close callback that releases the same logical open-handle reservation owned by the authorized
session. The protocol adapter therefore must return the session's exact repository object; wrapping
or replacing it without preserving close semantics can block repository deletion indefinitely.

Request attributes retain only the authorized session/repository binding for the request lifetime.
They do not contain credential plaintext. Factories reject a repository object that was not resolved
and bound by the same request.

## Retained protocol evidence

The Smart HTTP module is exercised through real JGit HTTP clients, not only direct resolver/factory
calls. The retained contract covers clone, fetch, fetch-only push rejection, explicitly admitted
create and fast-forward updates, protected force/delete rejection, atomic mixed-command rejection and
identical 404 behavior for absent and undiscoverable repositories. The same contract runs against the
supported JGit 7.5, 7.6, 7.7.0 and 7.7.1 lines.

The local credential bridge additionally verifies successful UTF-8 Basic and Bearer authentication,
password-buffer clearing, malformed-Basic dummy verification, bounded denial audit, ambiguous-header
rejection, no-challenge insecure-transport refusal, trusted transport overrides, backend failure
mapping and exact final-status challenge emission.

## Deployment requirements

Before production use:

- terminate TLS only at the application or a trusted reverse proxy;
- configure trusted proxy integration so secure transport is established by the container, not a raw
  forwarding header;
- register the selected authentication challenge filter before the Git servlet;
- reject untrusted forwarded identity/address headers;
- configure request-body, connection, idle and execution-time limits;
- configure database-pool capacity for concurrent Git and audit transactions;
- use bounded authentication/session lifetime and deterministic token revocation;
- retain authorization and identity audit according to the applicable privacy/retention policy;
- keep raw repository factories out of request-scoped dependency injection;
- leave receive-pack disabled unless an explicit coarse write-admission policy is configured;
- run clone, fetch, allowed push, protected-ref rejection, force/delete rejection and mixed-command
  tests against the selected deployment container.

## Current limitations and follow-up work

The Smart HTTP phase of issue #233 is not complete. Remaining work includes:

- a database-backed coarse write-admission implementation with bounded query cost;
- explicit advertised-ref filtering if a future permission model adds ref-read visibility;
- retained Smart HTTP authorization and identity-audit overhead measurements.

These follow-ups do not change the central trust boundary: exact authority-changing ref publication
continues to be decided in Core immediately before the database transaction commits.
