/*
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
    ChallengeResponse challengeResponse = new ChallengeResponse(httpResponse, challenges);
    chain.doFilter(request, challengeResponse);
    challengeResponse.complete();
  }

  private static String realm(String value) {
    if (value == null || value.isBlank() || value.length() > 128) {
      throw new IllegalArgumentException(
          "realm must contain 1 to 128 printable ASCII characters");
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character < 0x20 || character > 0x7e || character == '"' || character == '\\') {
        throw new IllegalArgumentException(
            "realm must contain printable ASCII without quote or backslash characters");
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
    public void flushBuffer() throws IOException {
      addChallenges(getStatus());
      super.flushBuffer();
    }

    @Override
    public void reset() {
      super.reset();
      added = false;
    }

    void complete() {
      if (!isCommitted()) {
        addChallenges(getStatus());
      }
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
