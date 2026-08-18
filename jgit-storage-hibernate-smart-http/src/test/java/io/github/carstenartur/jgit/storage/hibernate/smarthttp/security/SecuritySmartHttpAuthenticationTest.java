/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.smarthttp.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.carstenartur.jgit.storage.hibernate.config.HibernateSessionFactoryProvider;
import io.github.carstenartur.jgit.storage.hibernate.security.AccessTokenHash;
import io.github.carstenartur.jgit.storage.hibernate.security.AccessTokenHasher;
import io.github.carstenartur.jgit.storage.hibernate.security.AuthenticatedGitAccess;
import io.github.carstenartur.jgit.storage.hibernate.security.GitAccessContext;
import io.github.carstenartur.jgit.storage.hibernate.security.GitRepositoryPermission;
import io.github.carstenartur.jgit.storage.hibernate.security.HibernateSecurityCredentialService;
import io.github.carstenartur.jgit.storage.hibernate.security.HibernateSecurityIdentityAuditService;
import io.github.carstenartur.jgit.storage.hibernate.security.IssuedAccessToken;
import io.github.carstenartur.jgit.storage.hibernate.security.PasswordHash;
import io.github.carstenartur.jgit.storage.hibernate.security.PasswordHasher;
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityAuthenticationTrace;
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityCredentialKind;
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityEntities;
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityManagementOperation;
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityManagementRequest;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalStatus;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalType;
import io.github.carstenartur.jgit.storage.hibernate.smarthttp.RoutingSmartHttpAccessContextProvider;
import io.github.carstenartur.jgit.storage.hibernate.smarthttp.SmartHttpChallengeOwner;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

class SecuritySmartHttpAuthenticationTest {

  private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();
  private static final Instant START = Instant.parse("2026-08-18T12:00:00Z");
  private static final GitAccessContext ADMIN =
      new GitAccessContext(
          "admin", Set.of(), "oidc", "admin-session", "admin-correlation", Map.of());

  @Test
  void routesExternalBearerAndIssuedLocalTokenWithoutFallback() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider("mixed")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistPrincipal(sessionFactory, "alice", "alice");
      HibernateSecurityCredentialService credentials = credentials(sessionFactory);
      IssuedAccessToken token =
          credentials.issueAccessToken(
              SecurityManagementRequest.issueToken(ADMIN, "alice"),
              Set.of(GitRepositoryPermission.READ),
              Instant.now().plus(Duration.ofHours(1)));

      AtomicInteger externalCalls = new AtomicInteger();
      AuthenticatedGitAccess externalAccess =
          AuthenticatedGitAccess.unrestricted(
              new GitAccessContext(
                  "alice",
                  Set.of("oidc-users"),
                  "oidc",
                  "external-session",
                  "external-correlation",
                  Map.of()),
              SecurityCredentialKind.EXTERNAL,
              "external-oidc",
              1);
      RoutingSmartHttpAccessContextProvider<AuthenticatedGitAccess> authentication =
          SecuritySmartHttpAuthentication.externalBearerAndAccessToken(
              credentials,
              trace("mixed"),
              "oidc",
              (request, credential) -> {
                externalCalls.incrementAndGet();
                if (!"external.jwt.value".equals(credential)) {
                  throw new ServiceNotAuthorizedException();
                }
                return externalAccess;
              },
              SmartHttpChallengeOwner.APPLICATION,
              ignored -> true);

      AuthenticatedGitAccess external =
          authentication.require(request(false, "Bearer external.jwt.value"));
      assertEquals(SecurityCredentialKind.EXTERNAL, external.credentialKind());
      assertEquals("alice", external.context().principalId());
      assertEquals(1, externalCalls.get());

      AuthenticatedGitAccess local =
          authentication.require(request(false, "Bearer " + token.tokenValue()));
      assertEquals(SecurityCredentialKind.ACCESS_TOKEN, local.credentialKind());
      assertEquals(token.metadata().tokenId(), local.credentialId());
      assertEquals("alice", local.context().principalId());
      assertEquals(1, externalCalls.get());

