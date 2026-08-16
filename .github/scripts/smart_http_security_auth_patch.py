#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one replacement match, found {count}")
    target.write_text(text.replace(old, new), encoding="utf-8")


def write(path: str, content: str) -> None:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


replace_once(
    "jgit-storage-hibernate-smart-http/pom.xml",
    """    <dependency>
      <groupId>io.github.carstenartur</groupId>
      <artifactId>jgit-storage-hibernate-core</artifactId>
    </dependency>
    <dependency>
      <groupId>org.eclipse.jgit</groupId>
""",
    """    <dependency>
      <groupId>io.github.carstenartur</groupId>
      <artifactId>jgit-storage-hibernate-core</artifactId>
    </dependency>
    <dependency>
      <groupId>io.github.carstenartur</groupId>
      <artifactId>jgit-storage-hibernate-security</artifactId>
      <optional>true</optional>
    </dependency>
    <dependency>
      <groupId>org.eclipse.jgit</groupId>
""",
)

replace_once(
    ".github/scripts/verify-module-boundaries.py",
    "    SMART_HTTP: {CORE},\n",
    "    SMART_HTTP: {CORE, SECURITY},\n",
)
replace_once(
    ".github/scripts/verify-module-boundaries.py",
    """    for source, targets in edges.items():
        if source == BOM or modules[source].packaging == "pom":
""",
    """    smart_http_security = [
        dependency
        for dependency in modules[SMART_HTTP].dependencies
        if dependency.production
        and dependency.group_id == PROJECT_GROUP
        and dependency.artifact_id == SECURITY
    ]
    if any(not dependency.optional for dependency in smart_http_security):
        raise BoundaryError(
            f"{SMART_HTTP} may integrate Security only through an optional dependency"
        )

    for source, targets in edges.items():
        if source == BOM or modules[source].packaging == "pom":
""",
)
replace_once(
    ".github/scripts/verify-module-boundaries.py",
    '            "- Smart HTTP may depend on Core only among project modules and exclusively owns JGit HTTP/Servlet integration.",\n',
    '            "- Smart HTTP depends on Core and may optionally integrate Security while exclusively owning JGit HTTP/Servlet types.",\n',
)

replace_once(
    ".github/scripts/test_verify_module_boundaries.py",
    """def dep(artifact: str, scope: str = "compile", group: str = MODULE.PROJECT_GROUP):
    return MODULE.Dependency(group, artifact, scope, False)
""",
    """def dep(
    artifact: str,
    scope: str = "compile",
    group: str = MODULE.PROJECT_GROUP,
    optional: bool = False,
):
    return MODULE.Dependency(group, artifact, scope, optional)
""",
)
replace_once(
    ".github/scripts/test_verify_module_boundaries.py",
    """        MODULE.SMART_HTTP: module(
            MODULE.SMART_HTTP,
            dep(MODULE.CORE),
            dep("org.eclipse.jgit.http.server", group="org.eclipse.jgit"),
""",
    """        MODULE.SMART_HTTP: module(
            MODULE.SMART_HTTP,
            dep(MODULE.CORE),
            dep(MODULE.SECURITY, optional=True),
            dep("org.eclipse.jgit.http.server", group="org.eclipse.jgit"),
""",
)
replace_once(
    ".github/scripts/test_verify_module_boundaries.py",
    "        self.assertEqual({MODULE.CORE}, edges[MODULE.SMART_HTTP])\n",
    "        self.assertEqual({MODULE.CORE, MODULE.SECURITY}, edges[MODULE.SMART_HTTP])\n",
)
replace_once(
    ".github/scripts/test_verify_module_boundaries.py",
    """    def test_rejects_smart_http_dependency_on_security(self) -> None:
        modules = valid_modules()
        modules[MODULE.SMART_HTTP] = module(
            MODULE.SMART_HTTP, dep(MODULE.CORE), dep(MODULE.SECURITY)
        )
        with self.assertRaisesRegex(
            MODULE.BoundaryError, "forbidden production module dependencies"
        ):
            MODULE.verify(modules)
""",
    """    def test_requires_smart_http_security_dependency_to_remain_optional(self) -> None:
        modules = valid_modules()
        modules[MODULE.SMART_HTTP] = module(
            MODULE.SMART_HTTP, dep(MODULE.CORE), dep(MODULE.SECURITY)
        )
        with self.assertRaisesRegex(MODULE.BoundaryError, "optional dependency"):
            MODULE.verify(modules)
""",
)

