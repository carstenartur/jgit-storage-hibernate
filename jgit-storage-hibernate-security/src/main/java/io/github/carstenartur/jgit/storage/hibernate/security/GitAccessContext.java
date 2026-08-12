/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package io.github.carstenartur.jgit.storage.hibernate.security;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Immutable authenticated identity and tracing context passed explicitly to authorization services.
 *
 * <p>The stable authorization identity is {@link #principalId()}; login names, commit authors and
 * ambient process users are never authentication evidence.
 */
public record GitAccessContext(
    String principalId,
    Set<String> groupIds,
    String authenticationMethod,
    String sessionId,
    String correlationId,
    Map<String, String> attributes) {

  private static final int MAX_SECURITY_SUBJECT_ID_LENGTH = 128;
  private static final int MAX_CONTEXT_IDENTIFIER_LENGTH = 256;
  private static final int MAX_ATTRIBUTE_COUNT = 32;
  private static final int MAX_ATTRIBUTE_LENGTH = 1024;

  /** Creates a validated, deeply immutable context. */
  public GitAccessContext {
    principalId = required("principalId", principalId, MAX_SECURITY_SUBJECT_ID_LENGTH);
    authenticationMethod =
        required("authenticationMethod", authenticationMethod, MAX_CONTEXT_IDENTIFIER_LENGTH);
    sessionId = required("sessionId", sessionId, MAX_CONTEXT_IDENTIFIER_LENGTH);
    correlationId = required("correlationId", correlationId, MAX_CONTEXT_IDENTIFIER_LENGTH);
    groupIds = immutableGroups(groupIds);
    attributes = immutableAttributes(attributes);
  }

  private static Set<String> immutableGroups(Set<String> values) {
    if (values == null) {
      throw new IllegalArgumentException("groupIds must not be null");
    }
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (String value : values) {
      result.add(required("groupId", value, MAX_SECURITY_SUBJECT_ID_LENGTH));
    }
    return Set.copyOf(result);
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
                required("attribute key", key, MAX_CONTEXT_IDENTIFIER_LENGTH),
                required("attribute value", value, MAX_ATTRIBUTE_LENGTH)));
    return Map.copyOf(result);
  }

  private static String required(String name, String value, int maximumLength) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    if (value.length() > maximumLength) {
      throw new IllegalArgumentException(
          name + " must contain at most " + maximumLength + " characters");
    }
    return value;
  }
}
