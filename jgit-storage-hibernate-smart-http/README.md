# jgit-storage-hibernate Smart HTTP

This optional module binds JGit Smart HTTP requests to `SecuredHibernateRepositoryFactory` without
adding Servlet or HTTP-server dependencies to Core or Security.

> **Development status:** this module is available in the public `0.11.0` release. The current
> development line is `0.11.1-SNAPSHOT`.

It provides:

- strict and deterministic URL-name to `RepositoryName` mapping, including one optional `.git`
  suffix;
- an application-supplied request authentication boundary;
- explicit, startup-validated routing across external Bearer, versioned local PAT, local Basic,
  service and already-authenticated application-context modes;
- no credential-provider fallback after a route has been selected;
- optional strict UTF-8 Basic and one-way Bearer adapters for the Security credential service;
- bounded `WWW-Authenticate` challenge filters with explicit application-versus-library ownership;
- authenticated repository resolution that conceals both missing and undiscoverable repositories;
- one request binding shared by `RepositoryResolver`, `UploadPackFactory` and
  `ReceivePackFactory`;
- a fresh `READ` policy check before upload-pack and receive-pack service creation;
- fetch-only default wiring so a read-only deployment cannot accidentally accept push pack data;
- explicit coarse receive admission before JGit creates an atomic receive-pack;
- Core's authoritative exact-ref checks at the transactional publication boundary;
- explicit disabling of dumb HTTP file service so it cannot bypass the secured resolver;
- distinct missing/denied and infrastructure-failure results so outages are never disguised as 404s.

See the complete [secured Smart HTTP operations guide](../docs/operations/secured-smart-http.md) and
the [authentication-routing guide](../docs/operations/smart-http-authentication-routing.md).

## Fetch-only wiring

```java
GitServlet servlet =
    SecuredSmartHttp.servlet(
        securedRepositoryFactory,
        request -> applicationAuthentication.requireAccessContext(request));
```

This overload enables clone and fetch but deliberately leaves receive-pack disabled. The
access-context provider must return one immutable context accepted by the configured
`SecuredHibernateRepositoryFactory`; missing authentication must raise JGit's
`ServiceNotAuthorizedException`.

For one authentication mechanism, an application may implement `SmartHttpAccessContextProvider`
directly. Deployments combining external OIDC/OAuth2 tokens, local Git PATs, local passwords or
service credentials should use `RoutingSmartHttpAccessContextProvider` or the safe Security factory
methods below. Do not implement composition by trying validators sequentially.

## Choose an authentication mode

| Deployment | Recommended provider |
|---|---|
| standalone with local credentials | `SecuritySmartHttpAuthentication.localBasicAndAccessToken(...)` |
| local PAT only | `SecuritySmartHttpAuthentication.localAccessTokenOnly(...)` |
| external OIDC/OAuth2 Bearer only | `RoutingSmartHttpAccessContextProvider.externalBearerOnly(...)` |
| external Bearer plus local Git PAT | `SecuritySmartHttpAuthentication.externalBearerAndAccessToken(...)` |
| already authenticated gateway/session | `RoutingSmartHttpAccessContextProvider.applicationContextOnly(...)` |

Every router configuration names exactly one `SmartHttpChallengeOwner`:

- `APPLICATION` means Spring Security, the container or gateway owns all challenges;
- `LIBRARY` means the application registers exactly the filter returned by
  `authentication.challengeFilter(realm)`.

## External Bearer plus local Security PAT

The most relevant mixed mode keeps JWT/OIDC validation application-owned while routing versioned
local tokens only to the Security token verifier:

```java
RoutingSmartHttpAccessContextProvider<AuthenticatedGitAccess> authentication =
    SecuritySmartHttpAuthentication.externalBearerAndAccessToken(
        credentialService,
        request ->
            SecurityAuthenticationTrace.withoutRemoteAddress(
                trustedSessionId(request), trustedCorrelationId(request)),
        "oidc",
        (request, rawBearer) -> externalAuthentication.requireGitAccess(rawBearer),
        SmartHttpChallengeOwner.APPLICATION);

GitServlet servlet = SecuredSmartHttp.servlet(securedRepositoryFactory, authentication);
```

Routing is deterministic:

