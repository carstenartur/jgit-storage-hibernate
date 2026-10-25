/*
 * Copyright (C) 2026, Carsten Hammer and contributors.
 * SPDX-License-Identifier: BSD-3-Clause
 */
package io.github.carstenartur.jgit.storage.hibernate.javaanalysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class JavaSourceTextExtractorTest {

  @Test
  void extractsDocumentationAndDecodedLiteralsWithSourceLocations() {
    String source = "package example;\n"
        + "/** Orchard documentation with {@link String}. */\n"
        + "class Example {\n"
        + "  // ignored comment with \"fake literal\"\n"
        + "  String value = \"line\\nvalue\";\n"
        + "  String block = \"\"\"\n"
        + "      multi line\n"
        + "      \"\"\";\n"
        + "  /** Method documentation. */ void run() {}\n"
        + "}\n";
    JavaSourceText result = extract(source);

    assertEquals(0, result.errorCount());
    assertEquals(2, result.documentation().size());
    assertEquals("/** Orchard documentation with {@link String}. */",
        result.documentation().getFirst().text());
    assertEquals(2, result.documentation().getFirst().startLine());
    assertEquals(List.of("line\nvalue", "multi line\n"),
        result.stringLiterals().stream().map(JavaSourceText.Fragment::text).toList());
    assertEquals(5, result.stringLiterals().getFirst().startLine());
    assertEquals(8, result.stringLiterals().getLast().endLine());
    var literal = result.stringLiterals().getFirst();
    assertEquals("\"line\\nvalue\"",
        source.substring(literal.startPosition(), literal.startPosition() + literal.sourceLength()));
    assertThrows(UnsupportedOperationException.class, () -> result.documentation().clear());
    assertThrows(UnsupportedOperationException.class, () -> result.stringLiterals().clear());
  }

  @Test
  void recognizesDocumentedModuleDescriptorsFromTheirSnapshotPath() {
    for (String path : List.of("src/main/java/module-info.java", "src\\main\\java\\module-info.java")) {
      JavaSourceText result = new JavaSourceTextExtractor().extract(
          new JavaSourceSnapshot("repo", "commit", "blob", path,
              "/** Module documentation. */\nmodule example.application { exports example.api; }"),
          JavaAnalysisConfiguration.java21BindingAware());
      assertEquals(0, result.errorCount(), path);
      assertEquals(1, result.documentation().size(), path);
      assertEquals("/** Module documentation. */", result.documentation().getFirst().text());
      assertEquals(1, result.documentation().getFirst().startLine());
    }
  }

  @Test
  void retainsUnattachedJavadocCommentsInSourceOrder() {
    JavaSourceText result = extract("/** first */ /** second */ class Example {} /** last */");
    assertEquals(List.of("/** first */", "/** second */", "/** last */"),
        result.documentation().stream().map(JavaSourceText.Fragment::text).toList());
  }

  @Test
  void keepsRecoverableTextAndReportsSyntaxErrorsWithoutResolvingMissingTypes() {
    JavaSourceText result = extract("/** retained */ class Example { Missing value; String s = \"kept\"; }");
    assertEquals(0, result.errorCount(), "source text extraction must not require a classpath");
    assertEquals("kept", result.stringLiterals().getFirst().text());

    JavaSourceText malformed = extract("/** retained */ class Example { void broken( }");
    assertTrue(malformed.errorCount() > 0);
    assertTrue(malformed.problemCount() >= malformed.errorCount());
    assertFalse(malformed.documentation().isEmpty());
    assertTrue(extract("").documentation().isEmpty());
    assertTrue(extract("").stringLiterals().isEmpty());
  }

  private static JavaSourceText extract(String source) {
    return new JavaSourceTextExtractor().extract(
        new JavaSourceSnapshot("repo", "commit", "blob", "example/Example.java", source),
        JavaAnalysisConfiguration.java21BindingAware());
  }
}
