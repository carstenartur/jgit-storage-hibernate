/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import java.util.Locale;
import java.util.Objects;

/** Bounded, non-secret tracing evidence supplied to credential authentication services. */
public record SecurityAuthenticationTrace(
    String sessionId, String correlationId, String remoteAddressHash) {

  private static final int MAX_CONTEXT_IDENTIFIER_LENGTH = 256;

  /** Creates a trace; a remote address must already be irreversibly hashed outside this value. */
  public SecurityAuthenticationTrace {
    sessionId = required("sessionId", sessionId, MAX_CONTEXT_IDENTIFIER_LENGTH);
    correlationId = required("correlationId", correlationId, MAX_CONTEXT_IDENTIFIER_LENGTH);
    if (remoteAddressHash != null) {
      String normalized = remoteAddressHash.toLowerCase(Locale.ROOT);
      if (!normalized.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException(
            "remoteAddressHash must be null or a 64-character hexadecimal SHA-256/HMAC value");
      }
      remoteAddressHash = normalized;
    }
  }

  /** Create a trace without retaining remote-address evidence. */
  public static SecurityAuthenticationTrace withoutRemoteAddress(
      String sessionId, String correlationId) {
    return new SecurityAuthenticationTrace(sessionId, correlationId, null);
  }

  private static String required(String name, String value, int maximumLength) {
    Objects.requireNonNull(value, name);
    if (value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (value.length() > maximumLength) {
      throw new IllegalArgumentException(
          name + " must contain at most " + maximumLength + " characters");
    }
    return value;
  }
}
