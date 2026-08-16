/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.security;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Salted PBKDF2-HMAC-SHA-256 password hasher using only JDK cryptography providers. */
public final class Pbkdf2PasswordHasher implements PasswordHasher {

  /** Stable persisted algorithm identifier. */
  public static final String ALGORITHM = "PBKDF2-HMAC-SHA256";

  /** Current encoded-verifier format version. */
  public static final int VERSION = 1;

  /** Default work factor for newly created verifiers. */
  public static final int DEFAULT_ITERATIONS = 600_000;

  private static final String JCA_ALGORITHM = "PBKDF2WithHmacSHA256";
  private static final int MIN_ITERATIONS = 100_000;
  private static final int MAX_ITERATIONS = 10_000_000;
  private static final int DEFAULT_SALT_BYTES = 16;
  private static final int DEFAULT_HASH_BYTES = 32;
  private static final int MIN_SALT_BYTES = 16;
  private static final int MAX_SALT_BYTES = 64;
  private static final int MIN_HASH_BYTES = 32;
  private static final int MAX_HASH_BYTES = 64;
  private static final int MAX_PASSWORD_CHARACTERS = 1024;

  private final SecureRandom secureRandom;
  private final int iterations;
  private final int saltBytes;
  private final int hashBytes;

  /** Create a hasher using the default work factor and output sizes. */
  public Pbkdf2PasswordHasher() {
    this(new SecureRandom(), DEFAULT_ITERATIONS, DEFAULT_SALT_BYTES, DEFAULT_HASH_BYTES);
  }

  /**
   * Create a configurable hasher.
   *
   * @param secureRandom cryptographically secure salt source
   * @param iterations PBKDF2 iteration count, at least 100,000
   * @param saltBytes random salt bytes, between 16 and 64
   * @param hashBytes derived verifier bytes, between 32 and 64
   */
  public Pbkdf2PasswordHasher(
      SecureRandom secureRandom, int iterations, int saltBytes, int hashBytes) {
    this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    this.iterations = bounded("iterations", iterations, MIN_ITERATIONS, MAX_ITERATIONS);
    this.saltBytes = bounded("saltBytes", saltBytes, MIN_SALT_BYTES, MAX_SALT_BYTES);
    this.hashBytes = bounded("hashBytes", hashBytes, MIN_HASH_BYTES, MAX_HASH_BYTES);
  }

  @Override
  public PasswordHash hash(char[] password) {
    char[] copy = validatedPasswordCopy(password);
    byte[] salt = new byte[saltBytes];
    secureRandom.nextBytes(salt);
    byte[] derived = null;
    try {
      derived = derive(copy, salt, iterations, hashBytes);
      String encoded =
          iterations
              + "."
              + Base64.getUrlEncoder().withoutPadding().encodeToString(salt)
              + "."
              + Base64.getUrlEncoder().withoutPadding().encodeToString(derived);
      return new PasswordHash(ALGORITHM, VERSION, encoded);
    } finally {
      Arrays.fill(copy, '\0');
      Arrays.fill(salt, (byte) 0);
      if (derived != null) {
        Arrays.fill(derived, (byte) 0);
      }
    }
  }

  @Override
  public boolean verify(char[] password, PasswordHash expected) {
    Objects.requireNonNull(expected, "expected");
    char[] copy = validatedPasswordCopy(password);
    ParsedVerifier parsed = parse(expected);
    if (parsed == null) {
      Arrays.fill(copy, '\0');
      return false;
    }
    byte[] actual = null;
    try {
      actual = derive(copy, parsed.salt(), parsed.iterations(), parsed.hash().length);
      return MessageDigest.isEqual(parsed.hash(), actual);
    } finally {
      Arrays.fill(copy, '\0');
      parsed.clear();
      if (actual != null) {
        Arrays.fill(actual, (byte) 0);
      }
    }
  }

  @Override
  public boolean needsRehash(PasswordHash existing) {
    Objects.requireNonNull(existing, "existing");
    ParsedVerifier parsed = parse(existing);
    if (parsed == null) {
      return true;
    }
    try {
      return parsed.iterations() != iterations
          || parsed.salt().length != saltBytes
          || parsed.hash().length != hashBytes;
    } finally {
      parsed.clear();
    }
  }

  private static ParsedVerifier parse(PasswordHash expected) {
    if (!ALGORITHM.equals(expected.algorithm()) || expected.version() != VERSION) {
      return null;
    }
    String[] parts = expected.encodedHash().split("\\.", -1);
    if (parts.length != 3) {
      return null;
    }

    byte[] salt = null;
    byte[] hash = null;
    try {
      int encodedIterations = Integer.parseInt(parts[0]);
      if (encodedIterations < MIN_ITERATIONS || encodedIterations > MAX_ITERATIONS) {
        return null;
      }
      salt = Base64.getUrlDecoder().decode(parts[1]);
      hash = Base64.getUrlDecoder().decode(parts[2]);
      if (salt.length < MIN_SALT_BYTES
          || salt.length > MAX_SALT_BYTES
          || hash.length < MIN_HASH_BYTES
          || hash.length > MAX_HASH_BYTES) {
        return null;
      }
      ParsedVerifier parsed = new ParsedVerifier(encodedIterations, salt, hash);
      salt = null;
      hash = null;
      return parsed;
    } catch (IllegalArgumentException malformed) {
      return null;
    } finally {
      if (salt != null) {
        Arrays.fill(salt, (byte) 0);
      }
      if (hash != null) {
        Arrays.fill(hash, (byte) 0);
      }
    }
  }

  private static byte[] derive(char[] password, byte[] salt, int iterations, int bytes) {
    PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, Math.multiplyExact(bytes, 8));
    try {
      return SecretKeyFactory.getInstance(JCA_ALGORITHM).generateSecret(spec).getEncoded();
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException(JCA_ALGORITHM + " is unavailable", exception);
    } finally {
      spec.clearPassword();
    }
  }

  private static char[] validatedPasswordCopy(char[] password) {
    Objects.requireNonNull(password, "password");
    if (password.length == 0) {
      throw new IllegalArgumentException("password must not be empty");
    }
    if (password.length > MAX_PASSWORD_CHARACTERS) {
      throw new IllegalArgumentException(
          "password must contain at most " + MAX_PASSWORD_CHARACTERS + " characters");
    }
    return password.clone();
  }

  private static int bounded(String name, int value, int minimum, int maximum) {
    if (value < minimum || value > maximum) {
      throw new IllegalArgumentException(
          name + " must be between " + minimum + " and " + maximum);
    }
    return value;
  }

  private record ParsedVerifier(int iterations, byte[] salt, byte[] hash) {
    private void clear() {
      Arrays.fill(salt, (byte) 0);
      Arrays.fill(hash, (byte) 0);
    }
  }
}
