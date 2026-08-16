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
 * service, and every client-facing rejection is generic. Credential or required-audit
 * infrastructure failures remain HTTP 500 errors instead of being mislabeled as invalid
 * credentials.
 */
public final class SecuritySmartHttpAccessContextProvider
    implements SmartHttpAccessContextProvider<AuthenticatedGitAccess> {

  private static final int MAX_AUTHORIZATION_HEADER_LENGTH = 8192;
  private static final int MAX_BASIC_CREDENTIAL_BYTES = 4096;

  private final HibernateSecurityCredentialService credentialService;
  private final Set<SecuritySmartHttpAuthenticationMethod> methods;
  private final SecuritySmartHttpTraceProvider traceProvider;
  private final Predicate<HttpServletRequest> secureTransport;

  /** Accept Basic and Bearer credentials when {@link HttpServletRequest#isSecure()} is true. */
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
   * <p>The predicate is intended for containers whose trusted reverse-proxy integration already
   * establishes the original HTTPS state. It must not trust a caller-controlled forwarding header
   * directly.
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
    if (!isSecure(current)) {
      throw unauthorized();
    }

    String header;
    try {
      header = singleAuthorizationHeader(current);
    } catch (ServiceNotAuthorizedException denied) {
      throw denied;
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }

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

  private boolean isSecure(HttpServletRequest request) throws ServiceMayNotContinueException {
    try {
      return secureTransport.test(request);
    } catch (RuntimeException failure) {
      throw unavailable(failure);
    }
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
      password = decodeUtf8Chars(decoded, separator + 1, decoded.length - separator - 1);
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
      throw unavailable(
          new IllegalStateException("credential service accepted malformed Basic credentials"));
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
    } catch (SecurityIdentityAuditPersistenceException auditFailure) {
      throw unavailable(auditFailure);
    } catch (SecurityAuthenticationException denied) {
      if (denied.reason() == SecurityAuthenticationReason.AUTHENTICATION_FAILURE) {
        throw unavailable(denied);
      }
      // The credential service already persisted the bounded reason. Do not copy it into a
      // protocol exception that a container could expose or log together with request metadata.
      throw unauthorized();
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
