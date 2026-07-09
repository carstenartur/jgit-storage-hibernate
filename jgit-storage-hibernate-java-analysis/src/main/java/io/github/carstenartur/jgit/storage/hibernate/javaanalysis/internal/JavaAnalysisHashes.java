/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;

/** Stable SHA-256 hashing helpers for analysis context metadata. */
public final class JavaAnalysisHashes {

  private JavaAnalysisHashes() {}

  public static String hash(Collection<String> values) {
    StringJoiner joiner = new StringJoiner("\n");
    values.stream().sorted().forEach(joiner::add);
    return sha256(joiner.toString());
  }

  public static String hash(Map<String, String> values) {
    StringJoiner joiner = new StringJoiner("\n");
    new TreeMap<>(values).forEach((key, value) -> joiner.add(key + "=" + value));
    return sha256(joiner.toString());
  }

  private static String sha256(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(bytes.length * 2);
      for (byte b : bytes) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
