# jgit-storage-hibernate Smart HTTP

This optional module binds JGit Smart HTTP requests to `SecuredHibernateRepositoryFactory` without
adding Servlet or HTTP-server dependencies to Core or Security.

> **Development status:** this module belongs to the upcoming `0.11.0` line and is not contained in
> the current public `0.10.0` release.

It provides:

- strict and deterministic URL-name to `RepositoryName` mapping, including one optional `.git`
  suffix;
- an application-supplied request authentication boundary;
- optional strict UTF-8 Basic and one-way Bearer adapters for the Security credential service;
- bounded `WWW-Authenticate` challenge filters for Git clients;
- authenticated repository resolution that conceals both missing and undiscoverable repositories;
- one request binding shared by `RepositoryResolver`, `UploadPackFactory` and
  `ReceivePackFactory`;
- a fresh `READ` policy check before upload-pack and receive-pack service creation;
- fetch-only default wiring so a read-only deployment cannot accidentally accept push pack data;
- explicit coarse receive admission before JGit creates an atomic receive-pack;
- Core's authoritative exact-ref checks at the transactional publication boundary;
- explicit disabling of dumb HTTP file service so it cannot bypass the secured resolver;
- distinct missing/denied and infrastructure-failure results so outages are never disguised as 404s.

See the complete [secured Smart HTTP operations guide](../docs/operations/secured-smart-http.md).

## Fetch-only wiring

```java
GitServlet servlet =
    SecuredSmartHttp.servlet(
        securedRepositoryFactory,
        request -> applicationAuthentication.requireAccessContext(request));
```

This overload enables clone and fetch but deliberately leaves receive-pack disabled. The
access-context provider may use the Security module's local password/access-token service, an OIDC or
LDAP adapter, or an already authenticated application session. It must return one immutable context
accepted by the configured `SecuredHibernateRepositoryFactory`; missing authentication must raise
JGit's `ServiceNotAuthorizedException`.

## Local Security Basic and Bearer credentials

The bridge to `jgit-storage-hibernate-security` is an **optional** Maven dependency. Applications
using local passwords or access tokens must declare both artifacts; OIDC, LDAP and existing-session
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

Create the adapter with trusted, bounded audit identifiers:

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

GitServlet servlet = SecuredSmartHttp.servlet(securedRepositoryFactory, authentication);
SmartHttpAuthenticationChallengeFilter challenge =
    SmartHttpAuthenticationChallengeFilter.basicAndBearer("Git");
```

Register the challenge filter before the servlet mapping. It adds `WWW-Authenticate` only when JGit
emits a final 401, allowing clients to discover UTF-8 Basic or Bearer authentication without
advertising challenges on successful responses. Insecure transport is rejected with HTTP 403 before
credentials are parsed, so the filter does not solicit a password or token over plaintext HTTP.

The credential adapter:

- accepts exactly one bounded `Authorization` header;
- requires `request.isSecure()` by default and maps insecure transport to HTTP 403;
- decodes Basic credentials as UTF-8 and clears decoded byte/password buffers;
- performs the credential service's dummy verifier path for malformed Basic input;
- sends every credential, principal-state and token-state denial through the same generic 401;
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
`SmartHttpAccessContextProvider` adapter.
