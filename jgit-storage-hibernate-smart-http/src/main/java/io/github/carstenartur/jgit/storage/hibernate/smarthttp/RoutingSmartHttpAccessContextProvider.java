/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.smarthttp;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;

/**
 * Selects exactly one validated Smart HTTP authentication handler without fallback.
 *
 * <p>The router establishes trusted secure transport before reading credentials, accepts exactly
 * one bounded Authorization header and exposes its raw credential only to the selected handler.
 * Prefixed bearer namespaces are validated as non-overlapping at construction time. An optional
 * external bearer catch-all is selected only when no configured prefix matches; a denial from the
 * selected handler is never retried against another provider.
 *
 * @param <C> immutable authenticated access-context type
 */
public final class RoutingSmartHttpAccessContextProvider<C>
    implements SmartHttpAccessContextProvider<C> {

  /** Request attribute containing the selected non-secret authentication kind. */
  public static final String AUTHENTICATION_KIND_ATTRIBUTE =
      RoutingSmartHttpAccessContextProvider.class.getName() + ".authenticationKind";

  /** Request attribute containing the selected bounded non-secret handler identifier. */
  public static final String AUTHENTICATION_HANDLER_ATTRIBUTE =
      RoutingSmartHttpAccessContextProvider.class.getName() + ".authenticationHandler";

  private static final int MAX_AUTHORIZATION_HEADER_LENGTH = 8192;
  private static final int MAX_APPLICATION_HANDLER_ID_LENGTH = 64;

  private final List<SmartHttpAuthenticationRoute<C>> routes;
  private final SmartHttpChallengeOwner challengeOwner;
  private final Predicate<HttpServletRequest> secureTransport;
  private final SmartHttpAccessContextProvider<C> applicationContextProvider;
  private final String applicationHandlerId;

  /** Create an authorization-header router using {@link HttpServletRequest#isSecure()}. */
  public RoutingSmartHttpAccessContextProvider(
      List<SmartHttpAuthenticationRoute<C>> routes,
      SmartHttpChallengeOwner challengeOwner) {
    this(routes, challengeOwner, HttpServletRequest::isSecure);
  }

  /** Create an authorization-header router with an explicit trusted transport predicate. */
  public RoutingSmartHttpAccessContextProvider(
      List<SmartHttpAuthenticationRoute<C>> routes,
      SmartHttpChallengeOwner challengeOwner,
      Predicate<HttpServletRequest> secureTransport) {
    this(
        validatedRoutes(routes),
        Objects.requireNonNull(challengeOwner, "challengeOwner"),
        Objects.requireNonNull(secureTransport, "secureTransport"),
        null,
        null);
  }

  private RoutingSmartHttpAccessContextProvider(
      List<SmartHttpAuthenticationRoute<C>> routes,
      SmartHttpChallengeOwner challengeOwner,
      Predicate<HttpServletRequest> secureTransport,
      SmartHttpAccessContextProvider<C> applicationContextProvider,
      String applicationHandlerId) {
    this.routes = routes;
    this.challengeOwner = challengeOwner;
    this.secureTransport = secureTransport;
    this.applicationContextProvider = applicationContextProvider;
    this.applicationHandlerId = applicationHandlerId;
  }

  /** Create a safe external-bearer-only router using servlet secure-transport state. */
  public static <C> RoutingSmartHttpAccessContextProvider<C> externalBearerOnly(
      String handlerId,
      SmartHttpAuthenticationHandler<C> handler,
      SmartHttpChallengeOwner challengeOwner) {
    return externalBearerOnly(
        handlerId, handler, challengeOwner, HttpServletRequest::isSecure);
  }

  /** Create a safe external-bearer-only router with an explicit trusted transport predicate. */
  public static <C> RoutingSmartHttpAccessContextProvider<C> externalBearerOnly(
      String handlerId,
      SmartHttpAuthenticationHandler<C> handler,
      SmartHttpChallengeOwner challengeOwner,
      Predicate<HttpServletRequest> secureTransport) {
    return new RoutingSmartHttpAccessContextProvider<>(
        List.of(SmartHttpAuthenticationRoute.externalBearer(handlerId, handler)),
        challengeOwner,
        secureTransport);
  }

  /**
   * Bind an already authenticated application or gateway context without reading Authorization.
   *
   * <p>The application must own challenges in this mode because the router cannot infer which
   * upstream mechanism established the context.
   */
  public static <C> RoutingSmartHttpAccessContextProvider<C> applicationContextOnly(
      String handlerId,
      SmartHttpAccessContextProvider<C> provider,
      SmartHttpChallengeOwner challengeOwner) {
    return applicationContextOnly(
        handlerId,
        provider,
        challengeOwner,
        HttpServletRequest::isSecure);
  }

  /** Bind an already authenticated context with an explicit trusted transport predicate. */
  public static <C> RoutingSmartHttpAccessContextProvider<C> applicationContextOnly(
      String handlerId,
      SmartHttpAccessContextProvider<C> provider,
      SmartHttpChallengeOwner challengeOwner,
      Predicate<HttpServletRequest> secureTransport) {
    if (challengeOwner != SmartHttpChallengeOwner.APPLICATION) {
      throw new IllegalArgumentException(
          "application-context authentication requires APPLICATION challenge ownership");
    }
    String boundedHandlerId = applicationHandlerId(handlerId);
    return new RoutingSmartHttpAccessContextProvider<>(
        List.of(),
        SmartHttpChallengeOwner.APPLICATION,
        Objects.requireNonNull(secureTransport, "secureTransport"),
        Objects.requireNonNull(provider, "provider"),
        boundedHandlerId);
  }

  /** Return the single configured challenge owner. */
  public SmartHttpChallengeOwner challengeOwner() {
    return challengeOwner;
  }

  /** Return configured non-secret authentication kinds. */
  public Set<SmartHttpAuthenticationKind> authenticationKinds() {
    if (applicationContextProvider != null) {
      return Set.of(SmartHttpAuthenticationKind.APPLICATION_CONTEXT);
    }
    Set<SmartHttpAuthenticationKind> kinds = new LinkedHashSet<>();
    for (SmartHttpAuthenticationRoute<C> route : routes) {
      kinds.add(route.kind());
    }
    return Collections.unmodifiableSet(kinds);
  }

  /** Return configured bounded non-secret handler identifiers. */
  public Set<String> handlerIds() {
    if (applicationContextProvider != null) {
      return Set.of(applicationHandlerId);
    }
    Set<String> identifiers = new LinkedHashSet<>();
    for (SmartHttpAuthenticationRoute<C> route : routes) {
      identifiers.add(route.handlerId());
    }
    return Collections.unmodifiableSet(identifiers);
  }

  /**
   * Create the only library-owned challenge filter for this routing configuration.
   *
   * @throws IllegalStateException when the application owns challenges
   */
  public SmartHttpAuthenticationChallengeFilter challengeFilter(String realm) {
    if (challengeOwner != SmartHttpChallengeOwner.LIBRARY) {
      throw new IllegalStateException("the application owns Smart HTTP authentication challenges");
    }
    boolean basic =
        routes.stream()
            .anyMatch(
                route ->
                    route.authorizationScheme()
                        == SmartHttpAuthenticationRoute.AuthorizationScheme.BASIC);
    boolean bearer =
        routes.stream()
            .anyMatch(
                route ->
                    route.authorizationScheme()
                        == SmartHttpAuthenticationRoute.AuthorizationScheme.BEARER);
    if (basic && bearer) {
      return SmartHttpAuthenticationChallengeFilter.basicAndBearer(realm);
    }
    if (basic) {
      return SmartHttpAuthenticationChallengeFilter.basic(realm);
    }
    if (bearer) {
      return SmartHttpAuthenticationChallengeFilter.bearer(realm);
    }
    throw new IllegalStateException("no authorization-header challenge is configured");
  }

  @Override
  public C require(HttpServletRequest request)
      throws ServiceNotAuthorizedException, ServiceMayNotContinueException {
    HttpServletRequest current = Objects.requireNonNull(request, "request");
    if (!isSecure(current)) {
      throw insecureTransport();
    }

    if (applicationContextProvider != null) {
      setEvidence(
          current, SmartHttpAuthenticationKind.APPLICATION_CONTEXT, applicationHandlerId);
      return invokeApplication(current);
    }

    Credential credential = authorizationCredential(current);
    SmartHttpAuthenticationRoute<C> route = select(credential);
    if (route == null) {
      throw unauthorized();
    }
    setEvidence(current, route.kind(), route.handlerId());
    return invoke(route, current, credential.value());
  }

  private boolean isSecure(HttpServletRequest request) throws ServiceMayNotContinueException {
    try {
      return secureTransport.test(request);
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private C invokeApplication(HttpServletRequest request)
      throws ServiceNotAuthorizedException, ServiceMayNotContinueException {
    try {
      C context = applicationContextProvider.require(request);
      return requireContext(context);
    } catch (ServiceNotAuthorizedException denied) {
      throw unauthorized();
    } catch (ServiceMayNotContinueException failure) {
      throw sanitize(failure);
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private C invoke(
      SmartHttpAuthenticationRoute<C> route,
      HttpServletRequest request,
      String credential)
      throws ServiceNotAuthorizedException, ServiceMayNotContinueException {
    try {
      C context = route.handler().authenticate(request, credential);
      return requireContext(context);
    } catch (ServiceNotAuthorizedException denied) {
      throw unauthorized();
    } catch (ServiceMayNotContinueException failure) {
      throw sanitize(failure);
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private C requireContext(C context) throws ServiceMayNotContinueException {
    if (context == null) {
      throw unavailable();
    }
    return context;
  }

  private void setEvidence(
      HttpServletRequest request,
      SmartHttpAuthenticationKind kind,
      String handlerId)
      throws ServiceMayNotContinueException {
    try {
      request.setAttribute(AUTHENTICATION_KIND_ATTRIBUTE, kind);
      request.setAttribute(AUTHENTICATION_HANDLER_ATTRIBUTE, handlerId);
    } catch (RuntimeException failure) {
      throw unavailable();
    }
  }

  private SmartHttpAuthenticationRoute<C> select(Credential credential) {
    if (credential.scheme() == SmartHttpAuthenticationRoute.AuthorizationScheme.BASIC) {
      for (SmartHttpAuthenticationRoute<C> route : routes) {
        if (route.authorizationScheme()
            == SmartHttpAuthenticationRoute.AuthorizationScheme.BASIC) {
          return route;
        }
      }
      return null;
    }

    SmartHttpAuthenticationRoute<C> externalBearer = null;
    for (SmartHttpAuthenticationRoute<C> route : routes) {
      if (route.authorizationScheme()
          != SmartHttpAuthenticationRoute.AuthorizationScheme.BEARER) {
        continue;
      }
      String prefix = route.rawBearerPrefix();
      if (prefix == null) {
        externalBearer = route;
      } else if (credential.value().startsWith(prefix)) {
        return route;
      }
    }
    return externalBearer;
  }

  private static Credential authorizationCredential(HttpServletRequest request)
      throws ServiceNotAuthorizedException, ServiceMayNotContinueException {
    String header;
    try {
      Enumeration<String> headers = request.getHeaders("Authorization");
      if (headers == null || !headers.hasMoreElements()) {
        throw unauthorized();
      }
      header = headers.nextElement();
      if (headers.hasMoreElements()) {
        throw unauthorized();
      }
    } catch (ServiceNotAuthorizedException denied) {
      throw denied;
    } catch (RuntimeException failure) {
      throw unavailable();
    }

    if (header == null
        || header.length() > MAX_AUTHORIZATION_HEADER_LENGTH
        || !header.equals(header.strip())) {
      throw unauthorized();
    }
    int separator = header.indexOf(' ');
    if (separator < 1 || separator != header.lastIndexOf(' ')) {
      throw unauthorized();
    }
    String schemeValue = header.substring(0, separator).toUpperCase(Locale.ROOT);
    String value = header.substring(separator + 1);
    if (value.isEmpty() || containsWhitespaceOrControl(value)) {
      throw unauthorized();
    }

    SmartHttpAuthenticationRoute.AuthorizationScheme scheme =
        switch (schemeValue) {
          case "BASIC" -> SmartHttpAuthenticationRoute.AuthorizationScheme.BASIC;
          case "BEARER" -> SmartHttpAuthenticationRoute.AuthorizationScheme.BEARER;
          default -> null;
        };
    if (scheme == null) {
      throw unauthorized();
    }
    return new Credential(scheme, value);
  }

  private static boolean containsWhitespaceOrControl(String value) {
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (Character.isWhitespace(character) || Character.isISOControl(character)) {
        return true;
      }
    }
    return false;
  }

  private static <C> List<SmartHttpAuthenticationRoute<C>> validatedRoutes(
      List<SmartHttpAuthenticationRoute<C>> routes) {
    Objects.requireNonNull(routes, "routes");
    if (routes.isEmpty() || routes.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("routes must contain at least one non-null route");
    }

    List<SmartHttpAuthenticationRoute<C>> copy = List.copyOf(routes);
    int basicRoutes = 0;
    int externalBearerRoutes = 0;
    Set<String> handlerIds = new HashSet<>();
    List<String> prefixes = new ArrayList<>();
    for (SmartHttpAuthenticationRoute<C> route : copy) {
      if (!handlerIds.add(route.handlerId())) {
        throw new IllegalArgumentException("handler IDs must be unique");
      }
      if (route.authorizationScheme()
          == SmartHttpAuthenticationRoute.AuthorizationScheme.BASIC) {
        basicRoutes++;
      } else if (route.rawBearerPrefix() == null) {
        externalBearerRoutes++;
      } else {
        prefixes.add(route.rawBearerPrefix());
      }
    }
    if (basicRoutes > 1) {
      throw new IllegalArgumentException("only one Basic route may be configured");
    }
    if (externalBearerRoutes > 1) {
      throw new IllegalArgumentException(
          "only one unprefixed external Bearer route may be configured");
    }
    for (int left = 0; left < prefixes.size(); left++) {
      for (int right = left + 1; right < prefixes.size(); right++) {
        String first = prefixes.get(left);
        String second = prefixes.get(right);
        if (first.startsWith(second) || second.startsWith(first)) {
          throw new IllegalArgumentException(
              "Bearer namespaces must not overlap: " + first + " and " + second);
        }
      }
    }
    return copy;
  }

  private static String applicationHandlerId(String value) {
    Objects.requireNonNull(value, "handlerId");
    if (value.isBlank() || value.length() > MAX_APPLICATION_HANDLER_ID_LENGTH) {
      throw new IllegalArgumentException(
          "handlerId must contain 1 to "
              + MAX_APPLICATION_HANDLER_ID_LENGTH
              + " non-blank characters");
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character < 0x21
          || character > 0x7e
          || Character.isWhitespace(character)
          || Character.isISOControl(character)) {
        throw new IllegalArgumentException(
            "handlerId must contain visible non-whitespace ASCII");
      }
    }
    return value;
  }

  private static ServiceMayNotContinueException sanitize(
      ServiceMayNotContinueException failure) {
    return switch (failure.getStatusCode()) {
      case HttpServletResponse.SC_UNAUTHORIZED ->
          new ServiceMayNotContinueException(
              "Authentication required", HttpServletResponse.SC_UNAUTHORIZED);
      case HttpServletResponse.SC_FORBIDDEN ->
          new ServiceMayNotContinueException(
              "Authentication forbidden", HttpServletResponse.SC_FORBIDDEN);
      default -> unavailable();
    };
  }

  private static ServiceNotAuthorizedException unauthorized() {
    return new ServiceNotAuthorizedException();
  }

  private static ServiceMayNotContinueException insecureTransport() {
    return new ServiceMayNotContinueException(
        "Secure transport required", HttpServletResponse.SC_FORBIDDEN);
  }

  private static ServiceMayNotContinueException unavailable() {
    return new ServiceMayNotContinueException(
        "Authentication service unavailable",
        HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
  }

  private record Credential(
      SmartHttpAuthenticationRoute.AuthorizationScheme scheme, String value) {}
}
