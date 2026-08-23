/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.server;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Principal;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.web.filter.OncePerRequestFilter;

/** Fail-closed Basic authentication for the first standalone single-admin deployment mode. */
public final class ServerBasicAuthenticationFilter extends OncePerRequestFilter {

  private final JgitStorageServerProperties.Authentication authentication;

  public ServerBasicAuthenticationFilter(
      JgitStorageServerProperties.Authentication authentication) {
    this.authentication = authentication;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (authentication.isRequireSecureTransport() && !request.isSecure()) {
      response.sendError(HttpServletResponse.SC_FORBIDDEN, "Secure transport is required");
      return;
    }

    ServerPrincipal principal = authenticate(request.getHeader("Authorization"));
    if (principal == null) {
      response.setHeader("WWW-Authenticate", "Basic realm=\"Git\", charset=\"UTF-8\"");
      response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
      return;
    }

    HttpServletRequest authenticatedRequest =
        new HttpServletRequestWrapper(request) {
          @Override
          public Principal getUserPrincipal() {
            return principal;
          }

          @Override
          public String getRemoteUser() {
            return principal.getName();
          }

          @Override
          public boolean isUserInRole(String role) {
            return "ADMIN".equals(role);
          }
        };
    filterChain.doFilter(authenticatedRequest, response);
  }

  private ServerPrincipal authenticate(String authorization) {
    if (authorization == null || !authorization.startsWith("Basic ")) {
      return null;
    }
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(authorization.substring(6).trim());
    } catch (IllegalArgumentException malformed) {
      return null;
    }
    try {
      String value = new String(decoded, StandardCharsets.UTF_8);
      int separator = value.indexOf(':');
      if (separator <= 0) {
        return null;
      }
      String username = value.substring(0, separator);
      String password = value.substring(separator + 1);
      if (!constantTimeEquals(username, authentication.getUsername())
          || !constantTimeEquals(password, authentication.getPassword())) {
        return null;
      }
      return new ServerPrincipal(authentication.getUsername());
    } finally {
      Arrays.fill(decoded, (byte) 0);
    }
  }

  private static boolean constantTimeEquals(String actual, String expected) {
    if (expected == null) {
      return false;
    }
    byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
    byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
    try {
      return MessageDigest.isEqual(actualBytes, expectedBytes);
    } finally {
      Arrays.fill(actualBytes, (byte) 0);
      Arrays.fill(expectedBytes, (byte) 0);
    }
  }
}