write(
    "jgit-storage-hibernate-smart-http/src/main/java/io/github/carstenartur/jgit/storage/hibernate/smarthttp/security/SecuritySmartHttpAuthenticationMethod.java",
    '''/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.smarthttp.security;

/** Local Security credential schemes accepted from an HTTP Authorization header. */
public enum SecuritySmartHttpAuthenticationMethod {
  /** RFC 7617-style username/password credentials encoded as UTF-8. */
  BASIC,

  /** A one-way Security access token supplied as a bearer credential. */
  BEARER
}
''',
)

write(
    "jgit-storage-hibernate-smart-http/src/main/java/io/github/carstenartur/jgit/storage/hibernate/smarthttp/security/SecuritySmartHttpTraceProvider.java",
    '''/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.smarthttp.security;

import io.github.carstenartur.jgit.storage.hibernate.security.SecurityAuthenticationTrace;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import java.util.UUID;

/** Creates bounded, non-secret credential-audit trace evidence for one Smart HTTP request. */
@FunctionalInterface
public interface SecuritySmartHttpTraceProvider {

  /**
   * Create trace evidence without copying credentials, headers or raw remote addresses.
   *
   * @param request current HTTP request
   * @return non-null bounded trace
   */
  SecurityAuthenticationTrace create(HttpServletRequest request);

  /**
   * Create opaque per-request session and correlation identifiers without remote-address evidence.
   *
   * <p>Applications that already own trusted request/session identifiers should supply an explicit
   * provider instead. Do not copy an untrusted forwarded header directly into audit evidence.
   */
  static SecuritySmartHttpTraceProvider opaquePerRequest() {
    return request -> {
      Objects.requireNonNull(request, "request");
      String requestId = UUID.randomUUID().toString();
      return SecurityAuthenticationTrace.withoutRemoteAddress(
          "smart-http-" + requestId, requestId);
    };
  }
}
''',
)

