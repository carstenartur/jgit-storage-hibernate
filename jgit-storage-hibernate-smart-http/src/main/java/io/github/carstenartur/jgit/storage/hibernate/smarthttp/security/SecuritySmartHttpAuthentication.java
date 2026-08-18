/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.smarthttp.security;

import io.github.carstenartur.jgit.storage.hibernate.security.AuthenticatedGitAccess;
import io.github.carstenartur.jgit.storage.hibernate.security.HibernateSecurityCredentialService;
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityAccessTokenNamespace;
import io.github.carstenartur.jgit.storage.hibernate.smarthttp.RoutingSmartHttpAccessContextProvider;
import io.github.carstenartur.jgit.storage.hibernate.smarthttp.SmartHttpAuthenticationHandler;
import io.github.carstenartur.jgit.storage.hibernate.smarthttp.SmartHttpAuthenticationRoute;
import io.github.carstenartur.jgit.storage.hibernate.smarthttp.SmartHttpChallengeOwner;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Safe routing factories for local Security credentials and host-validated external bearers. */
public final class SecuritySmartHttpAuthentication {

  private static final String LOCAL_BASIC_HANDLER_ID = "security-local-basic";
  private static final String LOCAL_TOKEN_HANDLER_ID = "security-access-token-v1";

  private SecuritySmartHttpAuthentication() {}

  /** Configure local Basic plus local Security access tokens using servlet TLS state. */
  public static RoutingSmartHttpAccessContextProvider<AuthenticatedGitAccess>
      localBasicAndAccessToken(
          HibernateSecurityCredentialService credentialService,
          SecuritySmartHttpTraceProvider traceProvider,
          SmartHttpChallengeOwner challengeOwner) {
    return localBasicAndAccessToken(
        credentialService,
        traceProvider,
        challengeOwner,
        HttpServletRequest::isSecure);
  }

  /** Configure local Basic plus local access tokens with trusted transport integration. */
  public static RoutingSmartHttpAccessContextProvider<AuthenticatedGitAccess>
      localBasicAndAccessToken(
          HibernateSecurityCredentialService credentialService,
          SecuritySmartHttpTraceProvider traceProvider,
          SmartHttpChallengeOwner challengeOwner,
          Predicate<HttpServletRequest> secureTransport) {
    LocalHandlers handlers = localHandlers(credentialService, traceProvider);
    return router(
        List.of(
            SmartHttpAuthenticationRoute.securityLocalBasic(
                LOCAL_BASIC_HANDLER_ID, handlers.basic()),
            SmartHttpAuthenticationRoute.securityAccessToken(
                LOCAL_TOKEN_HANDLER_ID,
                SecurityAccessTokenNamespace.VERSION_1_BEARER_PREFIX,
                handlers.bearer())),
        challengeOwner,
        secureTransport);
  }

  /** Configure only local prefixed access tokens using servlet TLS state. */
  public static RoutingSmartHttpAccessContextProvider<AuthenticatedGitAccess>
      localAccessTokenOnly(
          HibernateSecurityCredentialService credentialService,
          SecuritySmartHttpTraceProvider traceProvider,
          SmartHttpChallengeOwner challengeOwner) {
    return localAccessTokenOnly(
        credentialService,
        traceProvider,
        challengeOwner,
        HttpServletRequest::isSecure);
  }

  /** Configure only local prefixed access tokens with trusted transport integration. */
  public static RoutingSmartHttpAccessContextProvider<AuthenticatedGitAccess>
      localAccessTokenOnly(
          HibernateSecurityCredentialService credentialService,
          SecuritySmartHttpTraceProvider traceProvider,
          SmartHttpChallengeOwner challengeOwner,
          Predicate<HttpServletRequest> secureTransport) {
    LocalHandlers handlers = localHandlers(credentialService, traceProvider);
    return router(
        List.of(
            SmartHttpAuthenticationRoute.securityAccessToken(
                LOCAL_TOKEN_HANDLER_ID,
                SecurityAccessTokenNamespace.VERSION_1_BEARER_PREFIX,
                handlers.bearer())),
        challengeOwner,
        secureTransport);
  }

  /**
   * Configure a host-validated external bearer plus clearly namespaced local Security tokens.
   *
   * <p>Any bearer beginning with the reserved local namespace is sent only to the local verifier.
   * Every other bearer is sent only to the application handler. A denial never falls back.
   */
  public static RoutingSmartHttpAccessContextProvider<AuthenticatedGitAccess>
      externalBearerAndAccessToken(
          HibernateSecurityCredentialService credentialService,
          SecuritySmartHttpTraceProvider traceProvider,
          String externalHandlerId,
          SmartHttpAuthenticationHandler<AuthenticatedGitAccess> externalBearerHandler,
          SmartHttpChallengeOwner challengeOwner) {
    return externalBearerAndAccessToken(
        credentialService,
        traceProvider,
        externalHandlerId,
        externalBearerHandler,
        challengeOwner,
        HttpServletRequest::isSecure);
  }

