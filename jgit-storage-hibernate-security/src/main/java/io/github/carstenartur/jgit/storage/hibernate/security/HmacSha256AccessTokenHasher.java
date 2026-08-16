/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA-256 access-token hasher using an application-owned secret pepper. */
public final class HmacSha256AccessTokenHasher implements AccessTokenHasher {

  /** Stable persisted algorithm identifier. */
  public static final String ALGORITHM = "HMAC-SHA256";

  /** Current verifier format version. */
  public static final int VERSION = 1;

  private static final String JCA_ALGORITHM = "HmacSHA256";
  private static final int MAC_BYTES = 32;
  private static final int MINIMUM_KEY_BYTES = 32;
  private static final int MAXIMUM_TOKEN_CHARACTERS = 512;

  private final SecretKeySpec key;

  /**
   * Create a hasher with an application-owned random secret key.
   *
   * <p>The key is not persisted by this library. Deployments must keep it in a secret manager and
   * retain old keys through an application-provided multi-key {@link AccessTokenHasher} when key
   * rotation must preserve existing tokens.
   *
   * @param secretKey at least 32 random bytes
   */
  public HmacSha256AccessTokenHasher(byte[] secretKey) {
    Objects.requireNonNull(secretKey, "secretKey");
    if (secretKey.length < MINIMUM_KEY_BYTES) {
      throw new IllegalArgumentException(
          "secretKey must contain at least " + MINIMUM_KEY_BYTES + " bytes");
    }
    byte[] copy = secretKey.clone();
    try {
      key = new SecretKeySpec(copy, JCA_ALGORITHM);
    } finally {
      Arrays.fill(copy, (byte) 0);
    }
  }

  @Override
  public AccessTokenHash hash(String tokenValue) {
    String token = requiredToken(tokenValue);
    byte[] digest = mac(token);
    try {
      return new AccessTokenHash(
          ALGORITHM,
          VERSION,
          Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
    } finally {
      Arrays.fill(digest, (byte) 0);
    }
  }

  @Override
  public boolean verify(String tokenValue, AccessTokenHash expected) {
    Objects.requireNonNull(expected, "expected");
    if (!validToken(tokenValue)
        || !ALGORITHM.equals(expected.algorithm())
        || expected.version() != VERSION) {
      return false;
    }

    byte[] expectedBytes = null;
    byte[] actualBytes = null;
    try {
      expectedBytes = Base64.getUrlDecoder().decode(expected.encodedHash());
      if (expectedBytes.length != MAC_BYTES) {
        return false;
      }
      actualBytes = mac(tokenValue);
      return MessageDigest.isEqual(expectedBytes, actualBytes);
    } catch (IllegalArgumentException malformed) {
      return false;
    } finally {
      if (expectedBytes != null) {
        Arrays.fill(expectedBytes, (byte) 0);
      }
      if (actualBytes != null) {
        Arrays.fill(actualBytes, (byte) 0);
      }
    }
  }

  private byte[] mac(String tokenValue) {
    try {
      Mac mac = Mac.getInstance(JCA_ALGORITHM);
      mac.init(key);
      return mac.doFinal(tokenValue.getBytes(StandardCharsets.US_ASCII));
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(JCA_ALGORITHM + " is unavailable", exception);
    }
  }

  private static String requiredToken(String tokenValue) {
    if (tokenValue == null || tokenValue.isBlank() || tokenValue.length() > MAXIMUM_TOKEN_CHARACTERS) {
      throw new IllegalArgumentException(
          "tokenValue must contain 1 to " + MAXIMUM_TOKEN_CHARACTERS + " ASCII characters");
    }
    if (!ascii(tokenValue)) {
      throw new IllegalArgumentException("tokenValue must contain ASCII characters only");
    }
    return tokenValue;
  }

  private static boolean validToken(String tokenValue) {
    return tokenValue != null
        && !tokenValue.isBlank()
        && tokenValue.length() <= MAXIMUM_TOKEN_CHARACTERS
        && ascii(tokenValue);
  }

  private static boolean ascii(String value) {
    for (int index = 0; index < value.length(); index++) {
      if (value.charAt(index) > 0x7f) {
        return false;
      }
    }
    return true;
  }
}
