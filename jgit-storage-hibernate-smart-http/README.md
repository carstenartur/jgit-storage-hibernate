# jgit-storage-hibernate Smart HTTP

This optional module binds JGit Smart HTTP requests to `SecuredHibernateRepositoryFactory` without
adding Servlet or HTTP-server dependencies to Core or Security.

It provides:

- strict and deterministic URL-name to `RepositoryName` mapping, including one optional `.git`
  suffix;
- an application-supplied request authentication boundary;
- authenticated repository resolution that conceals both missing and undiscoverable repositories;
- one request binding shared by `RepositoryResolver`, `UploadPackFactory` and
  `ReceivePackFactory`;
- a fresh `READ` policy check before upload-pack and receive-pack service creation;
- atomic receive-pack command execution, while Core still performs the authoritative exact-ref
  checks at the transactional publication boundary;
- explicit disabling of dumb HTTP file service so it cannot bypass the secured resolver.

## Wiring

```java
GitServlet servlet =
    SecuredSmartHttp.servlet(
        securedRepositoryFactory,
        request -> applicationAuthentication.requireAccessContext(request));
```

The access-context provider may use the Security module's local password/access-token service, an
OIDC or LDAP adapter, or an already authenticated application session. It must return one immutable
context accepted by the configured `SecuredHibernateRepositoryFactory`; missing authentication must
raise JGit's `ServiceNotAuthorizedException`.

For an application-owned coarse push admission check:

```java
GitServlet servlet =
    SecuredSmartHttp.servlet(
        securedRepositoryFactory,
        authentication,
        SmartHttpRepositoryNameMapper.strict(),
        (request, repository, accessContext) ->
            applicationWriteAdmission.requireAnyWriteCapability(repository, accessContext));
```

The admission callback is an early rejection optimization only. It must never replace Core's final
`CREATE_REF`, `UPDATE_REF`, `DELETE_REF` and `FORCE_UPDATE` checks. The default admits an already
authenticated and readable repository to receive-pack; exact commands remain fail-closed in Core.

## Current boundary

Repository-level `READ` exposes the repository's advertised refs. Protected-ref rules govern writes,
not per-ref read visibility. The module therefore does not pretend that write rules are read filters.
A future ref-read permission model can supply a JGit `RefFilter` without changing the resolver or
principal propagation contract.

The servlet container remains application-owned. Configure TLS at the container or trusted reverse
proxy, set request/body/time limits there, and use framework-specific authentication only inside the
`SmartHttpAccessContextProvider` adapter.
