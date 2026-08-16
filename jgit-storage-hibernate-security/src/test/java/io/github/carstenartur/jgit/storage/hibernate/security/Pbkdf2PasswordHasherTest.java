/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class Pbkdf2PasswordHasherTest {

  private static final int TEST_ITERATIONS = 100_000;

  @Test
  void hashesVerifiesAndKeepsTheCallerPasswordUntouched() {
    Pbkdf2PasswordHasher hasher = hasher(TEST_ITERATIONS, 16, 32, (byte) 0x5a);
    char[] password = "correct horse battery staple".toCharArray();
    char[] original = password.clone();

    PasswordHash hash = hasher.hash(password);

    assertArrayEquals(original, password);
    assertTrue(hasher.verify(password, hash));
    assertFalse(hasher.verify("wrong password".toCharArray(), hash));
    assertFalse(hasher.needsRehash(hash));
    assertFalse(hash.toString().contains(hash.encodedHash()));
    assertTrue(hash.toString().contains("<redacted>"));
  }

  @Test
  void rejectsMalformedUnsupportedAndOutOfBoundsVerifiers() {
    Pbkdf2PasswordHasher hasher = hasher(TEST_ITERATIONS, 16, 32, (byte) 1);
    PasswordHash valid = hasher.hash("password".toCharArray());
    String[] parts = valid.encodedHash().split("\\.");
    String salt = parts[1];
    String derived = parts[2];

    PasswordHash[] invalid = {
      new PasswordHash("OTHER", 1, valid.encodedHash()),
      new PasswordHash(Pbkdf2PasswordHasher.ALGORITHM, 2, valid.encodedHash()),
      new PasswordHash(Pbkdf2PasswordHasher.ALGORITHM, 1, "only.two"),
      new PasswordHash(Pbkdf2PasswordHasher.ALGORITHM, 1, "not-a-number." + salt + "." + derived),
      new PasswordHash(Pbkdf2PasswordHasher.ALGORITHM, 1, "99999." + salt + "." + derived),
      new PasswordHash(Pbkdf2PasswordHasher.ALGORITHM, 1, "10000001." + salt + "." + derived),
      new PasswordHash(Pbkdf2PasswordHasher.ALGORITHM, 1, "100000.***." + derived),
      new PasswordHash(Pbkdf2PasswordHasher.ALGORITHM, 1, "100000." + salt + ".***"),
      new PasswordHash(
          Pbkdf2PasswordHasher.ALGORITHM,
          1,
          "100000."
              + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[15])
              + "."
              + derived),
      new PasswordHash(
          Pbkdf2PasswordHasher.ALGORITHM,
          1,
          "100000."
              + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[65])
              + "."
              + derived),
      new PasswordHash(
          Pbkdf2PasswordHasher.ALGORITHM,
          1,
          "100000."
              + salt
              + "."
              + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[31])),
      new PasswordHash(
          Pbkdf2PasswordHasher.ALGORITHM,
          1,
          "100000."
              + salt
              + "."
              + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[65]))
    };

    for (PasswordHash verifier : invalid) {
      assertFalse(hasher.verify("password".toCharArray(), verifier), verifier.toString());
      assertTrue(hasher.needsRehash(verifier), verifier.toString());
    }
  }

  @Test
  void needsRehashTracksWorkFactorSaltAndVerifierSize() {
    PasswordHash existing = hasher(TEST_ITERATIONS, 16, 32, (byte) 2).hash("password".toCharArray());

    assertTrue(hasher(TEST_ITERATIONS + 1, 16, 32, (byte) 2).needsRehash(existing));
    assertTrue(hasher(TEST_ITERATIONS, 17, 32, (byte) 2).needsRehash(existing));
    assertTrue(hasher(TEST_ITERATIONS, 16, 33, (byte) 2).needsRehash(existing));
  }

  @Test
  void validatesConfigurationAndPasswordBounds() {
    assertThrows(NullPointerException.class, () -> new Pbkdf2PasswordHasher(null, 100_000, 16, 32));
    assertThrows(IllegalArgumentException.class, () -> hasher(99_999, 16, 32, (byte) 0));
    assertThrows(IllegalArgumentException.class, () -> hasher(10_000_001, 16, 32, (byte) 0));
    assertThrows(IllegalArgumentException.class, () -> hasher(100_000, 15, 32, (byte) 0));
    assertThrows(IllegalArgumentException.class, () -> hasher(100_000, 65, 32, (byte) 0));
    assertThrows(IllegalArgumentException.class, () -> hasher(100_000, 16, 31, (byte) 0));
    assertThrows(IllegalArgumentException.class, () -> hasher(100_000, 16, 65, (byte) 0));

    Pbkdf2PasswordHasher hasher = hasher(TEST_ITERATIONS, 16, 32, (byte) 0);
    assertThrows(NullPointerException.class, () -> hasher.hash(null));
    assertThrows(IllegalArgumentException.class, () -> hasher.hash(new char[0]));
    assertThrows(IllegalArgumentException.class, () -> hasher.hash(new char[1025]));
    assertThrows(NullPointerException.class, () -> hasher.verify(null, hasher.hash("x".toCharArray())));
    assertThrows(NullPointerException.class, () -> hasher.verify("x".toCharArray(), null));
    assertThrows(NullPointerException.class, () -> hasher.needsRehash(null));
  }

  private static Pbkdf2PasswordHasher hasher(
      int iterations, int saltBytes, int hashBytes, byte saltValue) {
    return new Pbkdf2PasswordHasher(
        new SecureRandom() {
          @Override
          public void nextBytes(byte[] bytes) {
            Arrays.fill(bytes, saltValue);
          }
        },
        iterations,
        saltBytes,
        hashBytes);
  }
}
