# Smart HTTP authentication routing

The Smart HTTP endpoint may be used by local installations, OIDC applications, service clients and
already authenticated gateways. These mechanisms must not be composed by trying one validator after
another. One request has exactly one credential owner, one selected handler and one authenticated
principal-bound context.

This guide describes the routing contract introduced for mixed deployments. The more general
[secured Smart HTTP guide](secured-smart-http.md) remains authoritative for repository discovery,
fetch, push admission and exact ref authorization.

## Invariant

`RoutingSmartHttpAccessContextProvider` performs these steps in order:

```text
trusted TLS decision
    -> exactly one bounded Authorization header, or application-context mode
    -> deterministic scheme/namespace selection
    -> exactly one authentication handler
    -> one immutable access context
    -> current repository and exact-ref authorization
```

It never sends the same bearer value to multiple validators. A selected handler's denial is final and
does not cause fallback to local Basic, a local access-token verifier or another external identity
provider.

Authentication proves who is making the request. It does not grant repository or ref permissions.
Current principal state, verified groups, repository grants, exact ref rules and credential scopes
remain authoritative after routing.

## Deployment decision table

| Deployment | Recommended authentication | Challenge owner |
|---|---|---|
| Standalone/local | Explicit local Basic and/or Security access token | Library filter or application |
| OIDC/OAuth2 application | Application-validated external Bearer only | Application security layer |
| OIDC plus Git personal access tokens | External Bearer plus namespaced Security access token | Application security layer |
| Already authenticated gateway/session | Application-context-only provider | Application/gateway |
| Dedicated service credentials | Explicit non-overlapping service Bearer namespace | One selected owner |

A deployment must select one challenge owner. `SmartHttpChallengeOwner.APPLICATION` means Spring
Security, the servlet container, gateway or another host layer owns every `WWW-Authenticate` field.
`SmartHttpChallengeOwner.LIBRARY` means the application registers exactly the filter returned by
`authentication.challengeFilter(realm)` before the Git servlet.

Do not register the library filter when an application security layer already emits challenges.
Doing both can produce duplicate or contradictory headers.

## Local Basic and Security access tokens

Use the optional Security bridge for a standalone installation:

```java
RoutingSmartHttpAccessContextProvider<AuthenticatedGitAccess> authentication =
    SecuritySmartHttpAuthentication.localBasicAndAccessToken(
        credentialService,
        request ->
            SecurityAuthenticationTrace.withoutRemoteAddress(
                trustedSessionId(request), trustedCorrelationId(request)),
        SmartHttpChallengeOwner.LIBRARY);

SmartHttpAuthenticationChallengeFilter challenge =
    authentication.challengeFilter("Git");
GitServlet servlet = SecuredSmartHttp.servlet(repositories, authentication);
```

Register `challenge` before the servlet mapping. The router rejects insecure transport with HTTP 403
before reading the authorization header, so the challenge filter does not solicit a password or token
on plaintext HTTP.

Local Basic means only the password verifier stored by `jgit-storage-hibernate-security`. It is not an
OIDC, LDAP or Keycloak password bridge. An SSO application should leave local Basic disabled unless it
intentionally maintains a separate local-authentication profile. Never forward an identity-provider
password into the local password verifier based on a matching username.

A PAT-only deployment can use:

```java
RoutingSmartHttpAccessContextProvider<AuthenticatedGitAccess> authentication =
    SecuritySmartHttpAuthentication.localAccessTokenOnly(
        credentialService,
        traceProvider,
        SmartHttpChallengeOwner.LIBRARY);
```

## External Bearer only

External token validation remains host-application responsibility. The application validates at
least signature, issuer, audience, expiry, required claims and its own revocation/session policy. It
then maps the verified external identity to a stable Security principal and returns one immutable
`AuthenticatedGitAccess` or equivalent application context.

```java
RoutingSmartHttpAccessContextProvider<AuthenticatedGitAccess> authentication =
    RoutingSmartHttpAccessContextProvider.externalBearerOnly(
        "oidc",
        (request, rawBearer) -> externalAuthentication.requireGitAccess(rawBearer),
        SmartHttpChallengeOwner.APPLICATION);
```

The library deliberately adds no OIDC, JWT, LDAP, Keycloak or Spring dependency. The handler receives
the raw bearer value only after the router selected it and must not retain, log, attach to exception
messages or copy it into audit metadata.

## External Bearer plus local Git PAT

Mixed SSO and Git-PAT deployments use a collision-resistant namespace instead of validator fallback:

```java
RoutingSmartHttpAccessContextProvider<AuthenticatedGitAccess> authentication =
    SecuritySmartHttpAuthentication.externalBearerAndAccessToken(
        credentialService,
        traceProvider,
        "oidc",
        (request, rawBearer) -> externalAuthentication.requireGitAccess(rawBearer),
        SmartHttpChallengeOwner.APPLICATION);
```

Selection is deterministic:

| Authorization value | Selected handler |
|---|---|
| `Bearer jsh_...` | Security access-token verifier only |
| any other syntactically valid `Bearer ...` | external Bearer handler only |
| `Basic ...` | rejected in this mode |
| unknown scheme, duplicate header or malformed value | generic authentication denial |

