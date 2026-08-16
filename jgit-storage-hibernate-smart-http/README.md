# jgit-storage-hibernate Smart HTTP

This optional module binds JGit Smart HTTP requests to `SecuredHibernateRepositoryFactory` without
adding Servlet or HTTP-server dependencies to Core or Security.

> **Development status:** this module belongs to the upcoming `0.11.0` line and is not contained in
> the current public `0.10.0` release.

It provides:

- strict and deterministic URL-name to `RepositoryName` mapping, including one optional `.git`
  suffix;
- an application-supplied request authentication boundary;
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