write(
    "jgit-storage-hibernate-smart-http/src/main/java/io/github/carstenartur/jgit/storage/hibernate/smarthttp/security/SecuritySmartHttpAccessContextProvider.java",
    '''/*
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
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityAuthenticationException;
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityAuthenticationReason;
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityAuthenticationTrace;
import io.github.carstenartur.jgit.storage.hibernate.security.SecurityIdentityAuditPersistenceException;
import io.github.carstenartur.jgit.storage.hibernate.smarthttp.SmartHttpAccessContextProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;

/**
 * Strict TLS-only Basic/Bearer adapter for {@link HibernateSecurityCredentialService}.
 *
 * <p>Exactly one Authorization header is accepted. Basic credentials use UTF-8, decoded password
 * arrays are cleared after authentication, bearer values are passed directly to the one-way token
 * service, and every client-facing rejection is generic. Credential/audit infrastructure failures
 * remain HTTP 500 errors instead of being mislabeled as invalid credentials.
 */
public final class SecuritySmartHttpAccessContextProvider
    implements SmartHttpAccessContextProvider<AuthenticatedGitAccess> {

  private static final int MAX_AUTHORIZATION_HEADER_LENGTH = 8192;
  private static final int MAX_BASIC_CREDENTIAL_BYTES = 4096;

  private final HibernateSecurityCredentialService credentialService;
  private final Set<SecuritySmartHttpAuthenticationMethod> methods;
  private final SecuritySmartHttpTraceProvider traceProvider;
  private final Predicate<HttpServletRequest> secureTransport;

  /** Accept Basic and Bearer credentials over requests for which {@code request.isSecure()} is true. */
  public SecuritySmartHttpAccessContextProvider(
      HibernateSecurityCredentialService credentialService) {
    this(
        credentialService,
        EnumSet.allOf(SecuritySmartHttpAuthenticationMethod.class),
        SecuritySmartHttpTraceProvider.opaquePerRequest(),
        HttpServletRequest::isSecure);
  }

  /** Accept the selected schemes with an application-owned trace provider and servlet TLS state. */
  public SecuritySmartHttpAccessContextProvider(
      HibernateSecurityCredentialService credentialService,
      Set<SecuritySmartHttpAuthenticationMethod> methods,
      SecuritySmartHttpTraceProvider traceProvider) {
    this(credentialService, methods, traceProvider, HttpServletRequest::isSecure);
  }

  /**
   * Create an adapter with an explicit trusted secure-transport predicate.
   *
   * <p>The predicate is intended for containers whose trusted proxy integration already establishes
   * the original HTTPS state. It must not trust a caller-controlled forwarding header directly.
   */
  public SecuritySmartHttpAccessContextProvider(
      HibernateSecurityCredentialService credentialService,
      Set<SecuritySmartHttpAuthenticationMethod> methods,
      SecuritySmartHttpTraceProvider traceProvider,
      Predicate<HttpServletRequest> secureTransport) {
    this.credentialService = Objects.requireNonNull(credentialService, "credentialService");
    Objects.requireNonNull(methods, "methods");
    if (methods.isEmpty() || methods.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("methods must contain at least one non-null method");
    }
    this.methods = Set.copyOf(methods);
    this.traceProvider = Objects.requireNonNull(traceProvider, "traceProvider");
    this.secureTransport = Objects.requireNonNull(secureTransport, "secureTransport");
  }

  @Override
  public AuthenticatedGitAccess require(HttpServletRequest request)
      throws ServiceNotAuthorizedException, ServiceMayNotContinueException {
    HttpServletRequest current = Objects.requireNonNull(request, "request");
    final boolean secure;
    try {
      secure = secureTransport.test(current);
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
    if (!secure) {
      throw unauthorized();
    }

    String header = singleAuthorizationHeader(current);
    SecurityAuthenticationTrace trace;
    try {
      trace = Objects.requireNonNull(traceProvider.create(current), "traceProvider result");
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }

    int separator = header.indexOf(' ');
    if (separator < 1 || separator != header.lastIndexOf(' ')) {
      throw unauthorized();
    }
    String scheme = header.substring(0, separator).toUpperCase(Locale.ROOT);
    String credential = header.substring(separator + 1);
    if (credential.isEmpty() || containsWhitespaceOrControl(credential)) {
      throw unauthorized();
    }

    if ("BASIC".equals(scheme)
        && methods.contains(SecuritySmartHttpAuthenticationMethod.BASIC)) {
      return authenticateBasic(credential, trace);
    }
    if ("BEARER".equals(scheme)
        && methods.contains(SecuritySmartHttpAuthenticationMethod.BEARER)) {
      return authenticateBearer(credential, trace);
    }
    throw unauthorized();
  }

  private AuthenticatedGitAccess authenticateBasic(
      String encodedCredentials, SecurityAuthenticationTrace trace)
      throws ServiceNotAuthorizedException, ServiceMayNotContinueException {
    byte[] decoded = null;
    char[] password = null;
    try {
      decoded = Base64.getDecoder().decode(encodedCredentials);
      if (decoded.length > MAX_BASIC_CREDENTIAL_BYTES) {
        return rejectMalformedBasic(trace);
      }
      int separator = indexOf(decoded, (byte) ':');
      if (separator < 0) {
        return rejectMalformedBasic(trace);
      }
      String loginName = decodeUtf8String(decoded, 0, separator);
      password =
          decodeUtf8Chars(decoded, separator + 1, decoded.length - separator - 1);
      char[] suppliedPassword = password;
      return invoke(
          () -> credentialService.authenticatePassword(loginName, suppliedPassword, trace));
    } catch (IllegalArgumentException | CharacterCodingException malformed) {
      return rejectMalformedBasic(trace);
    } finally {
      if (password != null) {
        Arrays.fill(password, '\0');
      }
      if (decoded != null) {
        Arrays.fill(decoded, (byte) 0);
      }
    }
  }

  private AuthenticatedGitAccess rejectMalformedBasic(SecurityAuthenticationTrace trace)
      throws ServiceNotAuthorizedException, ServiceMayNotContinueException {
    char[] invalid = new char[0];
    try {
      invoke(() -> credentialService.authenticatePassword("", invalid, trace));
      throw unauthorized();
    } finally {
      Arrays.fill(invalid, '\0');
    }
  }

  private AuthenticatedGitAccess authenticateBearer(
      String tokenValue, SecurityAuthenticationTrace trace)
      throws ServiceNotAuthorizedException, ServiceMayNotContinueException {
    return invoke(() -> credentialService.authenticateAccessToken(tokenValue, trace));
  }

  private AuthenticatedGitAccess invoke(AuthenticationAttempt attempt)
      throws ServiceNotAuthorizedException, ServiceMayNotContinueException {
    try {
      AuthenticatedGitAccess access = attempt.authenticate();
      if (access == null) {
        throw unavailable(new IllegalStateException("credential service returned no access context"));
      }
      return access;
    } catch (SecurityIdentityAuditPersistenceException unavailable) {
      throw unavailable(unavailable);
    } catch (SecurityAuthenticationException denied) {
      if (denied.reason() == SecurityAuthenticationReason.AUTHENTICATION_FAILURE) {
        throw unavailable(denied);
      }
      throw unauthorized(denied);
    } catch (ServiceMayNotContinueException | ServiceNotAuthorizedException mapped) {
      throw mapped;
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
  }

  private static String singleAuthorizationHeader(HttpServletRequest request)
      throws ServiceNotAuthorizedException {
    Enumeration<String> headers = request.getHeaders("Authorization");
    if (headers == null || !headers.hasMoreElements()) {
      throw unauthorized();
    }
    String header = headers.nextElement();
    if (headers.hasMoreElements()
        || header == null
        || header.length() > MAX_AUTHORIZATION_HEADER_LENGTH
        || !header.equals(header.strip())) {
      throw unauthorized();
    }
    return header;
  }

  private static int indexOf(byte[] values, byte expected) {
    for (int index = 0; index < values.length; index++) {
      if (values[index] == expected) {
        return index;
      }
    }
    return -1;
  }

  private static String decodeUtf8String(byte[] values, int offset, int length)
      throws CharacterCodingException {
    return decoder().decode(ByteBuffer.wrap(values, offset, length)).toString();
  }

  private static char[] decodeUtf8Chars(byte[] values, int offset, int length)
      throws CharacterCodingException {
    CharsetDecoder decoder = decoder();
    char[] scratch = new char[Math.max(1, length)];
    try {
      CharBuffer output = CharBuffer.wrap(scratch);
      CoderResult result = decoder.decode(ByteBuffer.wrap(values, offset, length), output, true);
      if (result.isError()) {
        result.throwException();
      }
      result = decoder.flush(output);
      if (result.isError()) {
        result.throwException();
      }
      return Arrays.copyOf(scratch, output.position());
    } finally {
      Arrays.fill(scratch, '\0');
    }
  }

  private static CharsetDecoder decoder() {
    return StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
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

  private static ServiceNotAuthorizedException unauthorized() {
    return new ServiceNotAuthorizedException();
  }

  private static ServiceNotAuthorizedException unauthorized(Throwable cause) {
    return new ServiceNotAuthorizedException("Authentication required", cause);
  }

  private static ServiceMayNotContinueException unavailable(Throwable cause) {
    return new ServiceMayNotContinueException(
        "Authentication service unavailable",
        cause,
        HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
  }

  @FunctionalInterface
  private interface AuthenticationAttempt {
    AuthenticatedGitAccess authenticate();
  }
}
''',
)