A malformed or revoked `jsh_` value is still owned exclusively by the local token route. It is not
retried as an external JWT. Conversely, an external token is never looked up in the Security token
table.

When a deployment intentionally maintains separate local passwords in addition to SSO and PATs, it
must choose the deliberately explicit
`externalBearerAccessTokenAndLocalBasic(...)` factory. This name is intentionally verbose so local
password acceptance cannot be enabled accidentally.

## Application-context-only mode

A gateway or servlet security layer that has already authenticated the request can avoid reading the
authorization header in the library:

```java
RoutingSmartHttpAccessContextProvider<MyAccessContext> authentication =
    RoutingSmartHttpAccessContextProvider.applicationContextOnly(
        "trusted-gateway-session",
        request -> gatewayIdentity.requireGitContext(request),
        SmartHttpChallengeOwner.APPLICATION);
```

This mode is exclusive: it does not combine with header routes, does not inspect `Authorization` and
requires application-owned challenges. The trusted layer must provide a stable identity and must not
accept caller-controlled identity headers without authenticated proxy integration.

## Local token namespace and versioning

The historical local token format is now published as Security access-token format version 1:

```text
jsh_<16-character non-secret lookup component>.<43-character random secret>
```

`SecurityAccessTokenNamespace.VERSION_1_BEARER_PREFIX` is `jsh_`. The short prefix is safe for route
selection and format identification. The longer lookup component stored in the database is not an
authorization decision and should not be copied into general request logs or audit metadata.

Version 1 remains fully compatible with existing issued tokens; no schema migration or forced token
replacement is required. A future incompatible format must reserve a new prefix that does not begin
with, contain or otherwise overlap an existing prefix. Startup validation rejects overlapping
configured namespaces before the endpoint can serve traffic.

Recommended rotation procedure:

1. issue a replacement token with the current format and the minimum required scopes;
2. update the client through its protected secret-management channel;
3. verify the new token against the intended repository operation;
4. revoke the previous token;
5. confirm that later requests fail through the same generic authentication response.

A token value is returned only once at issuance. Do not put it in a clone URL, repository config,
command history, issue, CI log or Git commit. Use an operating-system credential store, secret manager
or a short-lived process environment owned by the deployment. JGit clients that inject a Bearer
header should configure the header on the in-memory `TransportHttp` instance rather than persisting it
in repository configuration.

## TLS and reverse-proxy trust

The default secure-transport decision is `HttpServletRequest.isSecure()`. Behind a trusted reverse
proxy, configure the servlet container so this value represents the original HTTPS connection. The
factory overloads accepting a predicate exist for container integrations that have already validated
proxy state.

Never make the predicate trust a raw caller-controlled `X-Forwarded-Proto`, `Forwarded` or identity
header. A transport-predicate failure is an infrastructure failure, not permission to continue.

Transport is checked before the router reads `Authorization` or invokes any credential handler:

| Condition | Protocol result |
|---|---|
| insecure or untrusted transport | 403, no authentication challenge from the library filter |
| missing, malformed or invalid credential on trusted TLS | generic 401 |
| authenticated but repository is missing or undiscoverable | configured non-disclosure result, normally 404 |
| handler, audit, database or external key-service failure | bounded 500, fail closed |

Handler causes and messages are not propagated to the protocol response. This prevents a faulty
application adapter from exposing a raw bearer value or claim set through a servlet error page.

## Non-secret request evidence

After selecting a route, the router stores two bounded request attributes:

- `RoutingSmartHttpAccessContextProvider.AUTHENTICATION_KIND_ATTRIBUTE`;
- `RoutingSmartHttpAccessContextProvider.AUTHENTICATION_HANDLER_ATTRIBUTE`.

They contain only the configured authentication kind and bounded handler identifier. They never
contain the authorization header, token, password, JWT claims or a token-specific lookup prefix.
Applications may combine these values with bounded session, operation and correlation identifiers in
privacy-approved audit evidence.

## Revocation and authorization bounds

For local Security access tokens, authentication checks token existence, hash, expiry, revocation,
principal state and scopes. Pair the result with `HibernateCredentialScopedRepositoryAccessPolicy` so
sensitive Core operations revalidate the current credential and principal state. Repository grants
and protected-ref rules are evaluated independently and remain capable of reducing access at any
time.

For externally validated credentials, the host owns the documented token/session revocation bound.
The stable principal mapping must still feed the current Security repository policy so disabling a
principal, removing a group or changing an ACL does not turn the externally authenticated context
into an authorization cache.

Token scopes are reductions only. They cannot grant a permission absent from current repository and
ref policy.

## Configuration failures

The router rejects startup configuration when:

- more than one Basic route is present;
- more than one unprefixed external Bearer route is present;
- handler identifiers are duplicated;
- two bearer prefixes overlap in either direction;
- a route or handler is null;
- an application-context mode selects library-owned challenges;
- a prefix or handler identifier is blank, unbounded or contains unsafe characters.

This makes routing independent of handler order and prevents a later configuration edit from silently
introducing fallback authentication.
