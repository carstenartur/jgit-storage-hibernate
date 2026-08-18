/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.smarthttp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.junit.jupiter.api.Test;

class RoutingSmartHttpAccessContextProviderTest {

  @Test
  void routesExternalAndPrefixedBearersExactlyOnceAndRecordsEvidence() throws Exception {
    AtomicInteger localCalls = new AtomicInteger();
    AtomicInteger externalCalls = new AtomicInteger();
    RoutingSmartHttpAccessContextProvider<String> authentication =
        new RoutingSmartHttpAccessContextProvider<>(
            List.of(
                SmartHttpAuthenticationRoute.securityAccessToken(
                    "local-pat",
                    "jsh_",
                    (request, credential) -> {
                      localCalls.incrementAndGet();
                      return "local:" + credential;
                    }),
                SmartHttpAuthenticationRoute.externalBearer(
                    "oidc",
                    (request, credential) -> {
                      externalCalls.incrementAndGet();
                      return "external:" + credential;
                    })),
            SmartHttpChallengeOwner.APPLICATION);

    RequestFixture external = request(true, "Bearer eyJhbGciOiJSUzI1NiJ9.payload.signature");
    assertEquals(
        "external:eyJhbGciOiJSUzI1NiJ9.payload.signature",
        authentication.require(external.request()));
    assertEquals(SmartHttpAuthenticationKind.EXTERNAL_BEARER, external.authenticationKind());
    assertEquals("oidc", external.authenticationHandler());

    RequestFixture local = request(true, "Bearer jsh_lookup.secret");
    assertEquals("local:jsh_lookup.secret", authentication.require(local.request()));
    assertEquals(SmartHttpAuthenticationKind.SECURITY_ACCESS_TOKEN, local.authenticationKind());
    assertEquals("local-pat", local.authenticationHandler());

    assertEquals(1, externalCalls.get());
    assertEquals(1, localCalls.get());
  }

  @Test
  void aSelectedDenialNeverFallsBackAndUnknownNamespacesTryNothing() {
    AtomicInteger localCalls = new AtomicInteger();
    AtomicInteger externalCalls = new AtomicInteger();
    RoutingSmartHttpAccessContextProvider<String> authentication =
        new RoutingSmartHttpAccessContextProvider<>(
            List.of(
                SmartHttpAuthenticationRoute.securityAccessToken(
                    "local-pat",
                    "jsh_",
                    (request, credential) -> {
                      localCalls.incrementAndGet();
                      throw new ServiceNotAuthorizedException();
                    }),
                SmartHttpAuthenticationRoute.externalBearer(
                    "oidc",
                    (request, credential) -> {
                      externalCalls.incrementAndGet();
                      throw new ServiceNotAuthorizedException();
                    })),
            SmartHttpChallengeOwner.APPLICATION);

    assertThrows(
        ServiceNotAuthorizedException.class,
        () -> authentication.require(request(true, "Bearer jsh_invalid").request()));
    assertEquals(1, localCalls.get());
    assertEquals(0, externalCalls.get());

    assertThrows(
        ServiceNotAuthorizedException.class,
        () -> authentication.require(request(true, "Bearer external.invalid").request()));
    assertEquals(1, localCalls.get());
    assertEquals(1, externalCalls.get());

    AtomicInteger serviceCalls = new AtomicInteger();
    RoutingSmartHttpAccessContextProvider<String> prefixedOnly =
        new RoutingSmartHttpAccessContextProvider<>(
            List.of(
                SmartHttpAuthenticationRoute.securityAccessToken(
                    "local", "jsh_", (request, credential) -> "local"),
                SmartHttpAuthenticationRoute.serviceBearer(
                    "service",
                    "svc1_",
                    (request, credential) -> {
                      serviceCalls.incrementAndGet();
                      return "service";
                    })),
            SmartHttpChallengeOwner.APPLICATION);
    assertThrows(
        ServiceNotAuthorizedException.class,
        () -> prefixedOnly.require(request(true, "Bearer unknown_value").request()));
    assertEquals(0, serviceCalls.get());
  }