write(
    "jgit-storage-hibernate-smart-http/src/main/java/io/github/carstenartur/jgit/storage/hibernate/smarthttp/SmartHttpAuthenticationChallengeFilter.java",
    '''/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.smarthttp;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.util.List;

/** Adds configured WWW-Authenticate challenges immediately before a 401 response is committed. */
public final class SmartHttpAuthenticationChallengeFilter implements Filter {

  private final List<String> challenges;

  private SmartHttpAuthenticationChallengeFilter(List<String> challenges) {
    this.challenges = List.copyOf(challenges);
  }

  /** Advertise UTF-8 Basic authentication for one bounded realm. */
  public static SmartHttpAuthenticationChallengeFilter basic(String realm) {
    String value = realm(realm);
    return new SmartHttpAuthenticationChallengeFilter(
        List.of("Basic realm=\"" + value + "\", charset=\"UTF-8\""));
  }

  /** Advertise Bearer authentication for one bounded realm. */
  public static SmartHttpAuthenticationChallengeFilter bearer(String realm) {
    String value = realm(realm);
    return new SmartHttpAuthenticationChallengeFilter(
        List.of("Bearer realm=\"" + value + "\""));
  }

  /** Advertise both UTF-8 Basic and Bearer authentication. */
  public static SmartHttpAuthenticationChallengeFilter basicAndBearer(String realm) {
    String value = realm(realm);
    return new SmartHttpAuthenticationChallengeFilter(
        List.of(
            "Basic realm=\"" + value + "\", charset=\"UTF-8\"",
            "Bearer realm=\"" + value + "\""));
  }

  @Override
  public void doFilter(
      ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (!(response instanceof HttpServletResponse httpResponse)) {
      throw new ServletException("Smart HTTP authentication requires an HTTP response");
    }
    chain.doFilter(request, new ChallengeResponse(httpResponse, challenges));
  }

  private static String realm(String value) {
    if (value == null || value.isBlank() || value.length() > 128) {
      throw new IllegalArgumentException("realm must contain 1 to 128 visible ASCII characters");
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character < 0x20 || character > 0x7e || character == '"' || character == '\\') {
        throw new IllegalArgumentException(
            "realm must contain visible ASCII without quote or backslash characters");
      }
    }
    return value;
  }

  private static final class ChallengeResponse extends HttpServletResponseWrapper {

    private final List<String> challenges;
    private boolean added;

    ChallengeResponse(HttpServletResponse response, List<String> challenges) {
      super(response);
      this.challenges = challenges;
    }

    @Override
    public void sendError(int status) throws IOException {
      addChallenges(status);
      super.sendError(status);
    }

    @Override
    public void sendError(int status, String message) throws IOException {
      addChallenges(status);
      super.sendError(status, message);
    }

    @Override
    public void setStatus(int status) {
      addChallenges(status);
      super.setStatus(status);
    }

    @Override
    public void reset() {
      super.reset();
      added = false;
    }

    private void addChallenges(int status) {
      if (status != HttpServletResponse.SC_UNAUTHORIZED || added) {
        return;
      }
      for (String challenge : challenges) {
        super.addHeader("WWW-Authenticate", challenge);
      }
      added = true;
    }
  }
}
''',
)

