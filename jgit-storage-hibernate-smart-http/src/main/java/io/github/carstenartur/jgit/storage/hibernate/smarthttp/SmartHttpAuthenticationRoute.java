/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.smarthttp;

import java.util.Objects;
import java.util.Optional;

/**
 * One immutable, non-overlapping authorization-header route.
 *
 * <p>Bearer prefix routes are selected before an optional explicitly configured external bearer
 * catch-all. A prefix identifies only the credential namespace; it must never contain token-specific
 * lookup material or a secret.
 *
 * @param <C> immutable authenticated access-context type
 */
public final class SmartHttpAuthenticationRoute<C> {

  private static final int MAX_HANDLER_ID_LENGTH = 64;
  private static final int MAX_BEARER_PREFIX_LENGTH = 64;

  private final SmartHttpAuthenticationKind kind;
  private final AuthorizationScheme authorizationScheme;
  private final String handlerId;
  private final String bearerPrefix;
  private final SmartHttpAuthenticationHandler<C> handler;

  private SmartHttpAuthenticationRoute(
      SmartHttpAuthenticationKind kind,
      AuthorizationScheme authorizationScheme,
      String handlerId,
      String bearerPrefix,
      SmartHttpAuthenticationHandler<C> handler) {
    this.kind = Objects.requireNonNull(kind, "kind");
    this.authorizationScheme =
        Objects.requireNonNull(authorizationScheme, "authorizationScheme");
    this.handlerId = boundedIdentifier("handlerId", handlerId, MAX_HANDLER_ID_LENGTH);
    this.bearerPrefix = bearerPrefix;
    this.handler = Objects.requireNonNull(handler, "handler");
  }

  /** Create the only permitted local Basic route. */
  public static <C> SmartHttpAuthenticationRoute<C> securityLocalBasic(
      String handlerId, SmartHttpAuthenticationHandler<C> handler) {
    return new SmartHttpAuthenticationRoute<>(
        SmartHttpAuthenticationKind.SECURITY_LOCAL_BASIC,
        AuthorizationScheme.BASIC,
        handlerId,
        null,
        handler);
  }

  /** Create the explicit external bearer catch-all used after all prefix routes. */
  public static <C> SmartHttpAuthenticationRoute<C> externalBearer(
      String handlerId, SmartHttpAuthenticationHandler<C> handler) {
    return new SmartHttpAuthenticationRoute<>(
        SmartHttpAuthenticationKind.EXTERNAL_BEARER,
        AuthorizationScheme.BEARER,
        handlerId,
        null,
        handler);
  }

  /** Create a prefixed local Security access-token route. */
  public static <C> SmartHttpAuthenticationRoute<C> securityAccessToken(
      String handlerId, String bearerPrefix, SmartHttpAuthenticationHandler<C> handler) {
    return new SmartHttpAuthenticationRoute<>(
        SmartHttpAuthenticationKind.SECURITY_ACCESS_TOKEN,
        AuthorizationScheme.BEARER,
        handlerId,
        bearerPrefix(bearerPrefix),
        handler);
  }

  /** Create a prefixed service-credential route. */
  public static <C> SmartHttpAuthenticationRoute<C> serviceBearer(
      String handlerId, String bearerPrefix, SmartHttpAuthenticationHandler<C> handler) {
    return new SmartHttpAuthenticationRoute<>(
        SmartHttpAuthenticationKind.SERVICE,
        AuthorizationScheme.BEARER,
        handlerId,
        bearerPrefix(bearerPrefix),
        handler);
  }

  /** Return the bounded non-secret authentication classification. */
  public SmartHttpAuthenticationKind kind() {
    return kind;
  }

  /** Return the bounded non-secret handler identifier suitable for request evidence. */
  public String handlerId() {
    return handlerId;
  }

  /** Return the reserved bearer namespace, or empty for Basic and the external catch-all. */
  public Optional<String> bearerPrefix() {
    return Optional.ofNullable(bearerPrefix);
  }

  AuthorizationScheme authorizationScheme() {
    return authorizationScheme;
  }

  String rawBearerPrefix() {
    return bearerPrefix;
  }

  SmartHttpAuthenticationHandler<C> handler() {
    return handler;
  }

  @Override
  public String toString() {
    return "SmartHttpAuthenticationRoute[kind="
        + kind
        + ", authorizationScheme="
        + authorizationScheme
        + ", handlerId="
        + handlerId
        + ", bearerPrefix="
        + (bearerPrefix == null ? "<none>" : bearerPrefix)
        + "]";
  }

  private static String bearerPrefix(String value) {
    String prefix =
        boundedIdentifier("bearerPrefix", value, MAX_BEARER_PREFIX_LENGTH);
    for (int index = 0; index < prefix.length(); index++) {
      char character = prefix.charAt(index);
      if (!isBearerCharacter(character)) {
        throw new IllegalArgumentException(
            "bearerPrefix must contain only RFC 6750 bearer-token characters");
      }
    }
    return prefix;
  }

  private static String boundedIdentifier(String name, String value, int maximumLength) {
    Objects.requireNonNull(value, name);
    if (value.isBlank() || value.length() > maximumLength) {
      throw new IllegalArgumentException(
          name + " must contain 1 to " + maximumLength + " non-blank characters");
    }
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character < 0x21
          || character > 0x7e
          || Character.isWhitespace(character)
          || Character.isISOControl(character)) {
        throw new IllegalArgumentException(name + " must contain visible non-whitespace ASCII");
      }
    }
    return value;
  }

  private static boolean isBearerCharacter(char character) {
    return (character >= 'A' && character <= 'Z')
        || (character >= 'a' && character <= 'z')
        || (character >= '0' && character <= '9')
        || character == '-'
        || character == '.'
        || character == '_'
        || character == '~'
        || character == '+'
        || character == '/'
        || character == '=';
  }

  enum AuthorizationScheme {
    BASIC,
    BEARER
  }
}
