/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import java.util.List;
import java.util.Objects;

/** Configuration that defines the semantic context for one Java source analysis run. */
public record JavaAnalysisConfiguration(
    String sourceLevel,
    BindingMode bindingMode,
    List<String> classpathEntries,
    List<String> sourcepathEntries,
    List<String> encodings,
    List<String> modulepathEntries,
    boolean includeRunningVmBootClasspath,
    String analyzerVersion) {

  public static final String DEFAULT_ANALYZER_VERSION = "java-analysis-mvp-1";

  public JavaAnalysisConfiguration {
    sourceLevel = Objects.requireNonNullElse(sourceLevel, "21");
    bindingMode = Objects.requireNonNullElse(bindingMode, BindingMode.RECOVERY);
    classpathEntries = List.copyOf(Objects.requireNonNullElse(classpathEntries, List.of()));
    sourcepathEntries = List.copyOf(Objects.requireNonNullElse(sourcepathEntries, List.of()));
    encodings = List.copyOf(Objects.requireNonNullElse(encodings, List.of()));
    modulepathEntries = List.copyOf(Objects.requireNonNullElse(modulepathEntries, List.of()));
    analyzerVersion = Objects.requireNonNullElse(analyzerVersion, DEFAULT_ANALYZER_VERSION);
  }

  /** Binding-aware Java 21 default with JDT binding recovery enabled. */
  public static JavaAnalysisConfiguration java21BindingAware() {
    return new JavaAnalysisConfiguration(
        "21", BindingMode.RECOVERY, List.of(), List.of(), List.of(), List.of(), true,
        DEFAULT_ANALYZER_VERSION);
  }

  /** Java 21 syntax-only default for comparing binding-aware and syntax-only results. */
  public static JavaAnalysisConfiguration java21SyntaxOnly() {
    return new JavaAnalysisConfiguration(
        "21", BindingMode.DISABLED, List.of(), List.of(), List.of(), List.of(), true,
        DEFAULT_ANALYZER_VERSION);
  }

  public boolean resolveBindings() {
    return bindingMode != BindingMode.DISABLED;
  }

  public boolean recoverBindings() {
    return bindingMode == BindingMode.RECOVERY;
  }
}