write(
    "jgit-storage-hibernate-smart-http/src/test/java/io/github/carstenartur/jgit/storage/hibernate/smarthttp/security/SecuritySmartHttpAccessContextProviderTest.java",
    '''/*
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
  void authenticatesUtf8BasicAndBearerAndClearsDecodedPassword() {
    try (HibernateSessionFactoryProvider provider = provider("success")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistPrincipal(sessionFactory, "alice", "alice");
      CapturingPasswordHasher passwordHasher = new CapturingPasswordHasher();
      HibernateSecurityIdentityAuditService audit =
          new HibernateSecurityIdentityAuditService(sessionFactory);
      HibernateSecurityCredentialService credentials =
          credentials(sessionFactory, passwordHasher, audit);
      char[] configuredPassword = "sëcret".toCharArray();
      try {
        credentials.setPassword(
            SecurityManagementRequest.password(
                ADMIN, SecurityManagementOperation.SET_PASSWORD, "alice"),
            configuredPassword);
      } finally {
        Arrays.fill(configuredPassword, '\0');
      }
      IssuedAccessToken token =
          credentials.issueAccessToken(
              SecurityManagementRequest.issueToken(ADMIN, "alice"),
              Set.of(GitRepositoryPermission.READ),
              Instant.now().plus(Duration.ofHours(1)));

      SecuritySmartHttpAccessContextProvider authentication =
          authentication(credentials, "success");
      String basic =
          Base64.getEncoder()
              .encodeToString("alice:sëcret".getBytes(StandardCharsets.UTF_8));
      AuthenticatedGitAccess passwordAccess =
          authentication.require(request(true, "Basic " + basic));
      assertEquals("alice", passwordAccess.context().principalId());
      assertEquals(SecurityCredentialKind.PASSWORD, passwordAccess.credentialKind());
      assertTrue(
          passwordHasher.lastVerified().stream()
              .flatMapToInt(value -> new String(value).chars())
              .allMatch(character -> character == 0));

      AuthenticatedGitAccess tokenAccess =
          authentication.require(request(true, "Bearer " + token.tokenValue()));
      assertEquals("alice", tokenAccess.context().principalId());
      assertEquals(SecurityCredentialKind.ACCESS_TOKEN, tokenAccess.credentialKind());
    } catch (ServiceNotAuthorizedException | ServiceMayNotContinueException unexpected) {
      throw new AssertionError(unexpected);
    }
  }

  @Test
  void rejectsInsecureAmbiguousUnsupportedAndWrongCredentialsWithoutDetailLeak() {
    try (HibernateSessionFactoryProvider provider = provider("denied")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      persistPrincipal(sessionFactory, "alice", "alice");
      HibernateSecurityIdentityAuditService audit =
          new HibernateSecurityIdentityAuditService(sessionFactory);
      HibernateSecurityCredentialService credentials =
          credentials(sessionFactory, new CapturingPasswordHasher(), audit);
      credentials.setPassword(
          SecurityManagementRequest.password(
              ADMIN, SecurityManagementOperation.SET_PASSWORD, "alice"),
          "secret".toCharArray());
      SecuritySmartHttpAccessContextProvider authentication =
          authentication(credentials, "denied");

      assertThrows(
          ServiceNotAuthorizedException.class,
          () -> authentication.require(request(false, "Bearer token")));
      assertThrows(
          ServiceNotAuthorizedException.class,
          () -> authentication.require(request(true)));
      assertThrows(
          ServiceNotAuthorizedException.class,
          () -> authentication.require(request(true, "Bearer a", "Bearer b")));
      assertThrows(
          ServiceNotAuthorizedException.class,
          () -> authentication.require(request(true, "Digest value")));

      String wrong =
          Base64.getEncoder()
              .encodeToString("alice:wrong-secret".getBytes(StandardCharsets.UTF_8));
      ServiceNotAuthorizedException denied =
          assertThrows(
              ServiceNotAuthorizedException.class,
              () -> authentication.require(request(true, "Basic " + wrong)));
      assertFalse(String.valueOf(denied.getMessage()).contains("wrong-secret"));
      assertFalse(String.valueOf(denied.getMessage()).contains("INVALID_CREDENTIALS"));
    }
  }

  @Test
  void malformedBasicUsesDummyAuthenticationAndProducesBoundedAudit() {
    try (HibernateSessionFactoryProvider provider = provider("malformed")) {
      SessionFactory sessionFactory = provider.getSessionFactory();
      HibernateSecurityIdentityAuditService audit =
          new HibernateSecurityIdentityAuditService(sessionFactory);
      HibernateSecurityCredentialService credentials =
          credentials(sessionFactory, new CapturingPasswordHasher(), audit);
      SecuritySmartHttpAccessContextProvider authentication =
          authentication(credentials, "malformed");

      assertThrows(
          ServiceNotAuthorizedException.class,
          () -> authentication.require(request(true, "Basic !!!")));
      assertTrue(
          audit.findByCorrelationId("malformed", 10).stream()
              .anyMatch(
                  event ->
                      event.record().outcome() == SecurityAuditOutcome.DENIED
                          && SecurityAuthenticationReason.INVALID_CREDENTIALS.name()
                              .equals(event.record().reasonCode())));
    }
  }

  @Test
  void authenticationStoreFailureRemainsServerError() {
    HibernateSessionFactoryProvider provider = provider("failure");
    SessionFactory sessionFactory = provider.getSessionFactory();
    persistPrincipal(sessionFactory, "alice", "alice");
    HibernateSecurityCredentialService credentials =
        credentials(
            sessionFactory,
            new CapturingPasswordHasher(),
            new HibernateSecurityIdentityAuditService(sessionFactory));
    IssuedAccessToken token =
        credentials.issueAccessToken(
            SecurityManagementRequest.issueToken(ADMIN, "alice"),
            Set.of(GitRepositoryPermission.READ),
            Instant.now().plus(Duration.ofHours(1)));
    SecuritySmartHttpAccessContextProvider authentication =
        authentication(credentials, "failure");
    sessionFactory.close();

    ServiceMayNotContinueException failure =
        assertThrows(
            ServiceMayNotContinueException.class,
            () ->
                authentication.require(
                    request(true, "Bearer " + token.tokenValue())));
    assertEquals(500, failure.getStatusCode());
    assertFalse(String.valueOf(failure.getMessage()).contains(token.tokenValue()));
    provider.close();
  }

  private static SecuritySmartHttpAccessContextProvider authentication(
      HibernateSecurityCredentialService credentials, String correlationId) {
    return new SecuritySmartHttpAccessContextProvider(
        credentials,
        Set.of(
            SecuritySmartHttpAuthenticationMethod.BASIC,
            SecuritySmartHttpAuthenticationMethod.BEARER),
        request ->
            SecurityAuthenticationTrace.withoutRemoteAddress(
                "session-" + correlationId, correlationId));
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

  private static HttpServletRequest request(boolean secure, String... authorizationHeaders) {
    List<String> headers = List.of(authorizationHeaders);
    return (HttpServletRequest)
        Proxy.newProxyInstance(
            SecuritySmartHttpAccessContextProviderTest.class.getClassLoader(),
            new Class<?>[] {HttpServletRequest.class},
            (proxy, method, arguments) -> {
              return switch (method.getName()) {
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
                default -> defaultValue(method.getReturnType());
              };
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

    List<char[]> lastVerified() {
      return List.of(lastVerified.get());
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
''',
)