  @Test
  void secureTransportPrecedesHeaderParsingAndFailuresAreSecretFree() {
    AtomicInteger handlerCalls = new AtomicInteger();
    RoutingSmartHttpAccessContextProvider<String> authentication =
        new RoutingSmartHttpAccessContextProvider<>(
            List.of(
                SmartHttpAuthenticationRoute.externalBearer(
                    "oidc",
                    (request, credential) -> {
                      handlerCalls.incrementAndGet();
                      return "authenticated";
                    })),
            SmartHttpChallengeOwner.APPLICATION);
    RequestFixture insecure = request(false, "Bearer must-not-be-read");
    ServiceMayNotContinueException forbidden =
        assertThrows(
            ServiceMayNotContinueException.class,
            () -> authentication.require(insecure.request()));
    assertEquals(HttpServletResponse.SC_FORBIDDEN, forbidden.getStatusCode());
    assertNull(forbidden.getCause());
    assertEquals(0, insecure.authorizationReads());
    assertEquals(0, handlerCalls.get());

    RoutingSmartHttpAccessContextProvider<String> brokenTransport =
        RoutingSmartHttpAccessContextProvider.externalBearerOnly(
            "oidc",
            (request, credential) -> "ignored",
            SmartHttpChallengeOwner.APPLICATION,
            request -> {
              throw new IllegalStateException("proxy-token-secret");
            });
    ServiceMayNotContinueException transportFailure =
        assertThrows(
            ServiceMayNotContinueException.class,
            () -> brokenTransport.require(request(true, "Bearer secret").request()));
    assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, transportFailure.getStatusCode());
    assertNull(transportFailure.getCause());
    assertFalse(String.valueOf(transportFailure.getMessage()).contains("secret"));

    RoutingSmartHttpAccessContextProvider<String> brokenHandler =
        RoutingSmartHttpAccessContextProvider.externalBearerOnly(
            "oidc",
            (request, credential) -> {
              throw new IllegalStateException("backend failed for " + credential);
            },
            SmartHttpChallengeOwner.APPLICATION);
    ServiceMayNotContinueException handlerFailure =
        assertThrows(
            ServiceMayNotContinueException.class,
            () -> brokenHandler.require(request(true, "Bearer raw-secret").request()));
    assertEquals(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, handlerFailure.getStatusCode());
    assertNull(handlerFailure.getCause());
    assertFalse(String.valueOf(handlerFailure.getMessage()).contains("raw-secret"));

    RoutingSmartHttpAccessContextProvider<String> nullHandler =
        RoutingSmartHttpAccessContextProvider.externalBearerOnly(
            "oidc", (request, credential) -> null, SmartHttpChallengeOwner.APPLICATION);
    assertEquals(
        HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
        assertThrows(
                ServiceMayNotContinueException.class,
                () -> nullHandler.require(request(true, "Bearer token").request()))
            .getStatusCode());

