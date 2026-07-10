/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaSymbolIndex;
import java.util.Objects;

/** One structured declaration change with before/after provenance. */
public record SemanticChange(
    SemanticChangeKind kind,
    JavaSymbolIndex before,
    JavaSymbolIndex after,
    double confidence,
    String evidence) {

  public SemanticChange {
    Objects.requireNonNull(kind, "kind");
    if (before == null && after == null) {
      throw new IllegalArgumentException("A semantic change needs a before or after symbol");
    }
    if (confidence < 0.0 || confidence > 1.0) {
      throw new IllegalArgumentException("confidence must be between 0 and 1");
    }
  }
}
