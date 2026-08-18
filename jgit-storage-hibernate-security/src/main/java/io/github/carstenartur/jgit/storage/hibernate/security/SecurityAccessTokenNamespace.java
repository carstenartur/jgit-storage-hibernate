/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import java.util.Set;
import java.util.regex.Pattern;

/** Stable routing namespaces for opaque Security access tokens. */
public final class SecurityAccessTokenNamespace {

  /** Current token-format version issued by the credential service. */
  public static final int CURRENT_VERSION = 1;

  /** Explicit version-1 Bearer namespace used by newly issued local tokens. */
  public static final String VERSION_1_BEARER_PREFIX = "jsh1_";

  /** Unversioned namespace used by tokens issued before explicit format versioning. */
  public static final String LEGACY_BEARER_PREFIX = "jsh_";

  private static final Pattern VERSION_1_PATTERN =
      Pattern.compile("^jsh1_[A-Za-z0-9_-]{16}\\.[A-Za-z0-9_-]{43}$");
  private static final Pattern LEGACY_PATTERN =
      Pattern.compile("^jsh_[A-Za-z0-9_-]{16}\\.[A-Za-z0-9_-]{43}$");

  private SecurityAccessTokenNamespace() {}

  /** Return all local Bearer prefixes that must route exclusively to the Security verifier. */
  public static Set<String> bearerPrefixes() {
    return Set.of(VERSION_1_BEARER_PREFIX, LEGACY_BEARER_PREFIX);
  }

  /** Return whether the value is a syntactically valid current version-1 token. */
  public static boolean isVersion1Token(String tokenValue) {
    return tokenValue != null && VERSION_1_PATTERN.matcher(tokenValue).matches();
  }

  /** Return whether the value is a syntactically valid pre-versioning token. */
  public static boolean isLegacyToken(String tokenValue) {
    return tokenValue != null && LEGACY_PATTERN.matcher(tokenValue).matches();
  }

  /** Return whether the value belongs to any accepted local Security token format. */
  public static boolean isRecognizedToken(String tokenValue) {
    return isVersion1Token(tokenValue) || isLegacyToken(tokenValue);
  }
}
