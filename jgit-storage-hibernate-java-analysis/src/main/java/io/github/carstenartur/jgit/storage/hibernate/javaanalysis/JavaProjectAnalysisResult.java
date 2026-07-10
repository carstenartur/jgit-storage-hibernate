/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaReferenceIndex;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaSymbolIndex;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Aggregated result of analyzing all Java sources at one commit. */
public record JavaProjectAnalysisResult(
    JavaProjectSnapshot project,
    Map<String, JavaAnalysisResult> files,
    List<JavaSymbolIndex> symbols,
    List<JavaReferenceIndex> references,
    int problemCount,
    int errorCount) {

  public JavaProjectAnalysisResult {
    Objects.requireNonNull(project, "project");
    files = Map.copyOf(Objects.requireNonNull(files, "files"));
    symbols = List.copyOf(Objects.requireNonNull(symbols, "symbols"));
    references = List.copyOf(Objects.requireNonNull(references, "references"));
  }

  public boolean complete() {
    return errorCount == 0 && files.size() == project.sources().size();
  }
}