      assertThrows(
          ServiceNotAuthorizedException.class,
          () -> authentication.require(request(false, "Bearer jsh_invalid")));
      assertEquals(1, externalCalls.get());
    }
  }

  @Test
  void localBasicIsEnabledOnlyByAnExplicitFactory() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider("basic")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistPrincipal(sessionFactory, "alice", "alice");
      HibernateSecurityCredentialService credentials = credentials(sessionFactory);
      setPassword(credentials, "secret");
      String authorization = basic("alice", "secret");

      RoutingSmartHttpAccessContextProvider<AuthenticatedGitAccess> externalAndToken =
          SecuritySmartHttpAuthentication.externalBearerAndAccessToken(
              credentials,
              trace("external-and-token"),
              "oidc",
              (request, credential) -> {
                throw new ServiceNotAuthorizedException();
              },
              SmartHttpChallengeOwner.APPLICATION,
              ignored -> true);
      assertThrows(
          ServiceNotAuthorizedException.class,
          () -> externalAndToken.require(request(false, authorization)));

      RoutingSmartHttpAccessContextProvider<AuthenticatedGitAccess> tokenOnly =
          SecuritySmartHttpAuthentication.localAccessTokenOnly(
              credentials,
              trace("token-only"),
              SmartHttpChallengeOwner.APPLICATION,
              ignored -> true);
      assertThrows(
          ServiceNotAuthorizedException.class,
          () -> tokenOnly.require(request(false, authorization)));

      RoutingSmartHttpAccessContextProvider<AuthenticatedGitAccess> local =
          SecuritySmartHttpAuthentication.localBasicAndAccessToken(
              credentials,
              trace("local"),
              SmartHttpChallengeOwner.LIBRARY,
              ignored -> true);
      AuthenticatedGitAccess localAccess = local.require(request(false, authorization));
      assertEquals(SecurityCredentialKind.PASSWORD, localAccess.credentialKind());
      assertEquals("alice", localAccess.context().principalId());
      assertNotNull(local.challengeFilter("Git"));

      RoutingSmartHttpAccessContextProvider<AuthenticatedGitAccess> mixedWithBasic =
          SecuritySmartHttpAuthentication.externalBearerAccessTokenAndLocalBasic(
              credentials,
              trace("mixed-with-basic"),
              "oidc",
              (request, credential) -> {
                throw new ServiceNotAuthorizedException();
              },
              SmartHttpChallengeOwner.APPLICATION,
              ignored -> true);
      assertEquals(
          SecurityCredentialKind.PASSWORD,
          mixedWithBasic.require(request(false, authorization)).credentialKind());
    }
  }

  private static SecuritySmartHttpTraceProvider trace(String correlationId) {
    return request ->
        SecurityAuthenticationTrace.withoutRemoteAddress(
            "session-" + correlationId, correlationId);
  }

  private static HibernateSecurityCredentialService credentials(SessionFactory sessionFactory) {
    return new HibernateSecurityCredentialService(
        sessionFactory,
        new TestPasswordHasher(),
        new TestTokenHasher(),
        request -> {},
        new HibernateSecurityIdentityAuditService(sessionFactory));
  }

  private static void setPassword(
      HibernateSecurityCredentialService credentials, String passwordValue) {
    char[] password = passwordValue.toCharArray();
    try {
      credentials.setPassword(
          SecurityManagementRequest.password(
              ADMIN, SecurityManagementOperation.SET_PASSWORD, "alice"),
          password);
    } finally {
      Arrays.fill(password, '\0');
    }
  }

  private static String basic(String loginName, String password) {
    return "Basic "
        + Base64.getEncoder()
            .encodeToString(
                (loginName + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  private static HttpServletRequest request(boolean secure, String... authorizationHeaders) {
    List<String> headers = List.of(authorizationHeaders);
    Map<String, Object> attributes = new HashMap<>();
    return (HttpServletRequest)
        Proxy.newProxyInstance(
            SecuritySmartHttpAuthenticationTest.class.getClassLoader(),
            new Class<?>[] {HttpServletRequest.class},
            (proxy, method, arguments) ->
                switch (method.getName()) {
                  case "isSecure" -> secure;
                  case "getHeaders" ->
                      "Authorization".equalsIgnoreCase(String.valueOf(arguments[0]))
                          ? Collections.enumeration(headers)
                          : Collections.emptyEnumeration();
                  case "getHeader" ->
                      "Authorization".equalsIgnoreCase(String.valueOf(arguments[0]))
                              && !headers.isEmpty()
                          ? headers.getFirst()
                          : null;
                  case "setAttribute" -> {
                    attributes.put((String) arguments[0], arguments[1]);
                    yield null;
                  }
                  case "getAttribute" -> attributes.get(arguments[0]);
                  case "toString" -> "SecuritySmartHttpAuthenticationTestRequest";
                  case "hashCode" -> System.identityHashCode(proxy);
                  case "equals" -> proxy == arguments[0];
                  default -> defaultValue(method.getReturnType());
                });
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

  private static void persistPrincipal(
      SessionFactory sessionFactory, String principalId, String loginName) {
    SecurityPrincipalEntity principal = new SecurityPrincipalEntity();
    principal.setPrincipalId(principalId);
    principal.setPrincipalType(SecurityPrincipalType.USER);
    principal.setLoginName(loginName);
    principal.setDisplayName(principalId);
    principal.setStatus(SecurityPrincipalStatus.ACTIVE);
    principal.setCreatedAt(START);
    principal.setUpdatedAt(START);
    principal.setSecurityVersion(1);
    sessionFactory.inTransaction(session -> session.persist(principal));
  }

  private static HibernateSessionFactoryProvider provider(String purpose) {
    Properties properties = new Properties();
    properties.put(
        "hibernate.connection.url",
        "jdbc:h2:mem:smart-http-routing-"
            + purpose
            + "-"
            + DATABASE_COUNTER.incrementAndGet()
            + ";DB_CLOSE_DELAY=-1");
    properties.put("hibernate.connection.driver_class", "org.h2.Driver");
    properties.put("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
    properties.put("hibernate.hbm2ddl.auto", "create-drop");
    properties.put("hibernate.show_sql", "false");
    return new HibernateSessionFactoryProvider(
        properties, SecurityEntities.annotatedClasses());
  }

  private static final class TestPasswordHasher implements PasswordHasher {

    @Override
    public PasswordHash hash(char[] password) {
      return new PasswordHash("TEST-PASSWORD", 1, "value:" + new String(password));
    }

    @Override
    public boolean verify(char[] password, PasswordHash expected) {
      return expected.encodedHash().equals("value:" + new String(password));
    }

    @Override
    public boolean needsRehash(PasswordHash existing) {
      return false;
    }
  }

  private static final class TestTokenHasher implements AccessTokenHasher {

    @Override
    public AccessTokenHash hash(String tokenValue) {
      return new AccessTokenHash("TEST-TOKEN", 1, "value:" + tokenValue);
    }

    @Override
    public boolean verify(String tokenValue, AccessTokenHash expected) {
      return expected.encodedHash().equals("value:" + tokenValue);
    }
  }
}