write(
    "jgit-storage-hibernate-smart-http/src/test/java/io/github/carstenartur/jgit/storage/hibernate/smarthttp/SmartHttpAuthenticationChallengeFilterTest.java",
    '''/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.smarthttp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SmartHttpAuthenticationChallengeFilterTest {

  @Test
  void addsBasicAndBearerChallengesBeforeUnauthorizedError() throws Exception {
    List<String> challenges = new ArrayList<>();
    AtomicInteger status = new AtomicInteger();
    HttpServletResponse response = response(challenges, status);

    SmartHttpAuthenticationChallengeFilter.basicAndBearer("Git")
        .doFilter(
            request(),
            response,
            (servletRequest, servletResponse) ->
                ((HttpServletResponse) servletResponse)
                    .sendError(HttpServletResponse.SC_UNAUTHORIZED, "denied"));

    assertEquals(HttpServletResponse.SC_UNAUTHORIZED, status.get());
    assertEquals(
        List.of(
            "Basic realm=\"Git\", charset=\"UTF-8\"",
            "Bearer realm=\"Git\""),
        challenges);
  }

  @Test
  void doesNotAddChallengesToNonUnauthorizedResponsesAndValidatesRealm() throws Exception {
    List<String> challenges = new ArrayList<>();
    AtomicInteger status = new AtomicInteger();
    SmartHttpAuthenticationChallengeFilter.basic("Git")
        .doFilter(
            request(),
            response(challenges, status),
            (servletRequest, servletResponse) ->
                ((HttpServletResponse) servletResponse)
                    .sendError(HttpServletResponse.SC_FORBIDDEN));

    assertTrue(challenges.isEmpty());
    assertEquals(HttpServletResponse.SC_FORBIDDEN, status.get());
    assertThrows(
        IllegalArgumentException.class,
        () -> SmartHttpAuthenticationChallengeFilter.basic("bad\"realm"));
  }

  private static ServletRequest request() {
    return (ServletRequest)
        Proxy.newProxyInstance(
            SmartHttpAuthenticationChallengeFilterTest.class.getClassLoader(),
            new Class<?>[] {ServletRequest.class},
            (proxy, method, arguments) -> defaultValue(method.getReturnType()));
  }

  private static HttpServletResponse response(
      List<String> challenges, AtomicInteger status) {
    return (HttpServletResponse)
        Proxy.newProxyInstance(
            SmartHttpAuthenticationChallengeFilterTest.class.getClassLoader(),
            new Class<?>[] {HttpServletResponse.class},
            (proxy, method, arguments) -> {
              switch (method.getName()) {
                case "addHeader" -> {
                  if ("WWW-Authenticate".equals(arguments[0])) {
                    challenges.add((String) arguments[1]);
                  }
                  return null;
                }
                case "sendError", "setStatus" -> {
                  status.set((Integer) arguments[0]);
                  return null;
                }
                case "getStatus" -> {
                  return status.get();
                }
                case "reset" -> {
                  challenges.clear();
                  status.set(0);
                  return null;
                }
                default -> {
                  return defaultValue(method.getReturnType());
                }
              }
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
}
''',
)

