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

/**
 * Stable public namespace contract for Security access tokens.
 *
 * <p>The historical {@code jsh_} format is frozen as token format version 1. Its short namespace is
 * safe for credential routing but is not a token-specific lookup prefix and conveys no authority.
 * Future incompatible formats must use a different, non-overlapping bearer prefix rather than being
 * guessed from token contents or tried against multiple validators.
 */
public final class SecurityAccessTokenNamespace {

  /** Current persisted and issued token-format version. */
  public static final int CURRENT_VERSION = 1;

  /** Reserved bearer namespace for version-1 local access tokens. */
  public static final String VERSION_1_BEARER_PREFIX = "jsh_";

  private static final Pattern VERSION_1_PATTERN =
      Pattern.compile("^jsh_[A-Za-z0-9_-]{16}\\.[A-Za-z0-9_-]{43}$");

  private SecurityAccessTokenNamespace() {}

  /** Return every currently recognized, collision-checked local bearer namespace. */
  public static Set<String> recognizedBearerPrefixes() {
    return Set.of(VERSION_1_BEARER_PREFIX);
  }

  /** Return whether a complete value has the exact version-1 token syntax. */
  public static boolean isVersion1Token(String tokenValue) {
    return tokenValue != null && VERSION_1_PATTERN.matcher(tokenValue).matches();
  }
}