  /** Configure external bearer plus local token with trusted transport integration. */
  public static RoutingSmartHttpAccessContextProvider<AuthenticatedGitAccess>
      externalBearerAndAccessToken(
          HibernateSecurityCredentialService credentialService,
          SecuritySmartHttpTraceProvider traceProvider,
          String externalHandlerId,
          SmartHttpAuthenticationHandler<AuthenticatedGitAccess> externalBearerHandler,
          SmartHttpChallengeOwner challengeOwner,
          Predicate<HttpServletRequest> secureTransport) {
    LocalHandlers handlers = localHandlers(credentialService, traceProvider);
    return router(
        List.of(
            SmartHttpAuthenticationRoute.securityAccessToken(
                LOCAL_TOKEN_HANDLER_ID,
                SecurityAccessTokenNamespace.VERSION_1_BEARER_PREFIX,
                handlers.bearer()),
            SmartHttpAuthenticationRoute.externalBearer(
                externalHandlerId, externalBearerHandler)),
        challengeOwner,
        secureTransport);
  }

  /**
   * Configure external bearer, local access token and a deliberately separate local Basic profile.
   *
   * <p>This mode must not be used to forward an identity-provider password into the local Security
   * password verifier. It is intended only for deployments that explicitly maintain separate local
   * credentials.
   */
  public static RoutingSmartHttpAccessContextProvider<AuthenticatedGitAccess>
      externalBearerAccessTokenAndLocalBasic(
          HibernateSecurityCredentialService credentialService,
          SecuritySmartHttpTraceProvider traceProvider,
          String externalHandlerId,
          SmartHttpAuthenticationHandler<AuthenticatedGitAccess> externalBearerHandler,
          SmartHttpChallengeOwner challengeOwner) {
    return externalBearerAccessTokenAndLocalBasic(
        credentialService,
        traceProvider,
        externalHandlerId,
        externalBearerHandler,
        challengeOwner,
        HttpServletRequest::isSecure);
  }

  /** Configure all three mechanisms with trusted transport integration. */
  public static RoutingSmartHttpAccessContextProvider<AuthenticatedGitAccess>
      externalBearerAccessTokenAndLocalBasic(
          HibernateSecurityCredentialService credentialService,
          SecuritySmartHttpTraceProvider traceProvider,
          String externalHandlerId,
          SmartHttpAuthenticationHandler<AuthenticatedGitAccess> externalBearerHandler,
          SmartHttpChallengeOwner challengeOwner,
          Predicate<HttpServletRequest> secureTransport) {
    LocalHandlers handlers = localHandlers(credentialService, traceProvider);
    return router(
        List.of(
            SmartHttpAuthenticationRoute.securityLocalBasic(
                LOCAL_BASIC_HANDLER_ID, handlers.basic()),
            SmartHttpAuthenticationRoute.securityAccessToken(
                LOCAL_TOKEN_HANDLER_ID,
                SecurityAccessTokenNamespace.VERSION_1_BEARER_PREFIX,
                handlers.bearer()),
            SmartHttpAuthenticationRoute.externalBearer(
                externalHandlerId, externalBearerHandler)),
        challengeOwner,
        secureTransport);
  }

  private static RoutingSmartHttpAccessContextProvider<AuthenticatedGitAccess> router(
      List<SmartHttpAuthenticationRoute<AuthenticatedGitAccess>> routes,
      SmartHttpChallengeOwner challengeOwner,
      Predicate<HttpServletRequest> secureTransport) {
    return new RoutingSmartHttpAccessContextProvider<>(
        routes,
        Objects.requireNonNull(challengeOwner, "challengeOwner"),
        Objects.requireNonNull(secureTransport, "secureTransport"));
  }

  private static LocalHandlers localHandlers(
      HibernateSecurityCredentialService credentialService,
      SecuritySmartHttpTraceProvider traceProvider) {
    HibernateSecurityCredentialService credentials =
        Objects.requireNonNull(credentialService, "credentialService");
    SecuritySmartHttpTraceProvider traces =
        Objects.requireNonNull(traceProvider, "traceProvider");
    return new LocalHandlers(
        localHandler(credentials, traces, SecuritySmartHttpAuthenticationMethod.BASIC),
        localHandler(credentials, traces, SecuritySmartHttpAuthenticationMethod.BEARER));
  }

  private static SmartHttpAuthenticationHandler<AuthenticatedGitAccess> localHandler(
      HibernateSecurityCredentialService credentialService,
      SecuritySmartHttpTraceProvider traceProvider,
      SecuritySmartHttpAuthenticationMethod method) {
    SecuritySmartHttpAccessContextProvider provider =
        new SecuritySmartHttpAccessContextProvider(
            credentialService, Set.of(method), traceProvider, ignored -> true);
    String scheme =
        method == SecuritySmartHttpAuthenticationMethod.BASIC ? "Basic" : "Bearer";
    return (request, credential) ->
        provider.require(new RoutedAuthorizationRequest(request, scheme + " " + credential));
  }

  private record LocalHandlers(
      SmartHttpAuthenticationHandler<AuthenticatedGitAccess> basic,
      SmartHttpAuthenticationHandler<AuthenticatedGitAccess> bearer) {}

  private static final class RoutedAuthorizationRequest extends HttpServletRequestWrapper {

    private final String authorization;

    RoutedAuthorizationRequest(HttpServletRequest request, String authorization) {
      super(Objects.requireNonNull(request, "request"));
      this.authorization = Objects.requireNonNull(authorization, "authorization");
    }

    @Override
    public boolean isSecure() {
      return true;
    }

    @Override
    public String getHeader(String name) {
      return "Authorization".equalsIgnoreCase(name)
          ? authorization
          : super.getHeader(name);
    }

    @Override
    public java.util.Enumeration<String> getHeaders(String name) {
      return "Authorization".equalsIgnoreCase(name)
          ? Collections.enumeration(List.of(authorization))
          : super.getHeaders(name);
    }
  }
}
