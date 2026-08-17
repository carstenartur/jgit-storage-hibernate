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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityAuthenticationReason;
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityAuthenticationTrace;
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityAuditOutcome;
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityCredentialKind;
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityEntities;
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityManagementOperation;
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityManagementRequest;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalEntity;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalStatus;
import io.github.carstenartur.jgit.storage.hibernate.security.entity.SecurityPrincipalType;
import jakarta.servlet.http.HttpServletRequest;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;

class SecuritySmartHttpAccessContextProviderTest {

  private static final AtomicInteger DATABASE_COUNTER = new AtomicInteger();
  private static final Instant START = Instant.parse("2026-08-16T08:00:00Z");
  private static final GitAccessContext ADMIN =
      new GitAccessContext(
          "admin", Set.of(), "oidc", "admin-session", "admin-correlation", Map.of());

  @Test
  void authenticatesUtf8BasicAndBearerAndClearsDecodedPassword() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider("success")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistPrincipal(sessionFactory, "alice", "alice");
      CapturingPasswordHasher passwordHasher = new CapturingPasswordHasher();
      HibernateSecurityIdentityAuditService audit =
          new HibernateSecurityIdentityAuditService(sessionFactory);
      HibernateSecurityCredentialService credentials =
          credentials(sessionFactory, passwordHasher, audit);
      setPassword(credentials, "sëcret");
      IssuedAccessToken token =
          credentials.issueAccessToken(
              SecurityManagementRequest.issueToken(ADMIN, "alice"),
              Set.of(GitRepositoryPermission.READ),
              Instant.now().plus(Duration.ofHours(1)));

      SecuritySmartHttpAccessContextProvider authentication =
          new SecuritySmartHttpAccessContextProvider(credentials);
      AuthenticatedGitAccess passwordAccess =
          authentication.require(request(true, basic("alice", "sëcret")));
      assertEquals("alice", passwordAccess.context().principalId());
      assertEquals(SecurityCredentialKind.PASSWORD, passwordAccess.credentialKind());
      assertCleared(passwordHasher.lastVerified());

