/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 *
 * This program and the accompanying materials are made available under the
 * terms of the BSD 3-Clause License.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaReferenceIndex;
import io.github.carstenartur.jgit.storage.hibernate.javaanalysis.entity.JavaSymbolIndex;
import org.junit.jupiter.api.Test;

class JavaJdtAnalyzerTest {

  private static final String SOURCE =
      """
      package com.example;

      import java.util.List;

      public class UserService {
        private final List<String> names;

        public UserService(List<String> names) {
          this.names = names;
        }

        public List<String> names() {
          return names;
        }

        public int count() {
          return names.size();
        }
      }
      """;

  @Test
  void bindingAwareAnalysisCapturesSymbolsReferencesAndBindingContext() {
    JavaAnalysisResult result = new JavaJdtAnalyzer().analyze(snapshot(), JavaAnalysisConfiguration.java21BindingAware());

    assertEquals(JavaAnalysisStatus.COMPLETED, result.analysisRun().getStatus());
    assertEquals(BindingMode.RECOVERY, result.analysisRun().getBindingMode());
    assertEquals("21", result.analysisRun().getSourceLevel());
    assertEquals(64, result.analysisRun().getCompilerOptionsHash().length());
    assertEquals(64, result.analysisRun().getClasspathHash().length());

    assertSymbol(result, JavaSymbolKind.TYPE, "UserService");
    assertSymbol(result, JavaSymbolKind.FIELD, "names");
    assertSymbol(result, JavaSymbolKind.CONSTRUCTOR, "UserService");
    assertSymbol(result, JavaSymbolKind.METHOD, "names");
    assertSymbol(result, JavaSymbolKind.METHOD, "count");

    assertReference(result, JavaReferenceKind.IMPORT, "java.util.List");
    assertReference(result, JavaReferenceKind.METHOD_INVOCATION, "size");

    assertTrue(
        result.symbols().stream().anyMatch(symbol -> symbol.getRawBindingKey() != null),
        "At least one declaration should carry a JDT binding key in binding-aware mode");
    assertTrue(
        result.symbols().stream()
            .allMatch(symbol -> symbol.getStableSemanticKey() != null && !symbol.getStableSemanticKey().isBlank()));
  }

  @Test
  void syntaxOnlyAnalysisKeepsBindingColumnsEmptyButStillCapturesSymbols() {
    JavaAnalysisResult result = new JavaJdtAnalyzer().analyze(snapshot(), JavaAnalysisConfiguration.java21SyntaxOnly());

    assertEquals(JavaAnalysisStatus.COMPLETED, result.analysisRun().getStatus());
    assertEquals(BindingMode.DISABLED, result.analysisRun().getBindingMode());
    assertFalse(result.symbols().isEmpty());
    assertTrue(result.symbols().stream().allMatch(symbol -> symbol.getBindingStatus() == BindingStatus.NONE));
    assertTrue(result.symbols().stream().allMatch(symbol -> symbol.getRawBindingKey() == null));
  }

  private static JavaSourceSnapshot snapshot() {
    return new JavaSourceSnapshot(
        "demo", "0123456789abcdef0123456789abcdef01234567", "abcdef", "src/main/java/com/example/UserService.java", SOURCE);
  }

  private static void assertSymbol(JavaAnalysisResult result, JavaSymbolKind kind, String simpleName) {
    JavaSymbolIndex symbol =
        result.symbols().stream()
            .filter(candidate -> candidate.getSymbolKind() == kind)
            .filter(candidate -> simpleName.equals(candidate.getSimpleName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing symbol " + kind + " " + simpleName));
    assertNotNull(symbol.getQualifiedName());
    assertTrue(symbol.getStartLine() > 0);
    assertTrue(symbol.getEndLine() >= symbol.getStartLine());
  }

  private static void assertReference(JavaAnalysisResult result, JavaReferenceKind kind, String name) {
    JavaReferenceIndex reference =
        result.references().stream()
            .filter(candidate -> candidate.getReferenceKind() == kind)
            .filter(candidate -> name.equals(candidate.getReferenceName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing reference " + kind + " " + name));
    assertTrue(reference.getStartLine() > 0);
    assertTrue(reference.getEndLine() >= reference.getStartLine());
  }
}
