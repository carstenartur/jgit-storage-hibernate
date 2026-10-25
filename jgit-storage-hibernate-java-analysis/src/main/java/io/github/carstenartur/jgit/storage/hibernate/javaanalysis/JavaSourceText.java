/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import java.util.List;
import java.util.Objects;

/**
 * Searchable source text without a dependency on resolved Java bindings.
 * Javadoc comments retain their source spelling, including delimiters and tags;
 * string literals and text blocks contain their decoded values. Lists follow source order.
 * Syntax diagnostics allow callers to distinguish complete and recovered extraction.
 */
public record JavaSourceText(
    List<Fragment> documentation, List<Fragment> stringLiterals, int problemCount, int errorCount) {

  public JavaSourceText {
    documentation = List.copyOf(documentation);
    stringLiterals = List.copyOf(stringLiterals);
  }

  /** A text fragment with zero-based character offsets and one-based source lines. */
  public record Fragment(
      String text, int startPosition, int sourceLength, int startLine, int endLine) {
    public Fragment {
      Objects.requireNonNull(text, "text");
    }
  }
}