- `Bearer jsh1_...` selects the current local Security-PAT route;
- `Bearer jsh_...` selects the legacy local Security-PAT route;
- every other syntactically valid Bearer value selects the external handler;
- Basic is disabled in this mode;
- a denial never falls back to another handler.

Newly issued local tokens use `jsh1_`. Existing `jsh_` tokens remain readable until expiry or
revocation and can be replaced during ordinary credential rotation. Neither the raw JWT nor the raw
PAT belongs in logs, exception causes, audit metadata, repository URLs or Git configuration.

The host application remains responsible for validating external token signature, issuer, audience,
expiry, claims and its chosen revocation policy. This module intentionally adds no OIDC, JWT, LDAP,
Keycloak or Spring dependency.

## Local Security Basic and PAT credentials

The bridge to `jgit-storage-hibernate-security` is an **optional** Maven dependency. Applications
using local passwords or access tokens must declare both artifacts; external-only and session-based
deployments can keep using the generic Smart HTTP boundary without selecting the Security schema.

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

A standalone deployment can let the library own the Basic/Bearer challenge:

```java
RoutingSmartHttpAccessContextProvider<AuthenticatedGitAccess> authentication =
    SecuritySmartHttpAuthentication.localBasicAndAccessToken(
        credentialService,
        request ->
            SecurityAuthenticationTrace.withoutRemoteAddress(
                trustedSessionId(request), trustedCorrelationId(request)),
        SmartHttpChallengeOwner.LIBRARY);

GitServlet servlet = SecuredSmartHttp.servlet(securedRepositoryFactory, authentication);
SmartHttpAuthenticationChallengeFilter challenge = authentication.challengeFilter("Git");
```

Register the challenge filter before the servlet mapping. It adds `WWW-Authenticate` only when JGit
emits a final 401, allowing clients to discover UTF-8 Basic or Bearer authentication without
advertising challenges on successful responses. Insecure transport is rejected with HTTP 403 before
credentials are parsed, so the filter does not solicit a password or token over plaintext HTTP.

Local Basic authenticates only Security-module local credentials. It is not an OIDC/Keycloak password
bridge. SSO deployments should leave it disabled unless they intentionally maintain a separate local
authentication profile. The deliberately explicit
`externalBearerAccessTokenAndLocalBasic(...)` factory exists for that exceptional mode.

`SecuritySmartHttpAccessContextProvider` remains available as an exclusive-local compatibility
adapter. New mixed deployments should use the routing factories so one component owns the header and
handler selection.

The local credential adapter:

- accepts exactly one bounded `Authorization` header;
- requires trusted secure transport and maps insecure transport to HTTP 403;
- decodes Basic credentials as UTF-8 and clears decoded byte/password buffers;
- performs the credential service's dummy verifier path for malformed Basic input;
- sends credential, principal-state and token-state denials through the same generic 401;
- preserves authentication-store and required identity-audit failures as HTTP 500.

Behind a reverse proxy, configure the container so `request.isSecure()` reflects the trusted original
connection, or inject a predicate backed by trusted proxy integration. Never inspect a
caller-controlled `X-Forwarded-Proto` header directly.

## Explicit push admission

Push requires the overload with an application-owned coarse admission check:

```java
GitServlet servlet =
    SecuredSmartHttp.servlet(
        securedRepositoryFactory,
        authentication,
        SmartHttpRepositoryNameMapper.strict(),
        (request, repository, accessContext) ->
            applicationWriteAdmission.requireAnyWriteCapability(repository, accessContext));
```

The admission callback rejects principals with no repository-level write capability before JGit
accepts pack data. It is still only an early rejection boundary and must never approve an exact ref
command or replace Core's final `CREATE_REF`, `UPDATE_REF`, `DELETE_REF` and `FORCE_UPDATE` checks.

`SmartHttpReceiveAdmission.allowAuthenticatedRequests()` is available for controlled deployments that
accept receiving pack data before the exact Core decision, but it is not the default and is a poor fit
for untrusted or resource-constrained servers.

## Current boundary

Repository-level `READ` exposes the repository's advertised refs. Protected-ref rules govern writes,
not per-ref read visibility. The module therefore does not pretend that write rules are read filters.
A future ref-read permission model can supply a JGit `RefFilter` without changing the resolver or
principal propagation contract.

The servlet container remains application-owned. Configure TLS at the container or trusted reverse
proxy, set request/body/time limits there, and use framework-specific authentication only inside the
selected application handler.