    RoutingSmartHttpAccessContextProvider<String> explicitFailure =
        RoutingSmartHttpAccessContextProvider.externalBearerOnly(
            "oidc",
            (request, credential) -> {
              throw new ServiceMayNotContinueException(
                  "credential=" + credential,
                  new IllegalStateException("credential=" + credential),
                  HttpServletResponse.SC_UNAUTHORIZED);
            },
            SmartHttpChallengeOwner.APPLICATION);
    ServiceMayNotContinueException sanitized =
        assertThrows(
            ServiceMayNotContinueException.class,
            () -> explicitFailure.require(request(true, "Bearer raw-secret").request()));
    assertEquals(HttpServletResponse.SC_UNAUTHORIZED, sanitized.getStatusCode());
    assertNull(sanitized.getCause());
    assertFalse(String.valueOf(sanitized.getMessage()).contains("raw-secret"));
  }

  @Test
  void rejectsMalformedDuplicateAndUnsupportedAuthorizationInput() {
    AtomicInteger handlerCalls = new AtomicInteger();
    RoutingSmartHttpAccessContextProvider<String> authentication =
        RoutingSmartHttpAccessContextProvider.externalBearerOnly(
            "oidc",
            (request, credential) -> {
              handlerCalls.incrementAndGet();
              return "authenticated";
            },
            SmartHttpChallengeOwner.APPLICATION);

    List<List<String>> invalidHeaders =
        List.of(
            List.of(),
            List.of("Bearer one", "Bearer two"),
            List.of(" Bearer value"),
            List.of("Bearer value "),
            List.of("Bearer  value"),
            List.of("Bearer\tvalue"),
            List.of("Bearer value\ncontinued"),
            List.of("Digest value"),
            List.of("Bearer " + "x".repeat(8192)));
    for (List<String> headers : invalidHeaders) {
      RequestFixture fixture = request(true, headers.toArray(String[]::new));
      assertThrows(
          ServiceNotAuthorizedException.class,
          () -> authentication.require(fixture.request()));
    }
    assertEquals(0, handlerCalls.get());
  }

  @Test
  void rejectsAmbiguousRouteConfigurationAtStartup() {
    SmartHttpAuthenticationHandler<String> handler = (request, credential) -> "ok";

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RoutingSmartHttpAccessContextProvider<String>(
                List.of(), SmartHttpChallengeOwner.APPLICATION));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RoutingSmartHttpAccessContextProvider<>(
                List.of(
                    SmartHttpAuthenticationRoute.securityLocalBasic("basic-1", handler),
                    SmartHttpAuthenticationRoute.securityLocalBasic("basic-2", handler)),
                SmartHttpChallengeOwner.APPLICATION));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RoutingSmartHttpAccessContextProvider<>(
                List.of(
                    SmartHttpAuthenticationRoute.externalBearer("external-1", handler),
                    SmartHttpAuthenticationRoute.externalBearer("external-2", handler)),
                SmartHttpChallengeOwner.APPLICATION));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RoutingSmartHttpAccessContextProvider<>(
                List.of(
                    SmartHttpAuthenticationRoute.securityAccessToken(
                        "local", "jsh_", handler),
                    SmartHttpAuthenticationRoute.serviceBearer(
                        "service", "jsh_admin_", handler)),
                SmartHttpChallengeOwner.APPLICATION));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RoutingSmartHttpAccessContextProvider<>(
                List.of(
                    SmartHttpAuthenticationRoute.securityAccessToken(
                        "same", "jsh_", handler),
                    SmartHttpAuthenticationRoute.externalBearer("same", handler)),
                SmartHttpChallengeOwner.APPLICATION));

    List<SmartHttpAuthenticationRoute<String>> nullRoute = new ArrayList<>();
    nullRoute.add(SmartHttpAuthenticationRoute.externalBearer("external", handler));
    nullRoute.add(null);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new RoutingSmartHttpAccessContextProvider<>(
                nullRoute, SmartHttpChallengeOwner.APPLICATION));
  }

  @Test
  void applicationContextModeNeverReadsAuthorizationAndRequiresApplicationChallenges()
      throws Exception {
    RoutingSmartHttpAccessContextProvider<String> authentication =
        RoutingSmartHttpAccessContextProvider.applicationContextOnly(
            "gateway-session",
            request -> "gateway-context",
            SmartHttpChallengeOwner.APPLICATION,
            request -> true);
    RequestFixture fixture = request(false, "Bearer ignored-by-router");
    assertEquals("gateway-context", authentication.require(fixture.request()));
    assertEquals(0, fixture.authorizationReads());
    assertEquals(SmartHttpAuthenticationKind.APPLICATION_CONTEXT, fixture.authenticationKind());
    assertEquals("gateway-session", fixture.authenticationHandler());
    assertEquals(
        Set.of(SmartHttpAuthenticationKind.APPLICATION_CONTEXT),
        authentication.authenticationKinds());
    assertEquals(Set.of("gateway-session"), authentication.handlerIds());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            RoutingSmartHttpAccessContextProvider.applicationContextOnly(
                "gateway",
                request -> "context",
                SmartHttpChallengeOwner.LIBRARY));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            RoutingSmartHttpAccessContextProvider.applicationContextOnly(
                "bad handler",
                request -> "context",
                SmartHttpChallengeOwner.APPLICATION));
  }

  @Test
  void challengeOwnershipAndRouteMetadataAreExplicit() {
    SmartHttpAuthenticationHandler<String> handler = (request, credential) -> "ok";
    SmartHttpAuthenticationRoute<String> basic =
        SmartHttpAuthenticationRoute.securityLocalBasic("basic", handler);
    SmartHttpAuthenticationRoute<String> bearer =
        SmartHttpAuthenticationRoute.externalBearer("oidc", handler);
    RoutingSmartHttpAccessContextProvider<String> libraryOwned =
        new RoutingSmartHttpAccessContextProvider<>(
            List.of(basic, bearer), SmartHttpChallengeOwner.LIBRARY);

    assertEquals(SmartHttpChallengeOwner.LIBRARY, libraryOwned.challengeOwner());
    assertEquals(
        Set.of(
            SmartHttpAuthenticationKind.SECURITY_LOCAL_BASIC,
            SmartHttpAuthenticationKind.EXTERNAL_BEARER),
        libraryOwned.authenticationKinds());
    assertEquals(Set.of("basic", "oidc"), libraryOwned.handlerIds());
    assertNotNull(libraryOwned.challengeFilter("Git"));
    assertTrue(basic.bearerPrefix().isEmpty());
    assertTrue(bearer.bearerPrefix().isEmpty());
    assertTrue(basic.toString().contains("SECURITY_LOCAL_BASIC"));

    RoutingSmartHttpAccessContextProvider<String> applicationOwned =
        new RoutingSmartHttpAccessContextProvider<>(
            List.of(bearer), SmartHttpChallengeOwner.APPLICATION);
    assertThrows(IllegalStateException.class, () -> applicationOwned.challengeFilter("Git"));
  }

  @Test
  void routeFactoriesValidateBoundedNonSecretMetadata() {
    SmartHttpAuthenticationHandler<String> handler = (request, credential) -> "ok";
    SmartHttpAuthenticationRoute<String> local =
        SmartHttpAuthenticationRoute.securityAccessToken("local", "jsh_", handler);
    SmartHttpAuthenticationRoute<String> service =
        SmartHttpAuthenticationRoute.serviceBearer("service", "svc1_", handler);
    assertEquals(SmartHttpAuthenticationKind.SECURITY_ACCESS_TOKEN, local.kind());
    assertEquals("jsh_", local.bearerPrefix().orElseThrow());
    assertEquals(SmartHttpAuthenticationKind.SERVICE, service.kind());
    assertEquals("svc1_", service.bearerPrefix().orElseThrow());

    assertThrows(
        IllegalArgumentException.class,
        () -> SmartHttpAuthenticationRoute.externalBearer("bad handler", handler));
    assertThrows(
        IllegalArgumentException.class,
        () -> SmartHttpAuthenticationRoute.securityAccessToken("local", "bad prefix", handler));
    assertThrows(
        IllegalArgumentException.class,
        () -> SmartHttpAuthenticationRoute.serviceBearer("service", "", handler));
    assertThrows(
        NullPointerException.class,
        () -> SmartHttpAuthenticationRoute.externalBearer("external", null));
  }

  private static RequestFixture request(boolean secure, String... authorizationHeaders) {
    AtomicInteger authorizationReads = new AtomicInteger();
    Map<String, Object> attributes = new HashMap<>();
    List<String> headers = List.of(authorizationHeaders);
    HttpServletRequest request =
        (HttpServletRequest)
            Proxy.newProxyInstance(
                RoutingSmartHttpAccessContextProviderTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, arguments) ->
                    switch (method.getName()) {
                      case "isSecure" -> secure;
                      case "getHeaders" -> {
                        if ("Authorization".equalsIgnoreCase(String.valueOf(arguments[0]))) {
                          authorizationReads.incrementAndGet();
                          yield Collections.enumeration(headers);
                        }
                        yield Collections.emptyEnumeration();
                      }
                      case "getHeader" -> {
                        if ("Authorization".equalsIgnoreCase(String.valueOf(arguments[0]))) {
                          authorizationReads.incrementAndGet();
                          yield headers.isEmpty() ? null : headers.getFirst();
                        }
                        yield null;
                      }
                      case "setAttribute" -> {
                        attributes.put((String) arguments[0], arguments[1]);
                        yield null;
                      }
                      case "getAttribute" -> attributes.get(arguments[0]);
                      case "toString" -> "RoutingSmartHttpTestRequest";
                      case "hashCode" -> System.identityHashCode(proxy);
                      case "equals" -> proxy == arguments[0];
                      default -> defaultValue(method.getReturnType());
                    });
    return new RequestFixture(request, authorizationReads, attributes);
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == byte.class) {
      return (byte) 0;
    }
    if (type == short.class) {
      return (short) 0;
    }
    if (type == int.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == float.class) {
      return 0F;
    }
    if (type == double.class) {
      return 0D;
    }
    if (type == char.class) {
      return '\0';
    }
    return null;
  }

  private record RequestFixture(
      HttpServletRequest request,
      AtomicInteger authorizationReadCounter,
      Map<String, Object> attributes) {

    int authorizationReads() {
      return authorizationReadCounter.get();
    }

    Object authenticationKind() {
      return attributes.get(
          RoutingSmartHttpAccessContextProvider.AUTHENTICATION_KIND_ATTRIBUTE);
    }

    Object authenticationHandler() {
      return attributes.get(
          RoutingSmartHttpAccessContextProvider.AUTHENTICATION_HANDLER_ATTRIBUTE);
    }
  }
}