replace_once(
    "jgit-storage-hibernate-smart-http/README.md",
    """The access-context provider may use the Security module's local password/access-token service, an
OIDC or LDAP adapter, or an already authenticated application session. It must return one immutable
context accepted by the configured `SecuredHibernateRepositoryFactory`; missing authentication must
raise JGit's `ServiceNotAuthorizedException`.
""",
    """The access-context provider may use the Security module's local password/access-token service, an
OIDC or LDAP adapter, or an already authenticated application session. It must return one immutable
context accepted by the configured `SecuredHibernateRepositoryFactory`; missing authentication must
raise JGit's `ServiceNotAuthorizedException`.

### Security Basic and Bearer adapter

The module has an **optional** compile-time integration with `jgit-storage-hibernate-security`. Server
applications that use local credentials or access tokens must therefore declare both artifacts. The
optional edge keeps Smart HTTP usable with OIDC, LDAP or an application session without selecting the
Security schema.

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
emits a 401, allowing Git clients to discover UTF-8 Basic or Bearer authentication without advertising
credentials on successful responses. The credential adapter accepts exactly one Authorization header,
requires trusted TLS state, clears decoded Basic password arrays and maps every credential denial to a
generic 401. Authentication or required-audit infrastructure failures remain HTTP 500 responses.

Behind a reverse proxy, configure the servlet container so `request.isSecure()` reflects the trusted
original connection, or inject a predicate backed by trusted proxy integration. Never inspect a
caller-controlled `X-Forwarded-Proto` header directly.
""",
)

