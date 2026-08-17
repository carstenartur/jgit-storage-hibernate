/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Already authenticated external identity supplied by a trusted host application.
 *
 * <p>The host remains responsible for validating tokens, sessions, issuer trust, audience and
 * required claims. This value only binds the validated canonical issuer and subject to a stable
 * Security principal. Display data and attributes are never authorization evidence.
 */
public record ExternalPrincipalIdentity(
    String issuer, String subject, String displayName, Map<String, String> attributes) {

  private static final int MAX_ISSUER_LENGTH = 512;
  private static final int MAX_SUBJECT_LENGTH = 512;
  private static final int MAX_DISPLAY_NAME_LENGTH = 256;
  private static final int MAX_ATTRIBUTE_COUNT = 32;
  private static final int MAX_ATTRIBUTE_KEY_LENGTH = 128;
  private static final int MAX_ATTRIBUTE_VALUE_LENGTH = 1024;

  /** Creates a validated, deeply immutable external identity. */
  public ExternalPrincipalIdentity {
    issuer = canonicalIssuer(issuer);
    subject = required("subject", subject, MAX_SUBJECT_LENGTH);
    displayName = optional("displayName", displayName, MAX_DISPLAY_NAME_LENGTH);
    attributes = immutableAttributes(attributes);
  }

  /** Create an identity without provider-specific profile attributes. */
  public static ExternalPrincipalIdentity of(
      String issuer, String subject, String displayName) {
    return new ExternalPrincipalIdentity(issuer, subject, displayName, Map.of());
  }

  private static String canonicalIssuer(String value) {
    String issuer = required("issuer", value, MAX_ISSUER_LENGTH);
    URI parsed;
    try {
      parsed = URI.create(issuer);
    } catch (IllegalArgumentException invalid) {
      throw new IllegalArgumentException("issuer must be an absolute URI", invalid);
    }
    if (!parsed.isAbsolute() || parsed.getFragment() != null) {
      throw new IllegalArgumentException("issuer must be an absolute URI without a fragment");
    }
    return issuer;
  }

  private static Map<String, String> immutableAttributes(Map<String, String> values) {
    if (values == null) {
      throw new IllegalArgumentException("attributes must not be null");
    }
    if (values.size() > MAX_ATTRIBUTE_COUNT) {
      throw new IllegalArgumentException(
          "attributes must contain at most " + MAX_ATTRIBUTE_COUNT + " entries");
    }
    LinkedHashMap<String, String> result = new LinkedHashMap<>();
    values.forEach(
        (key, value) ->
            result.put(
                required("attribute key", key, MAX_ATTRIBUTE_KEY_LENGTH),
                required("attribute value", value, MAX_ATTRIBUTE_VALUE_LENGTH)));
    return Map.copyOf(result);
  }

  private static String required(String name, String value, int maximumLength) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (!value.equals(value.strip())) {
      throw new IllegalArgumentException(name + " must not contain surrounding whitespace");
    }
    if (value.length() > maximumLength) {
      throw new IllegalArgumentException(
          name + " must contain at most " + maximumLength + " characters");
    }
    if (value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(name + " must not contain control characters");
    }
    return value;
  }

  private static String optional(String name, String value, int maximumLength) {
    return value == null ? null : required(name, value, maximumLength);
  }
}
