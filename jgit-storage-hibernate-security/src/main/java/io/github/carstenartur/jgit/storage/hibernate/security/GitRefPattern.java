/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package io.github.carstenartur.jgit.storage.hibernate.security;

import java.util.regex.Pattern;

final class GitRefPattern {

  private GitRefPattern() {}

  static void validate(String pattern) {
    if (pattern == null || pattern.isBlank() || !pattern.startsWith("refs/")) {
      throw new IllegalArgumentException("refPattern must start with refs/");
    }
    if (pattern.length() > 1024) {
      throw new IllegalArgumentException("refPattern must contain at most 1024 characters");
    }
    for (int index = 0; index < pattern.length(); index++) {
      char character = pattern.charAt(index);
      if (Character.isISOControl(character) || character == '\\') {
        throw new IllegalArgumentException(
            "refPattern must not contain control characters or backslashes");
      }
    }
  }

  static boolean matches(String pattern, String refName) {
    validate(pattern);
    return Pattern.matches(toRegex(pattern), refName);
  }

  static int specificity(String pattern) {
    validate(pattern);
    int literalCharacters = 0;
    for (int index = 0; index < pattern.length(); index++) {
      char character = pattern.charAt(index);
      if (character != '*' && character != '?') {
        literalCharacters++;
      }
    }
    return literalCharacters;
  }

  private static String toRegex(String pattern) {
    StringBuilder regex = new StringBuilder(pattern.length() * 2).append('^');
    for (int index = 0; index < pattern.length(); index++) {
      char character = pattern.charAt(index);
      if (character == '*') {
        if (index + 1 < pattern.length() && pattern.charAt(index + 1) == '*') {
          regex.append(".*");
          index++;
        } else {
          regex.append("[^/]*");
        }
      } else if (character == '?') {
        regex.append("[^/]");
      } else {
        if (".[]{}()+-^$|".indexOf(character) >= 0) {
          regex.append('\\');
        }
        regex.append(character);
      }
    }
    return regex.append('$').toString();
  }
}
