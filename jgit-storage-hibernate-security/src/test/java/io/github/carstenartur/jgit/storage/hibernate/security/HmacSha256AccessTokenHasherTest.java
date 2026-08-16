/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class HmacSha256AccessTokenHasherTest {

  @Test
  void hashesAndVerifiesOnlyBoundedAsciiTokenMaterial() {
    byte[] key = new byte[32];
    Arrays.fill(key, (byte) 0x5a);
    HmacSha256AccessTokenHasher hasher = new HmacSha256AccessTokenHasher(key);

    AccessTokenHash hash = hasher.hash("gst_example-token_0123456789");

    assertTrue(hasher.verify("gst_example-token_0123456789", hash));
    assertFalse(hasher.verify("gst_example-token_0123456788", hash));
    assertFalse(hasher.verify("gst_tökén", hash));
    assertFalse(hasher.verify(null, hash));
    assertFalse(hasher.verify(" ", hash));
    assertFalse(hasher.verify("x".repeat(513), hash));
    assertFalse(
        hasher.verify(
            "gst_example-token_0123456789",
            new AccessTokenHash("OTHER", hash.version(), hash.encodedHash())));
    assertFalse(
        hasher.verify(
            "gst_example-token_0123456789",
            new AccessTokenHash(hash.algorithm(), hash.version() + 1, hash.encodedHash())));

    assertThrows(IllegalArgumentException.class, () -> hasher.hash("gst_tökén"));
    assertThrows(IllegalArgumentException.class, () -> hasher.hash(null));
    assertThrows(IllegalArgumentException.class, () -> hasher.hash(" "));
    assertThrows(IllegalArgumentException.class, () -> hasher.hash("x".repeat(513)));
    assertThrows(NullPointerException.class, () -> hasher.verify("token", null));
    assertThrows(NullPointerException.class, () -> new HmacSha256AccessTokenHasher(null));
    assertThrows(IllegalArgumentException.class, () -> new HmacSha256AccessTokenHasher(new byte[31]));
  }

  @Test
  void rejectsMalformedOrWrongLengthStoredMacsBeforeComparison() {
    HmacSha256AccessTokenHasher hasher = new HmacSha256AccessTokenHasher(new byte[32]);
    String token = "gst_example-token";

    assertFalse(
        hasher.verify(
            token,
            new AccessTokenHash(
                HmacSha256AccessTokenHasher.ALGORITHM,
                HmacSha256AccessTokenHasher.VERSION,
                "not-base64***")));
    assertFalse(
        hasher.verify(
            token,
            new AccessTokenHash(
                HmacSha256AccessTokenHasher.ALGORITHM,
                HmacSha256AccessTokenHasher.VERSION,
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[31]))));
    assertFalse(
        hasher.verify(
            token,
            new AccessTokenHash(
                HmacSha256AccessTokenHasher.ALGORITHM,
                HmacSha256AccessTokenHasher.VERSION,
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[33]))));
  }
}