replace_once(
    "docs/operations/secured-smart-http.md",
    """The module depends on Core and JGit HTTP support. It deliberately does not depend on the Security
module: an application can bind the same protocol adapter to database credentials, OIDC, LDAP or an
existing authenticated session. Core-only consumers that do not select Smart HTTP retain their
existing dependency surface.
""",
    """The module depends on Core and JGit HTTP support. Its Security integration is an optional Maven
edge: applications using local passwords or one-way access tokens declare Security explicitly, while
OIDC, LDAP and existing-session deployments can use the generic provider without selecting Security.
Core-only consumers that do not select Smart HTTP retain their existing dependency surface, and the
Security module itself remains free of Servlet and JGit HTTP types.
""",
)
replace_once(
    "docs/operations/secured-smart-http.md",
    """A bearer-token adapter around the Security credential service has this shape:

```java
SmartHttpAccessContextProvider<AuthenticatedGitAccess> authentication =
    request -> {
      String token = applicationBearerTokenParser.requireToken(request);
      try {
        return credentialService.authenticateAccessToken(
            token, applicationAuthenticationTrace(request));
      } catch (SecurityAuthenticationException denied) {
        throw new ServiceNotAuthorizedException();
      } catch (HibernateStorageException unavailable) {
        throw new ServiceMayNotContinueException(
            "Authentication service unavailable", unavailable, 500);
      }
    };
```

The adapter must not place passwords, token plaintext or Authorization headers in exception messages,
logs, audit attributes or correlation metadata. Basic authentication is TLS-only. Prefer short-lived
or revocable bearer credentials for unattended Git clients.
""",
    """For the Security credential service, use the reusable strict adapter instead of parsing headers in
each application:

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

GitServlet servlet = SecuredSmartHttp.servlet(repositories, authentication);
```

The adapter requires exactly one Authorization header and a trusted secure transport. Basic payloads
are decoded as UTF-8, malformed Basic input executes the credential service's dummy verifier path, and
decoded password arrays are cleared after the authentication call. Bearer values are passed to the
one-way access-token service. Credential denials are always generic 401 results; an authentication or
required-audit infrastructure failure remains an HTTP 500.

Most Git clients discover Basic authentication only after a standards-compliant challenge. Register
this filter before the Git servlet:

```java
SmartHttpAuthenticationChallengeFilter challenge =
    SmartHttpAuthenticationChallengeFilter.basicAndBearer("Git");
```

The filter intercepts only 401 status/error emission and adds the configured `WWW-Authenticate`
headers before the response is committed. It does not advertise challenges on successful responses.
The adapter and filter never place passwords, token plaintext or Authorization headers in exceptions,
logs, audit attributes or correlation metadata. Prefer short-lived or revocable bearer credentials for
unattended Git clients.
""",
)
replace_once(
    "docs/operations/secured-smart-http.md",
    "- reusable, non-secret Basic/Bearer header adapters for the Security credential service;\n",
    "- a database-backed coarse write-admission implementation with bounded query cost;\n",
)
# The replacement above duplicates the following bullet in the old list; collapse it.
replace_once(
    "docs/operations/secured-smart-http.md",
    """- a database-backed coarse write-admission implementation with bounded query cost;
- a database-backed coarse write-admission implementation with bounded query cost;
""",
    """- a database-backed coarse write-admission implementation with bounded query cost;
""",
)