      AuthenticatedGitAccess tokenAccess =
          authentication.require(request(true, "Bearer " + token.tokenValue()));
      assertEquals("alice", tokenAccess.context().principalId());
      assertEquals(SecurityCredentialKind.ACCESS_TOKEN, tokenAccess.credentialKind());
    }
  }

  @Test
  void rejectsTransportHeaderSchemeAndCredentialFailuresWithoutDisclosure() throws Exception {
    try (HibernateSessionFactoryProvider provider = provider("denied")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistPrincipal(sessionFactory, "alice", "alice");
      HibernateSecurityCredentialService credentials =
          credentials(
              sessionFactory,
              new CapturingPasswordHasher(),
              new HibernateSecurityIdentityAuditService(sessionFactory));
      setPassword(credentials, "secret");
      SecuritySmartHttpAccessContextProvider authentication =
          authentication(credentials, "denied");
      String valid = basic("alice", "secret");

      ServiceMayNotContinueException insecure =
          assertThrows(
              ServiceMayNotContinueException.class,
              () -> authentication.require(request(false, valid)));
      assertEquals(403, insecure.getStatusCode());
      assertNull(insecure.getCause());
      assertFalse(String.valueOf(insecure.getMessage()).contains("secret"));

      List<HttpServletRequest> rejectedRequests =
          List.of(
              request(true),
              request(true, valid, "Bearer duplicate"),
              request(true, " " + valid),
              request(true, "Basic  value"),
              request(true, "Basic\tvalue"),
              request(true, "Digest value"),
              request(true, "Bearer malformed"),
              request(true, "Bearer " + "x".repeat(8192)));
      for (HttpServletRequest rejected : rejectedRequests) {
        assertThrows(ServiceNotAuthorizedException.class, () -> authentication.require(rejected));
      }

      SecuritySmartHttpAccessContextProvider bearerOnly =
          authentication(
              credentials,
              Set.of(SecuritySmartHttpAuthenticationMethod.BEARER),
              "bearer-only",
              HttpServletRequest::isSecure);
      assertThrows(
          ServiceNotAuthorizedException.class,
          () -> bearerOnly.require(request(true, valid)));

      String wrong = basic("alice", "wrong-secret");
      ServiceNotAuthorizedException denied =
          assertThrows(
              ServiceNotAuthorizedException.class,
              () -> authentication.require(request(true, wrong)));
      assertNull(denied.getCause());
      assertFalse(String.valueOf(denied.getMessage()).contains("wrong-secret"));
      assertFalse(String.valueOf(denied.getMessage()).contains("INVALID_CREDENTIALS"));
    }
  }

  @Test
  void malformedBasicRunsDummyVerifierAndPersistsBoundedDenials() {
    try (HibernateSessionFactoryProvider provider = provider("malformed")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      CapturingPasswordHasher passwordHasher = new CapturingPasswordHasher();
      HibernateSecurityIdentityAuditService audit =
          new HibernateSecurityIdentityAuditService(sessionFactory);
      HibernateSecurityCredentialService credentials =
          credentials(sessionFactory, passwordHasher, audit);
      SecuritySmartHttpAccessContextProvider authentication =
          authentication(credentials, "malformed");

      byte[] invalidUtf8 = {(byte) 0xc3, (byte) 0x28, (byte) ':', (byte) 'x'};
      byte[] oversized = new byte[4097];
      Arrays.fill(oversized, (byte) 'a');
      List<String> malformed =
          List.of(
              "Basic !!!",
              "Basic "
                  + Base64.getEncoder()
                      .encodeToString("alice".getBytes(StandardCharsets.UTF_8)),
              "Basic " + Base64.getEncoder().encodeToString(invalidUtf8),
              "Basic " + Base64.getEncoder().encodeToString(oversized));

      for (String header : malformed) {
        assertThrows(
            ServiceNotAuthorizedException.class,
            () -> authentication.require(request(true, header)));
      }
      assertCleared(passwordHasher.lastVerified());
      long denied =
          audit.findByCorrelationId("malformed", 10).stream()
              .filter(
                  event ->
                      event.record().outcome() == SecurityAuditOutcome.DENIED
                          && SecurityAuthenticationReason.INVALID_CREDENTIALS.name()
                              .equals(event.record().reasonCode()))
              .count();
      assertEquals(malformed.size(), denied);
    }
  }

  @Test
  void trustedTransportOverridesAreExplicitAndInfrastructureFailuresRemainServerErrors()
      throws Exception {
    HibernateSessionFactoryProvider provider = provider("failure");
    try {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistPrincipal(sessionFactory, "alice", "alice");
      HibernateSecurityCredentialService credentials =
          credentials(
              sessionFactory,
              new CapturingPasswordHasher(),
              new HibernateSecurityIdentityAuditService(sessionFactory));
      setPassword(credentials, "secret");

      SecuritySmartHttpAccessContextProvider trustedProxy =
          authentication(
              credentials,
              Set.of(SecuritySmartHttpAuthenticationMethod.BASIC),
              "trusted-proxy",
              ignored -> true);
      assertEquals(
          "alice",
          trustedProxy
              .require(request(false, basic("alice", "secret")))
              .context()
              .principalId());

      SecuritySmartHttpAccessContextProvider brokenTransport =
          authentication(
              credentials,
              Set.of(SecuritySmartHttpAuthenticationMethod.BASIC),
              "broken-transport",
              ignored -> {
                throw new IllegalStateException("trusted proxy state unavailable");
              });
      ServiceMayNotContinueException transportFailure =
          assertThrows(
              ServiceMayNotContinueException.class,
              () -> brokenTransport.require(request(true, basic("alice", "secret"))));
      assertEquals(500, transportFailure.getStatusCode());

      SecuritySmartHttpAccessContextProvider brokenTrace =
          new SecuritySmartHttpAccessContextProvider(
              credentials,
              Set.of(SecuritySmartHttpAuthenticationMethod.BASIC),
              ignored -> {
                throw new IllegalStateException("trace store unavailable");
              },
              ignored -> true);
      ServiceMayNotContinueException traceFailure =
          assertThrows(
              ServiceMayNotContinueException.class,
              () -> brokenTrace.require(request(true, basic("alice", "secret"))));
      assertEquals(500, traceFailure.getStatusCode());

      IssuedAccessToken token =
          credentials.issueAccessToken(
              SecurityManagementRequest.issueToken(ADMIN, "alice"),
              Set.of(GitRepositoryPermission.READ),
              Instant.now().plus(Duration.ofHours(1)));
      SecuritySmartHttpAccessContextProvider authentication =
          authentication(credentials, "closed-store");
      sessionFactory.close();

      ServiceMayNotContinueException storeFailure =
          assertThrows(
              ServiceMayNotContinueException.class,
              () ->
                  authentication.require(
                      request(true, "Bearer " + token.tokenValue())));
      assertEquals(500, storeFailure.getStatusCode());
      assertNotNull(storeFailure.getCause());
      assertFalse(String.valueOf(storeFailure.getMessage()).contains(token.tokenValue()));
    } finally {
      provider.close();
    }
  }

  @Test
  void rejectsInvalidConstruction() {
    try (HibernateSessionFactoryProvider provider = provider("construction")) {
      HibernateSecurityCredentialService credentials =
          credentials(
              provider.getSessionFactory(),
              new CapturingPasswordHasher(),
              new HibernateSecurityIdentityAuditService(provider.getSessionFactory()));
      SecuritySmartHttpTraceProvider trace =
          ignored -> SecurityAuthenticationTrace.withoutRemoteAddress("session", "correlation");

      assertThrows(
          NullPointerException.class,
          () -> new SecuritySmartHttpAccessContextProvider(null));
      assertThrows(
          IllegalArgumentException.class,
          () -> new SecuritySmartHttpAccessContextProvider(credentials, Set.of(), trace));
      Set<SecuritySmartHttpAuthenticationMethod> nullMethod = new HashSet<>();
      nullMethod.add(null);
      assertThrows(
          IllegalArgumentException.class,
          () -> new SecuritySmartHttpAccessContextProvider(credentials, nullMethod, trace));
      assertThrows(
          NullPointerException.class,
          () -> new SecuritySmartHttpAccessContextProvider(credentials, null, trace));
      assertThrows(
          NullPointerException.class,
          () ->
              new SecuritySmartHttpAccessContextProvider(
                  credentials,
                  Set.of(SecuritySmartHttpAuthenticationMethod.BASIC),
                  null));
      assertThrows(
          NullPointerException.class,
          () ->
              new SecuritySmartHttpAccessContextProvider(
                  credentials,
                  Set.of(SecuritySmartHttpAuthenticationMethod.BASIC),
                  trace,
                  null));
    }
  }

  private static SecuritySmartHttpAccessContextProvider authentication(
      HibernateSecurityCredentialService credentials, String correlationId) {
    return authentication(
        credentials,
        Set.of(
            SecuritySmartHttpAuthenticationMethod.BASIC,
            SecuritySmartHttpAuthenticationMethod.BEARER),
        correlationId,
        HttpServletRequest::isSecure);
  }

  private static SecuritySmartHttpAccessContextProvider authentication(
      HibernateSecurityCredentialService credentials,
      Set<SecuritySmartHttpAuthenticationMethod> methods,
      String correlationId,
      java.util.function.Predicate<HttpServletRequest> secureTransport) {
    return new SecuritySmartHttpAccessContextProvider(
        credentials,
        methods,
        request ->
            SecurityAuthenticationTrace.withoutRemoteAddress(
                "session-" + correlationId, correlationId),
        secureTransport);
  }

  private static HibernateSecurityCredentialService credentials(
      SessionFactory sessionFactory,
      PasswordHasher passwordHasher,
      HibernateSecurityIdentityAuditService audit) {
    return new HibernateSecurityCredentialService(
        sessionFactory,
        passwordHasher,
        new TestTokenHasher(),
        request -> {},
        audit);
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
    return (HttpServletRequest)
        Proxy.newProxyInstance(
            SecuritySmartHttpAccessContextProviderTest.class.getClassLoader(),
            new Class<?>[] {HttpServletRequest.class},
            (proxy, method, arguments) ->
                switch (method.getName()) {
                  case "isSecure" -> secure;
                  case "getHeaders" ->
                      "Authorization".equals(arguments[0])
                          ? Collections.enumeration(headers)
                          : Collections.emptyEnumeration();
                  case "getHeader" ->
                      "Authorization".equals(arguments[0]) && !headers.isEmpty()
                          ? headers.getFirst()
                          : null;
                  case "toString" -> "SecuritySmartHttpTestRequest";
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

  private static void assertCleared(char[] value) {
    assertNotNull(value);
    for (char character : value) {
      assertEquals('\0', character);
    }
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
        "jdbc:h2:mem:smart-http-auth-"
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

  private static final class CapturingPasswordHasher implements PasswordHasher {

    private final AtomicReference<char[]> lastVerified = new AtomicReference<>();

    @Override
    public PasswordHash hash(char[] password) {
      return new PasswordHash("TEST-PASSWORD", 1, "value:" + new String(password));
    }

    @Override
    public boolean verify(char[] password, PasswordHash expected) {
      lastVerified.set(password);
      return expected.encodedHash().equals("value:" + new String(password));
    }

    @Override
    public boolean needsRehash(PasswordHash existing) {
      return false;
    }

    char[] lastVerified() {
      return lastVerified.get();
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
