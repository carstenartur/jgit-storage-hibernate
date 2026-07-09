/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaAnalysisRun;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaReferenceIndex;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaSymbolIndex;
import java.util.List;
import java.util.Objects;

/** Result of analyzing one Java source snapshot. */
public record JavaAnalysisResult(
    JavaAnalysisRun analysisRun,
    List<JavaSymbolIndex> symbols,
    List<JavaReferenceIndex> references) {

  public JavaAnalysisResult {
    Objects.requireNonNull(analysisRun, "analysisRun");
    symbols = List.copyOf(Objects.requireNonNull(symbols, "symbols"));
    references = List.copyOf(Objects.requireNonNull(references, "references"));
  }
}
