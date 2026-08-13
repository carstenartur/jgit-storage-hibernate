/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import java.util.Objects;

/**
 * One-time access-token issuance result.
 *
 * <p>The plaintext token is never returned by later metadata queries and is deliberately redacted
 * from {@link #toString()}.
 */
public final class IssuedAccessToken {

  private final AccessTokenMetadata metadata;
  private final String tokenValue;

  /** Create an issuance result containing the only library-returned copy of the plaintext token. */
  public IssuedAccessToken(AccessTokenMetadata metadata, String tokenValue) {
    this.metadata = Objects.requireNonNull(metadata, "metadata");
    if (tokenValue == null || tokenValue.isBlank() || tokenValue.length() > 512) {
      throw new IllegalArgumentException("tokenValue must contain 1 to 512 characters");
    }
    this.tokenValue = tokenValue;
  }

  public AccessTokenMetadata metadata() {
    return metadata;
  }

  public String tokenValue() {
    return tokenValue;
  }

  @Override
  public String toString() {
    return "IssuedAccessToken[metadata=" + metadata + ", tokenValue=<redacted>]";
  }
}
